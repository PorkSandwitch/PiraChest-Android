package com.pirachest.android.feature.anime.data.providers.common

import android.util.Base64
import okhttp3.OkHttpClient
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object MegaplayResolver {

    private const val BASE = "https://megaplay.buzz"
    private const val REFERER = "$BASE/"
    private val EMBED_RE = Regex(
        """https?://(?:[\w.-]+\.)?megaplay\.[\w.-]+/stream/[^\s"'<>\\]+""",
        RegexOption.IGNORE_CASE
    )
    private val DATA_ID_RE = Regex("""data-id="(\d+)"""")
    private val KEY = "i?LMTAx0Q6,:}50U".toByteArray(Charsets.UTF_8)
    private val IV = "W0;27ToaUpl_P%'c".toByteArray(Charsets.UTF_8)

    fun findEmbedUrl(html: String): String? = EMBED_RE.find(html)?.value

    fun decryptPayload(payload: JSONObject): JSONObject? {
        val enc = payload.optString("enc", "")
        if (enc.isBlank()) return null
        return try {
            val key = KEY.copyOf(32)
            val ciphertext = Base64.decode(enc, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(IV))
            val plain = cipher.doFinal(ciphertext)
            JSONObject(plain.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    data class Resolved(
        val url: String,
        val referer: String,
        val fileId: String,
        val subtitles: List<Subtitle>,
        val intro: Pair<Double, Double>?,
        val outro: Pair<Double, Double>?
    )

    data class Subtitle(val url: String, val label: String, val srclang: String, val default: Boolean)

    fun resolve(client: OkHttpClient, pageUrl: String, referer: String? = null): Resolved {
        val url = findEmbedUrl(pageUrl) ?: pageUrl
        val referers = if (referer != null && referer != REFERER) listOf(referer, REFERER) else listOf(REFERER)

        var fileId: String? = null
        var lastErr: Exception? = null
        for (ref in referers) {
            try {
                val page = ProviderHttp.get(client, url, mapOf("Referer" to ref, "Accept-Language" to "en-US,en;q=0.9"))
                val m = DATA_ID_RE.find(page) ?: throw RuntimeException("megaplay: no data-id on $url")
                fileId = m.groupValues[1]
                break
            } catch (e: Exception) {
                lastErr = e
            }
        }
        if (fileId == null) throw lastErr ?: RuntimeException("megaplay: no data-id on $url")

        val body = ProviderHttp.get(
            client,
            "$BASE/stream/getSources?id=$fileId&platform=OTHER",
            mapOf("Referer" to url, "X-Requested-With" to "XMLHttpRequest")
        )
        val payload = try { JSONObject(body) } catch (_: Exception) { JSONObject() }

        val decrypted = decryptPayload(payload)
        val file = decrypted?.optString("file")?.takeIf { it.isNotBlank() }
            ?: payload.optJSONObject("sources")?.optString("file")?.takeIf { it.isNotBlank() }
            ?: throw RuntimeException("megaplay: no playable source for $url")

        val subs = mutableListOf<Subtitle>()
        val tracks = payload.optJSONArray("tracks")
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                val fileUrl = t.optString("file", "")
                if (fileUrl.isBlank()) continue
                val label = t.optString("label").ifBlank { t.optString("language", "Subtitle") }
                subs.add(Subtitle(fileUrl, label, label.take(2).lowercase(), t.optBoolean("default", false)))
            }
        }

        return Resolved(
            url = file,
            referer = REFERER,
            fileId = fileId,
            subtitles = subs,
            intro = parseSkip(payload, "intro"),
            outro = parseSkip(payload, "outro")
        )
    }

    private fun parseSkip(payload: JSONObject, key: String): Pair<Double, Double>? {
        val o = payload.optJSONObject(key) ?: return null
        val s = o.optDouble("start", 0.0)
        val e = o.optDouble("end", 0.0)
        return if (s != 0.0 || e != 0.0) s to e else null
    }
}