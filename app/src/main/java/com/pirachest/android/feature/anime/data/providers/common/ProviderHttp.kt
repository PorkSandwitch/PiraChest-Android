package com.pirachest.android.feature.anime.data.providers.common

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

object ProviderHttp {

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val FORM = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

    fun get(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String> = emptyMap()
    ): String {
        val b = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> b.header(k, v) }
        return client.newCall(b.build()).execute().use { it.body?.string().orEmpty() }
    }

    fun getJson(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String> = emptyMap()
    ): String = get(client, url, headers)

    fun postJson(
        client: OkHttpClient,
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): String {
        val b = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .post(body.toRequestBody(JSON))
        headers.forEach { (k, v) -> b.header(k, v) }
        return client.newCall(b.build()).execute().use { it.body?.string().orEmpty() }
    }

    fun postForm(
        client: OkHttpClient,
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): String {
        val b = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .post(body.toRequestBody(FORM))
        headers.forEach { (k, v) -> b.header(k, v) }
        return client.newCall(b.build()).execute().use { it.body?.string().orEmpty() }
    }

    fun doc(html: String, base: String = ""): Document = Jsoup.parse(html, base)

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun origin(url: String): String {
        val u = java.net.URI(url)
        return "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
    }

    fun attr(tagHtml: String, name: String): String {
        val m = Regex("""$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tagHtml)
        return m?.groupValues?.get(1) ?: ""
    }

    fun stripTags(html: String): String =
        html.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").trim()

    fun decodeEntities(s: String): String = stripTags(s)

    fun firstMatch(regex: Regex, text: String): String? =
        regex.find(text)?.groupValues?.getOrNull(1)

    fun allMatches(regex: Regex, text: String): List<MatchResult> = regex.findAll(text).toList()

    fun sliceBalanced(text: String, start: Int, open: Char, close: Char): String? {
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun cleanJson(s: String): String =
        s.replace("\\u0026", "&").replace("\\\"", "\"").replace("\\\\", "\\")

    fun buildTitles(english: String?, romaji: String?, synonyms: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        english?.trim()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        romaji?.trim()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        synonyms.forEach { s -> s.trim().takeIf { it.isNotBlank() }?.let { out.add(it) } }
        return out.toList()
    }
}