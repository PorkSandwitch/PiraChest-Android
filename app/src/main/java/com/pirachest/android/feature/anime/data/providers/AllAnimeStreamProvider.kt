package com.pirachest.android.feature.anime.data.providers

import android.content.Context
import com.pirachest.android.feature.anime.data.AnimeStreamProvider
import com.pirachest.android.feature.anime.data.VideoStreamSource
import com.pirachest.android.feature.anime.data.WebViewStreamSniffer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class AllAnimeStreamProvider(
    private val context: Context,
    private val client: OkHttpClient
) : AnimeStreamProvider {

    override val id: String = "allanime"
    override val name: String = "AllAnime"
    private val apiUrl = "https://api.allanime.day/api"
    private val referer = "https://allanime.to"

    override suspend fun resolveEpisodeStreams(
        animeTitles: List<String>,
        episodeNumber: Int
    ): List<VideoStreamSource> {
        for (title in animeTitles.distinct()) {
            val showId = findShowId(title) ?: continue
            val streams = fetchEpisodeSources(showId, episodeNumber.toString())
            if (streams.isNotEmpty()) return streams
        }
        return emptyList()
    }

    private fun findShowId(query: String): String? {
        val gql = """
            query(${"$"}search: SearchInput, ${"$"}limit: Int, ${"$"}page: Int, ${"$"}translationType: VaildTranslationTypeEnumType, ${"$"}countryOrigin: VaildCountryOriginEnumType) {
              shows(search: ${"$"}search, limit: ${"$"}limit, page: ${"$"}page, translationType: ${"$"}translationType, countryOrigin: ${"$"}countryOrigin) {
                edges { _id name }
              }
            }
        """.trimIndent()

        val variables = JSONObject().apply {
            put("search", JSONObject().apply {
                put("allowAdult", false)
                put("allowUnknown", false)
                put("query", query)
            })
            put("limit", 5)
            put("page", 1)
            put("translationType", "sub")
            put("countryOrigin", "ALL")
        }

        val url = apiUrl.toHttpUrl().newBuilder()
            .addQueryParameter("variables", variables.toString())
            .addQueryParameter("query", gql)
            .build()

        val req = Request.Builder()
            .url(url)
            .header("Referer", referer)
            .header("User-Agent", USER_AGENT)
            .build()

        val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
        val edges = JSONObject(body)
            .optJSONObject("data")
            ?.optJSONObject("shows")
            ?.optJSONArray("edges") ?: return null

        if (edges.length() == 0) return null
        return edges.getJSONObject(0).optString("_id").takeIf { it.isNotBlank() }
    }

    private suspend fun fetchEpisodeSources(showId: String, epNum: String): List<VideoStreamSource> {
        val gql = """
            query(${"$"}showId: String!, ${"$"}translationType: VaildTranslationTypeEnumType!, ${"$"}episodeString: String!) {
              episode(showId: ${"$"}showId, translationType: ${"$"}translationType, episodeString: ${"$"}episodeString) {
                sourceUrls
              }
            }
        """.trimIndent()

        val variables = JSONObject().apply {
            put("showId", showId)
            put("translationType", "sub")
            put("episodeString", epNum)
        }

        val url = apiUrl.toHttpUrl().newBuilder()
            .addQueryParameter("variables", variables.toString())
            .addQueryParameter("query", gql)
            .build()

        val req = Request.Builder()
            .url(url)
            .header("Referer", referer)
            .header("User-Agent", USER_AGENT)
            .build()

        val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
        val sources = JSONObject(body)
            .optJSONObject("data")
            ?.optJSONObject("episode")
            ?.optJSONArray("sourceUrls") ?: return emptyList()

        val results = mutableListOf<VideoStreamSource>()
        for (i in 0 until sources.length()) {
            val srcObj = sources.getJSONObject(i)
            val sourceName = srcObj.optString("sourceName", "Server ${i + 1}")
            val decoded = decodeSourceUrl(srcObj.optString("sourceUrl"))

            if (decoded.startsWith("http") && (decoded.contains(".m3u8") || decoded.contains(".mp4"))) {
                results.add(
                    VideoStreamSource(
                        providerName = "$name ($sourceName)",
                        url = decoded,
                        quality = "Auto",
                        isHls = decoded.contains(".m3u8"),
                        headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
                    )
                )
            } else if (decoded.startsWith("http")) {
                val sniffed = WebViewStreamSniffer.sniffStreamUrl(
                    context = context,
                    embedUrl = decoded,
                    referer = referer,
                    timeoutMs = 10_000L
                )
                if (sniffed != null) {
                    results.add(sniffed.copy(providerName = "$name ($sourceName)"))
                }
            }
        }
        return results
    }

    private fun decodeSourceUrl(raw: String): String {
        if (!raw.startsWith("--")) return raw
        val hex = raw.removePrefix("--")
        return buildString {
            for (i in hex.indices step 2) {
                if (i + 1 < hex.length) {
                    val byteVal = hex.substring(i, i + 2).toIntOrNull(16) ?: continue
                    append((byteVal xor 0x38).toChar())
                }
            }
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
    }
}