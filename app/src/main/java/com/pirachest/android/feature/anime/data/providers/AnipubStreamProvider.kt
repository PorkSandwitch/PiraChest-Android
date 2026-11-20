package com.pirachest.android.feature.anime.data.providers

import android.content.Context
import android.util.Log
import com.pirachest.android.feature.anime.data.AnimeEpisode
import com.pirachest.android.feature.anime.data.AnimeStreamProvider
import com.pirachest.android.feature.anime.data.SubtitleTrack
import com.pirachest.android.feature.anime.data.VideoStreamSource
import com.pirachest.android.feature.anime.data.WebViewStreamSniffer
import com.pirachest.android.feature.anime.data.providers.common.MegaplayResolver
import com.pirachest.android.feature.anime.data.providers.common.ProviderHttp
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

class AnipubStreamProvider(
    private val context: Context,
    private val client: OkHttpClient
) : AnimeStreamProvider {
    override val id = "anipub"
    override val name = "AniPub"
    private val baseUrl = "https://www.anipub.org"
    private val tag = "AniPubProvider"

    private val stripRe = Regex("""\b(?:season|part|cour)\s*\d+\b|\b\d+(?:st|nd|rd|th)\s+season\b""", RegexOption.IGNORE_CASE)
    private val parenRe = Regex("""\([^)]*\)""")
    private val srcRe = Regex("""src=["']?(https?://[^\s"'<>]+)""", RegexOption.IGNORE_CASE)
    private val whitespaceRe = Regex("""\s+""")

    private data class Candidate(val id: String, val title: String, val finder: String, val score: Double)

    override suspend fun discoverEpisodes(animeTitles: List<String>, animeTitle: String): List<AnimeEpisode> {
        val titles = animeTitles.filter { it.isNotBlank() && !it.startsWith("mal:", true) }.distinct()
        if (titles.isEmpty()) return emptyList()
        for (candidate in searchCandidates(titles).take(3)) {
            val local = fetchDetails(candidate.id) ?: continue
            val eps = local.optJSONArray("ep") ?: JSONArray()
            val count = eps.length() + 1 
            if (count < 1) continue
            return (1..count).map { number ->
                val epTitle = if (number >= 2) eps.optJSONObject(number - 2)?.optString("name", "")?.trim().orEmpty() else ""
                AnimeEpisode(number, epTitle.ifBlank { "Episode $number" }, animeTitle, providerId = id)
            }
        }
        return emptyList()
    }

    override suspend fun resolveEpisodeStreams(animeTitles: List<String>, episodeNumber: Int): List<VideoStreamSource> {
        val titles = animeTitles.filter { it.isNotBlank() && !it.startsWith("mal:", true) }.distinct()
        if (titles.isEmpty() || episodeNumber < 1) return emptyList()
        for (candidate in searchCandidates(titles).take(3)) {
            val local = fetchDetails(candidate.id) ?: continue
            val sourceUrl = episodeUrl(local, episodeNumber) ?: continue
            val all = mutableListOf<VideoStreamSource>()
            for (audio in listOf("sub", "dub")) {
                val rewritten = rewriteAudio(sourceUrl, audio)
                val targets = listOf(rewritten, sourceUrl).distinct()
                for (target in targets) {
                    val streams = resolveFromPage(target, audio)
                    if (streams.isNotEmpty()) {
                        all += streams
                        break
                    }
                }
            }
            if (all.isNotEmpty()) return all.distinctBy { it.url to it.providerName }
        }
        return emptyList()
    }

    private fun searchCandidates(titles: List<String>): List<Candidate> {
        val queries = LinkedHashSet<String>()
        for (title in titles) {
            val stripped = whitespaceRe.replace(stripRe.replace(title, ""), " ").trim(' ', '-', ':')
            val noParens = whitespaceRe.replace(parenRe.replace(title, " "), " ").trim(' ', '-', ':')
            listOf(title.trim(), stripped, noParens).filter { it.length >= 3 }.forEach(queries::add)
        }

        val found = linkedMapOf<String, Candidate>()
        for (query in queries.take(5)) {
            try {
                val url = "$baseUrl/api/search/${ProviderHttp.encode(query).replace("+", "%20")}"
                val body = ProviderHttp.get(client, url, mapOf("Referer" to "$baseUrl/", "Accept" to "application/json"))
                val items = try {
                    val trimmed = body.trim()
                    when {
                        trimmed.startsWith("[") -> {
                            val array = JSONArray(trimmed)
                            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                        }
                        trimmed.startsWith("{") -> {
                            val obj = JSONObject(trimmed)
                            if (obj.has("Id")) listOf(obj) else emptyList()
                        }
                        else -> emptyList()
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Bad search JSON for '$query'", e)
                    emptyList()
                }
                for (item in items) {
                    val id = item.optString("Id", "").trim()
                    val title = item.optString("Name", "").trim()
                    if (id.isBlank() || title.isBlank()) continue
                    val finder = item.optString("finder", "").trim()
                    val score = maxOf(titleScore(title, titles), if (finder.isNotBlank()) titleScore(finder, titles) else 0.0)
                    if (score >= 0.5) {
                        val old = found[id]
                        if (old == null || score > old.score) found[id] = Candidate(id, title, finder, score)
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "AniPub search failed for '$query'", e)
            }
        }
        return found.values.sortedByDescending { it.score }
    }

    private fun titleScore(candidate: String, titles: List<String>): Double {
        val c = candidate.lowercase()
        var best = 0.0
        for (title in titles) {
            val t = title.lowercase()
            if (c == t) best = maxOf(best, 1.0)
            else if (c.contains(t) || t.contains(c)) best = maxOf(best, 0.85)
            else {
                val words = t.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 2 }
                val hits = words.count { c.contains(it) }
                if (words.isNotEmpty()) best = maxOf(best, hits.toDouble() / words.size)
            }
        }
        return best
    }

    private fun fetchDetails(showId: String): JSONObject? = try {
        val body = ProviderHttp.get(
            client, "$baseUrl/v1/api/details/$showId",
            mapOf("Referer" to "$baseUrl/", "Accept" to "application/json")
        )
        JSONObject(body).optJSONObject("local")
    } catch (e: Exception) {
        Log.w(tag, "AniPub details failed for id $showId", e)
        null
    }

    private fun episodeUrl(local: JSONObject, number: Int): String? {
        val raw = if (number == 1) local.optString("link", "").takeIf { it.isNotBlank() } else {
            val eps = local.optJSONArray("ep") ?: return null
            eps.optJSONObject(number - 2)?.optString("link", "")?.takeIf { it.isNotBlank() }
        } ?: return null
        return srcRe.find(raw)?.groupValues?.get(1)
    }

    private fun rewriteAudio(url: String, audio: String): String {
        val typeParam = Regex("""([?&]type=)(sub|dub)\b""", RegexOption.IGNORE_CASE)
        if (typeParam.containsMatchIn(url)) return typeParam.replace(url) { "${it.groupValues[1]}$audio" }
        return Regex("""/(sub|dub)(?=[/?#]|$)""", RegexOption.IGNORE_CASE).replaceFirst(url, "/$audio")
    }

    private suspend fun resolveFromPage(pageUrl: String, audio: String): List<VideoStreamSource> {
        val page = try {
            ProviderHttp.get(client, pageUrl, mapOf("Referer" to "$baseUrl/", "Accept-Language" to "en-US,en;q=0.9"))
        } catch (e: Exception) {
            Log.w(tag, "Episode page failed: $pageUrl", e)
            return emptyList()
        }
        val embed = MegaplayResolver.findEmbedUrl(page) ?: return sniffFallback(pageUrl, audio)
        return try {
            val resolved = MegaplayResolver.resolve(client, embed, referer = pageUrl)
            val subtitleTracks = resolved.subtitles.map {
                SubtitleTrack(it.url, it.srclang.ifBlank { "und" }, it.label, subtitleMime(it.url), it.default)
            }
            listOf(
                VideoStreamSource(
                    providerName = "$name/${audio.uppercase()}",
                    url = resolved.url,
                    quality = "auto",
                    isHls = true,
                    headers = mapOf("User-Agent" to ProviderHttp.USER_AGENT, "Referer" to resolved.referer),
                    subtitles = subtitleTracks
                )
            )
        } catch (e: Exception) {
            Log.w(tag, "Megaplay resolution failed for $embed", e)
            sniffFallback(embed, audio)
        }
    }

    private suspend fun sniffFallback(url: String, audio: String): List<VideoStreamSource> {
        val sniffed = WebViewStreamSniffer.sniffStreamUrl(context, url, "$baseUrl/", 12_000L)
        return sniffed?.let { listOf(it.copy(providerName = "$name/${audio.uppercase()}")) } ?: emptyList()
    }

    private fun subtitleMime(url: String): String? = when (url.substringBefore("?").substringAfterLast(".", "").lowercase()) {
        "vtt" -> "text/vtt"
        "srt" -> "application/x-subrip"
        "ass", "ssa" -> "text/x-ssa"
        else -> null
    }
}
