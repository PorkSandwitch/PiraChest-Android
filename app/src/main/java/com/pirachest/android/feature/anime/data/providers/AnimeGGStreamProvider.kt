package com.pirachest.android.feature.anime.data.providers

import android.content.Context
import android.util.Base64
import android.util.Log
import com.pirachest.android.feature.anime.data.AnimeEpisode
import com.pirachest.android.feature.anime.data.AnimeStreamProvider
import com.pirachest.android.feature.anime.data.SubtitleTrack
import com.pirachest.android.feature.anime.data.VideoStreamSource
import com.pirachest.android.feature.anime.data.WebViewStreamSniffer
import com.pirachest.android.feature.anime.data.providers.common.ProviderHttp
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.net.URLDecoder

class AnimeGGStreamProvider(
    private val context: Context,
    private val client: OkHttpClient
) : AnimeStreamProvider {

    override val id = "animegg"
    override val name = "AnimeGG"
    private val baseUrl = "https://www.animegg.org"
    private val tag = "AnimeGGProvider"

    private val searchItemRe = Regex(
        """<a\b[^>]*class=["'][^"']*\bmse\b[^"']*["'][^>]*>[\s\S]*?</a>""",
        RegexOption.IGNORE_CASE
    )
    private val videoSourcesRe = Regex("""var\s+videoSources\s*=\s*(\[[\s\S]*?\]);""")
    private val unquotedKeyRe = Regex("""([{,]\s*)([a-zA-Z_][a-zA-Z0-9_]*)\s*:""")
    private val singleQuotedRe = Regex(""":\s*'([^']*)'""")

    private data class Candidate(val slug: String, val title: String, val score: Double)
    private data class EpisodeRef(val number: Int, val title: String, val href: String, val hasSub: Boolean, val hasDub: Boolean)
    private data class Tab(val embedId: String, val embedUrl: String, val server: String, val audio: String)
    private data class Source(val quality: String, val url: String, val backup: String?)

    override suspend fun discoverEpisodes(animeTitles: List<String>, animeTitle: String): List<AnimeEpisode> {
        val titles = animeTitles.filter { it.isNotBlank() && !it.startsWith("mal:", true) }.distinct()
        if (titles.isEmpty()) return emptyList()

        for (candidate in searchCandidates(titles).take(5)) {
            val refs = scrapeSeries(candidate.slug)
            if (refs.isNotEmpty()) {
                return refs.map {
                    AnimeEpisode(
                        number = it.number,
                        title = it.title.ifBlank { "Episode ${it.number}" },
                        animeTitle = animeTitle,
                        providerId = id,
                        sourceNumber = it.number
                    )
                }
            }
        }
        return emptyList()
    }

    override suspend fun resolveEpisodeStreams(
        animeTitles: List<String>,
        episodeNumber: Int
    ): List<VideoStreamSource> {
        val titles = animeTitles.filter { it.isNotBlank() && !it.startsWith("mal:", true) }.distinct()
        if (titles.isEmpty() || episodeNumber < 1) return emptyList()

        for (candidate in searchCandidates(titles).take(5)) {
            val episodes = scrapeSeries(candidate.slug)
            val episode = episodes.firstOrNull { it.number == episodeNumber } ?: continue
            val watchUrl = absolute(episode.href)
            val watchHtml = try {
                ProviderHttp.get(client, watchUrl, mapOf("Referer" to "$baseUrl/"))
            } catch (e: Exception) {
                Log.w(tag, "Episode page failed: $watchUrl", e)
                continue
            }
            if (watchHtml.isBlank()) continue

            val tabs = parseTabs(watchHtml)
            Log.i(tag, "AnimeGG episode $episodeNumber: found ${tabs.size} player tabs")
            val results = mutableListOf<VideoStreamSource>()
            for (tab in tabs) {
                val scraped = scrapeEmbed(tab.embedId, tab.embedUrl)
                val referer = runCatching { ProviderHttp.origin(tab.embedUrl) + "/" }.getOrDefault("$baseUrl/")
                for (source in scraped.first) {
                    results += VideoStreamSource(
                        providerName = "$name/${tab.audio.uppercase()}/${tab.server}",
                        url = source.url,
                        quality = source.quality,
                        isHls = source.url.contains(".m3u8", true),
                        headers = mapOf("User-Agent" to ProviderHttp.USER_AGENT, "Referer" to referer),
                        subtitles = scraped.second
                    )
                    source.backup?.takeIf { it.startsWith("http", true) }?.let { backup ->
                        results += VideoStreamSource(
                            providerName = "$name/${tab.audio.uppercase()}/${tab.server} backup",
                            url = backup,
                            quality = "${source.quality} backup",
                            isHls = backup.contains(".m3u8", true),
                            headers = mapOf("User-Agent" to ProviderHttp.USER_AGENT, "Referer" to referer),
                            subtitles = scraped.second
                        )
                    }
                }
                
                if (scraped.first.isEmpty()) {
                    WebViewStreamSniffer.sniffStreamUrl(
                        context = context,
                        embedUrl = tab.embedUrl,
                        referer = "$baseUrl/",
                        timeoutMs = 12_000L
                    )?.let { results += it.copy(providerName = "$name/${tab.audio.uppercase()}/${tab.server}", subtitles = scraped.second) }
                }
            }
            if (results.isNotEmpty()) return results.distinctBy { it.url }
            Log.w(tag, "AnimeGG found no direct streams for episode $episodeNumber; trying watch-page fallback")
            if (tabs.isEmpty()) {
                WebViewStreamSniffer.sniffStreamUrl(
                    context = context,
                    embedUrl = watchUrl,
                    referer = "$baseUrl/",
                    timeoutMs = 12_000L
                )?.let { return listOf(it.copy(providerName = name)) }
            }
        }
        return emptyList()
    }

    private fun searchCandidates(queryTitles: List<String>): List<Candidate> {
        val found = linkedMapOf<String, Candidate>()
        for (query in queryTitles.take(5)) {
            val html = try {
                ProviderHttp.get(client, "$baseUrl/search/?q=${ProviderHttp.encode(query)}", mapOf("Referer" to "$baseUrl/"))
            } catch (e: Exception) {
                Log.w(tag, "Search failed for $query", e)
                continue
            }
            for (match in searchItemRe.findAll(html)) {
                val block = match.value
                val anchor = Regex("""<a\b[^>]*>""", RegexOption.IGNORE_CASE).find(block)?.value ?: continue
                val href = ProviderHttp.attr(anchor, "href").trim()
                val path = runCatching { java.net.URI(href).path }.getOrNull().orEmpty().ifBlank { href }
                val slug = Regex("""(?:^|/)series/([^/?#]+)""").find(path)?.groupValues?.get(1) ?: continue
                val title = Regex("""<strong[^>]*>([\s\S]*?)</strong>""", RegexOption.IGNORE_CASE)
                    .find(block)?.groupValues?.get(1)?.let { ProviderHttp.stripTags(it) }
                    ?: slug.replace("-", " ")
                val score = titleScore(title, queryTitles)
                val old = found[slug]
                if (old == null || score > old.score) found[slug] = Candidate(slug, title, score)
            }
        }
        val ranked = found.values.sortedByDescending { it.score }
        Log.i(tag, "Search returned ${ranked.size} series candidates: ${ranked.take(5).joinToString { "${it.title} (${it.slug}, ${"%.2f".format(it.score)})" }}")
        return ranked
    }

    private fun titleScore(candidate: String, titles: List<String>): Double {
        val c = normalize(candidate)
        var best = 0.0
        for (title in titles) {
            val t = normalize(title)
            if (c == t) best = maxOf(best, 1.0)
            else if (c.contains(t) || t.contains(c)) best = maxOf(best, 0.88)
            else {
                val words = t.split(" ").filter { it.length >= 2 }
                val hits = words.count { c.contains(it) }
                if (words.isNotEmpty()) best = maxOf(best, hits.toDouble() / words.size)
            }
        }
        return best
    }

    private fun normalize(value: String) =
        value.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim().replace(Regex("""\s+"""), " ")

    private fun scrapeSeries(slug: String): List<EpisodeRef> {
        val html = try {
            ProviderHttp.get(client, "$baseUrl/series/$slug", mapOf("Referer" to "$baseUrl/"))
        } catch (e: Exception) {
            Log.w(tag, "Series page failed for $slug", e)
            return emptyList()
        }
        val episodes = mutableListOf<EpisodeRef>()
        for (match in Regex("""<li\b[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE).findAll(html)) {
            val block = match.groupValues[1]
            if (!Regex("""\banm_det_pop\b""").containsMatchIn(block)) continue
            val linkTag = Regex(
                """<a\b[^>]*class=["'][^"']*anm_det_pop[^"']*["'][^>]*>""",
                RegexOption.IGNORE_CASE
            ).find(block)?.value ?: continue
            val href = ProviderHttp.attr(linkTag, "href").substringBefore("#")
            val strong = Regex("""<strong[^>]*>([\s\S]*?)</strong>""", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.get(1)?.let { ProviderHttp.stripTags(it) }.orEmpty()
            val number = Regex("""(\d+)(?:-\d+)?\s*$""").find(strong)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            if (number < 1 || href.isBlank()) continue
            val episodeTitle = Regex(
                """<i\b[^>]*class=["'][^"']*anititle[^"']*["'][^>]*>([\s\S]*?)</i>""",
                RegexOption.IGNORE_CASE
            ).find(block)?.groupValues?.get(1)?.let { ProviderHttp.stripTags(it) }.orEmpty()
            episodes += EpisodeRef(
                number = number,
                title = episodeTitle.ifBlank { strong.ifBlank { "Episode $number" } },
                href = href,
                hasSub = Regex("""\bbtn-subbed\b""").containsMatchIn(block),
                hasDub = Regex("""\bbtn-dubbed\b""").containsMatchIn(block)
            )
        }
        val sorted = episodes.distinctBy { it.number }.sortedBy { it.number }
        Log.i(tag, "AnimeGG series $slug yielded ${sorted.size} episodes")
        return sorted
    }

    private fun parseTabs(html: String): List<Tab> {
        val tabs = mutableListOf<Tab>()
        for (match in Regex("""<a\b[^>]*data-toggle=["']tab["'][^>]*>""", RegexOption.IGNORE_CASE).findAll(html)) {
            val tag = match.value
            val embedId = ProviderHttp.attr(tag, "data-id")
            if (embedId.isBlank()) continue
            val server = ProviderHttp.attr(tag, "data-mirror").ifBlank { "AnimeGG" }
            val version = ProviderHttp.attr(tag, "data-version").ifBlank { "subbed" }
            val audio = if (version.startsWith("dub", true)) "dub" else "sub"
            tabs += Tab(embedId, "$baseUrl/embed/$embedId", server, audio)
        }
        return tabs
    }

    private fun scrapeEmbed(embedId: String, embedUrl: String): Pair<List<Source>, List<SubtitleTrack>> {
        val html = try {
            ProviderHttp.get(client, embedUrl, mapOf("Referer" to baseUrl))
        } catch (e: Exception) {
            Log.w(tag, "Embed fetch failed: $embedUrl", e)
            return emptyList<Source>() to emptyList()
        }
        val sources = mutableListOf<Source>()
        val sourceMatch = videoSourcesRe.find(html)
        if (sourceMatch != null) {
            val raw = sourceMatch.groupValues[1]
                .replace(unquotedKeyRe) { "${it.groupValues[1]}\"${it.groupValues[2]}\":" }
                .replace(singleQuotedRe) { ": \"${it.groupValues[1]}\"" }
            try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val file = item.optString("file", "")
                    if (file.isBlank()) continue
                    val url = if (file.startsWith("http", true)) file else "$baseUrl${if (file.startsWith("/")) "" else "/"}$file"
                    val backup = item.optString("bk", "").takeIf { it.isNotBlank() }?.let(::decodeBackup)
                    sources += Source(item.optString("label", "unknown"), url, backup)
                }
            } catch (e: Exception) {
                Log.w(tag, "Could not parse videoSources JSON", e)
            }
        }

        val subtitles = mutableListOf<SubtitleTrack>()

        val trackTags = Regex("""<(?:track)\b[^>]*>""", RegexOption.IGNORE_CASE)
        for (m in trackTags.findAll(html)) {
            val tag = m.value
            val src = ProviderHttp.attr(tag, "src")
                .ifBlank { ProviderHttp.attr(tag, "data-src") }
                .ifBlank { ProviderHttp.attr(tag, "data-file") }
            if (src.isBlank()) continue
            val url = resolveEmbedAsset(embedUrl, src) ?: continue
            val lang = ProviderHttp.attr(tag, "srclang")
                .ifBlank { ProviderHttp.attr(tag, "data-lang") }
                .ifBlank { "und" }
            val label = ProviderHttp.attr(tag, "label")
                .ifBlank { ProviderHttp.attr(tag, "data-label") }
                .ifBlank { lang }
            val mime = subtitleMime(url)
            subtitles += SubtitleTrack(url, lang, label, mime,
                ProviderHttp.attr(tag, "default").isNotBlank())
        }

        val configKeys = listOf("tracks", "captions", "subtitles", "subtitleTracks", "captionTracks")
        for (key in configKeys) {
            for (arrayText in extractAssignedArrays(html, key)) {
                val normalized = arrayText
                    .replace(unquotedKeyRe) { "${it.groupValues[1]}\"${it.groupValues[2]}\":" }
                    .replace(singleQuotedRe) { ": \"${it.groupValues[1]}\"" }
                val array = runCatching { JSONArray(normalized) }.getOrNull() ?: continue
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val rawUrl = sequenceOf("file", "src", "url", "source", "subtitle", "track")
                        .map { item.optString(it, "") }.firstOrNull { it.isNotBlank() } ?: continue
                    val url = resolveEmbedAsset(embedUrl, rawUrl) ?: continue
                    val language = sequenceOf("srclang", "language", "lang", "label")
                        .map { item.optString(it, "") }.firstOrNull { it.isNotBlank() } ?: "und"
                    val label = sequenceOf("label", "language", "lang", "srclang")
                        .map { item.optString(it, "") }.firstOrNull { it.isNotBlank() } ?: language
                    val mime = item.optString("type", "").takeIf { it.contains("/") }
                        ?: subtitleMime(url)
                    subtitles += SubtitleTrack(url, language, label, mime,
                        item.optBoolean("default", false) || item.optBoolean("isDefault", false))
                }
            }
        }

        val singleUrlRe = Regex("""(?:subtitle|caption)(?:Url|File|Src)\s*[:=]\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
        for (m in singleUrlRe.findAll(html)) {
            val url = resolveEmbedAsset(embedUrl, m.groupValues[1]) ?: continue
            subtitles += SubtitleTrack(url, "und", "Subtitle", subtitleMime(url), false)
        }
        val uniqueSubtitles = subtitles.distinctBy { it.url }
        Log.i(tag, "AnimeGG embed $embedId: extracted ${uniqueSubtitles.size} subtitle tracks and ${sources.size} video sources")
        if (uniqueSubtitles.isEmpty()) Log.w(tag, "No subtitle metadata found in embed $embedId")
        return sources to uniqueSubtitles
    }

    private fun extractAssignedArrays(html: String, key: String): List<String> {
        val out = mutableListOf<String>()
        val pattern = Regex("""(?:[\"']?$key[\"']?)\s*[:=]\s*\[""", RegexOption.IGNORE_CASE)
        for (match in pattern.findAll(html)) {
            val open = html.indexOf('[', match.range.first)
            if (open < 0) continue
            var depth = 0
            var quote: Char? = null
            var escaped = false
            for (i in open until html.length) {
                val c = html[i]
                if (quote != null) {
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == quote) quote = null
                    continue
                }
                if (c == '\"' || c == '\'') quote = c
                else if (c == '[') depth++
                else if (c == ']') {
                    depth--
                    if (depth == 0) {
                        out += html.substring(open, i + 1)
                        break
                    }
                }
            }
        }
        return out
    }

    private fun resolveEmbedAsset(embedUrl: String, raw: String): String? = try {
        val cleaned = raw.trim().replace("\\/", "/")
            .replace("&amp;", "&")
        java.net.URI(embedUrl).resolve(cleaned).toString()
            .takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    } catch (_: Exception) { null }

    private fun decodeBackup(encoded: String): String? = try {
        val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
        val decoded = Base64.decode(padded, Base64.DEFAULT).toString(Charsets.UTF_8)
        URLDecoder.decode(decoded, "UTF-8")
    } catch (_: Exception) { null }

    private fun subtitleMime(url: String): String? = when (url.substringBefore("?").substringAfterLast(".", "").lowercase()) {
        "vtt" -> "text/vtt"
        "srt" -> "application/x-subrip"
        "ass", "ssa" -> "text/x-ssa"
        else -> null
    }

    private fun absolute(href: String): String =
        if (href.startsWith("http", true)) href else "$baseUrl/${href.trimStart('/')}"
}
