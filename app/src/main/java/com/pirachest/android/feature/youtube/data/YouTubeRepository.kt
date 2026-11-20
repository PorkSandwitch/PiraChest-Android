package com.pirachest.android.feature.youtube.data

import android.content.Context
import android.os.Environment
import com.pirachest.android.core.downloads.MediaPublisher
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class VideoCodec(
    val label: String,
    val description: String,
    val ytDlpPrefix: String
) {
    H264(
        label = "H.264",
        description = "Widely compatible with phones, TVs and video editors. Usually larger files at similar visual quality.",
        ytDlpPrefix = "avc1"
    ),
    AV1(
        label = "AV1",
        description = "More efficient compression, often smaller files at similar visual quality. Encoding and playback support depend on your device.",
        ytDlpPrefix = "av01"
    )
}

data class VideoQuality(
    val label: String,
    val height: Int?,
    val selector: String,
    val estimatedBytes: Long? = null,
    val codec: VideoCodec = VideoCodec.H264
)
data class YouTubeVideo(
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val thumbnail: String?,
    val qualities: List<VideoQuality>
)
data class DownloadProgress(val percent: Float, val etaSeconds: Long)

data class DownloadResult(val file: File, val title: String, val savedLocation: String, val playUri: String)

private val PercentRegex = Regex("""(?<!\d)(\d{1,3}(?:\.\d+)?)%""")

class YouTubeRepository(private val context: Context) {
    private val inspectMutex = Mutex()

    fun quickPreview(url: String): YouTubeVideo {
        val id = youtubeVideoId(url)
        val thumbnail = id?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        return YouTubeVideo(
            title = "Loading video details…",
            uploader = "",
            durationSeconds = 0L,
            thumbnail = thumbnail,
            qualities = emptyList()
        )
    }

    suspend fun fetchQuickDetails(url: String): YouTubeVideo? = withContext(Dispatchers.IO) {
        validateUrl(url)
        val connection = (URL("https://www.youtube.com/oembed?url=${URLEncoder.encode(url.trim(), "UTF-8")}&format=json")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2500
            readTimeout = 2500
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val id = youtubeVideoId(url)
            YouTubeVideo(
                title = json.optString("title").takeIf { it.isNotBlank() } ?: "Loading video details…",
                uploader = json.optString("author_name"),
                durationSeconds = 0L,
                thumbnail = json.optString("thumbnail_url").takeIf { it.isNotBlank() }
                    ?: id?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
                qualities = emptyList()
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun youtubeVideoId(url: String): String? = runCatching {
        val uri = android.net.Uri.parse(url.trim())
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty().trim('/')
        when {
            host == "youtu.be" || host.endsWith(".youtu.be") -> path.substringBefore('/')
            uri.getQueryParameter("v") != null -> uri.getQueryParameter("v")
            path.startsWith("shorts/") -> path.removePrefix("shorts/").substringBefore('/')
            path.startsWith("embed/") -> path.removePrefix("embed/").substringBefore('/')
            path.startsWith("live/") -> path.removePrefix("live/").substringBefore('/')
            else -> null
        }?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,}")) }
    }.getOrNull()

    suspend fun inspect(url: String): YouTubeVideo = withContext(Dispatchers.IO) {
        validateUrl(url)

        inspectMutex.withLock {
            val cacheFile = infoFileFor(url)
            val legacyCacheFile = legacyInfoFileFor(url)
            val now = System.currentTimeMillis()
            fun readFresh(file: File): String? {
                if (!file.isFile || now - file.lastModified() >= INFO_CACHE_TTL_MS) return null
                val text = runCatching { file.readText() }.getOrNull() ?: return null
                if (!hasVideoFormats(text)) {
                    file.delete()
                    return null
                }
                return text
            }

            val cachedJson = readFresh(cacheFile) ?: readFresh(legacyCacheFile)
            val jsonText = cachedJson ?: run {

                val primary = try {
                    runYtDlpInfo(url)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w(TAG, "Default YouTube extraction failed; retrying IPv4", error)
                    null
                }

                val output = primary?.takeIf(::hasVideoFormats) ?: run {

                    val ipv4 = runYtDlpInfo(url, forceIpv4 = true)
                    if (!hasVideoFormats(ipv4)) {
                        error("YouTube returned no video formats. Try again or check whether the video is available in your region.")
                    }
                    ipv4
                }

                runCatching {
                    infoCacheDir().mkdirs()
                    val temporary = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
                    temporary.writeText(output)
                    if (!temporary.renameTo(cacheFile)) {
                        cacheFile.writeText(output)
                        temporary.delete()
                    }
                }.onFailure { android.util.Log.w(TAG, "Could not cache YouTube metadata", it) }
                output
            }

        val json = JSONObject(jsonText)
        val formats = json.optJSONArray("formats")
        val formatList = buildList {
            if (formats != null) {
                for (i in 0 until formats.length()) {
                    formats.optJSONObject(i)?.let { add(it) }
                }
            }
        }
        val duration = json.optLong("duration", 0L)
        val audioFormats = formatList.filter {
            it.optString("acodec", "none") != "none" &&
                it.optString("vcodec", "none") == "none"
        }
        val bestAudio = audioFormats.maxByOrNull {
            it.optDouble("abr", it.optDouble("tbr", 0.0))
        }
        fun estimatedBytes(format: JSONObject): Long? {
            val exact = format.optLong("filesize", -1L)
            if (exact > 0L) return exact
            val approximate = format.optLong("filesize_approx", -1L)
            return approximate.takeIf { it > 0L }
        }
        val audioBytes = bestAudio?.let(::estimatedBytes)

        val qualities = buildList {
            VideoCodec.values().forEach { codec ->
                val matchingFormats = formatList.filter {
                    it.optString("vcodec", "none").startsWith(codec.ytDlpPrefix)
                }
                val heights = matchingFormats
                    .map { it.optInt("height", 0) }
                    .filter { it > 0 }
                    .distinct()
                    .sortedDescending()

                for (height in heights) {
                    val bestVideo = matchingFormats
                        .filter { it.optInt("height", 0) == height }
                        .maxByOrNull { it.optDouble("tbr", 0.0) }
                        ?: continue
                    val videoBytes = estimatedBytes(bestVideo)
                    val videoHasAudio = bestVideo.optString("acodec", "none") != "none"
                    val estimate = when {
                        videoBytes != null && videoHasAudio -> videoBytes
                        videoBytes != null && audioBytes != null -> videoBytes + audioBytes
                        else -> {
                            val videoKbps = bestVideo.optDouble("tbr", 0.0)
                            val audioKbps = if (videoHasAudio) 0.0 else bestAudio?.let {
                                it.optDouble("abr", it.optDouble("tbr", 0.0))
                            } ?: 0.0
                            ((videoKbps + audioKbps) * 1000.0 / 8.0 * duration)
                                .toLong().takeIf { it > 0L }
                        }
                    }
                    add(
                        VideoQuality(
                            label = "${height}p",
                            height = height,
                            selector = "bestvideo[height=$height][vcodec^=${codec.ytDlpPrefix}]+bestaudio/best[height=$height][vcodec^=${codec.ytDlpPrefix}]",
                            estimatedBytes = estimate,
                            codec = codec
                        )
                    )
                }
            }
        }.sortedWith(
            compareBy<VideoQuality> { it.codec.ordinal }
                .thenByDescending { it.height ?: 0 }
        )
        YouTubeVideo(
            title = json.optString("title", "Untitled video"),
            uploader = json.optString("uploader", ""),
            durationSeconds = json.optLong("duration", 0L),
            thumbnail = json.optString("thumbnail").takeIf(String::isNotBlank),
            qualities = qualities
        )
        }
    }

    suspend fun download(
        url: String,
        audioOnly: Boolean,
        quality: VideoQuality,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        validateUrl(url)
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        val outputDir = File(baseDir, "PiraChest").apply { mkdirs() }
        val processId = UUID.randomUUID().toString()
        
        val taskDir = File(outputDir, processId).apply { mkdirs() }
        val cachedInfo = infoFileFor(url).takeIf {
            it.exists() && System.currentTimeMillis() - it.lastModified() < 30 * 60 * 1000L
        }
        fun newRequest(useCachedInfo: Boolean) = (
            if (useCachedInfo && cachedInfo != null) YoutubeDLRequest(emptyList<String>())
                .apply { addOption("--load-info-json", cachedInfo.absolutePath) }
            else YoutubeDLRequest(url.trim())
        ).apply {
            addOption("--cache-dir", ytDlpCacheDir().absolutePath)
            addOption("--no-mtime")
            addOption("--no-playlist")

            addOption("--http-chunk-size", "10M")
            addOption("--concurrent-fragments", "4")
            addOption("--socket-timeout", "20")
            addOption("--retries", "5")
            addOption("--fragment-retries", "10")
            addOption("--newline")
            addOption("--progress")
            addOption("--progress-delta", "1")
            addOption("-o", File(taskDir, "%(title).150B.%(ext)s").absolutePath)
            if (audioOnly) {
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "0")
            } else {
                addOption("-f", quality.selector)
                addOption("--merge-output-format", "mp4")
            }
            addOption("--print", "after_move:filepath")
        }
        val request = newRequest(true)
        val lastProgress = AtomicReference(0f)
        val lastEmitAt = java.util.concurrent.atomic.AtomicLong(0L)
        
        fun emit(percent: Float, eta: Long) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (percent <= lastProgress.get()) return
            if (percent - lastProgress.get() < 0.5f && now - lastEmitAt.get() < 250L) return
            lastProgress.set(percent)
            lastEmitAt.set(now)
            onProgress(DownloadProgress(percent, eta))
        }
        val expectedBytes = if (!audioOnly) quality.estimatedBytes else null
        val response = coroutineScope {
            val watcher = launch {
                while (currentCoroutineContext().isActive) {
                    val bytesOnDisk = taskDir.walkTopDown()
                        .filter { it.isFile }
                        .sumOf { it.length() }
                    if (expectedBytes != null && expectedBytes > 0L) {
                        val measured = (bytesOnDisk.toDouble() / expectedBytes * 100.0)
                            .toFloat().coerceIn(0f, 99f)
                        emit(measured, 0L)
                    }
                    delay(750)
                }
            }
            val onLine: (Float, Long, String?) -> Unit = { callbackPercent, eta, line ->
                val linePercent = PercentRegex
                    .find(line.orEmpty())
                    ?.groupValues?.getOrNull(1)?.toFloatOrNull()
                emit((linePercent ?: callbackPercent).coerceIn(0f, 99f), eta)
            }
            try {
                try {
                    YoutubeDL.getInstance().execute(request, processId, onLine)
                } catch (e: Exception) {
                    
                    if (cachedInfo == null) throw e
                    infoFileFor(url).delete()
                    YoutubeDL.getInstance().execute(newRequest(false), processId, onLine)
                }
            } finally {
                watcher.cancel()
            }
        }
        val output = response.out.trim().lineSequence().lastOrNull { it.isNotBlank() }
        val file = output?.let(::File)?.takeIf { it.exists() }
            ?: taskDir.walkTopDown()
                .filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                .maxByOrNull { it.length() }
            ?: error("Download finished but output file was not found.")
        val title = file.nameWithoutExtension
        val published = MediaPublisher.publish(context, file)
        
        runCatching { taskDir.deleteRecursively() }
        DownloadResult(file, title, published.location, published.uri)
    }

    private fun runYtDlpInfo(url: String, forceIpv4: Boolean = false): String {
        val request = YoutubeDLRequest(url.trim()).apply {
            addOption("--dump-single-json")
            addOption("--no-warnings")
            addOption("--skip-download")
            addOption("--no-playlist")
            addOption("--no-check-formats")
            addOption("--socket-timeout", "5")
            addOption("--retries", "1")
            addOption("--extractor-retries", "1")
            addOption("--no-progress")
            if (forceIpv4) addOption("--force-ipv4")
            
            addOption("--cache-dir", ytDlpCacheDir().absolutePath)
            addOption("--js-runtimes", "quickjs")
            addOption("--remote-components", "ejs:github")
        }
        val started = android.os.SystemClock.elapsedRealtime()
        val response = YoutubeDL.getInstance().execute(request, UUID.randomUUID().toString())
        android.util.Log.i(
            "PiraChestYtDlp",
            "inspect forceIpv4=$forceIpv4 took ${(android.os.SystemClock.elapsedRealtime() - started) / 1000}s"
        )
        return response.out
    }

    private fun hasVideoFormats(text: String): Boolean = runCatching {
        val formats = JSONObject(text).optJSONArray("formats") ?: return@runCatching false
        (0 until formats.length()).any {
            val f = formats.optJSONObject(it)
            f != null && f.optInt("height", 0) > 0 && f.optString("vcodec", "none") != "none"
        }
    }.getOrDefault(false)

    private fun ytDlpCacheDir() = File(context.filesDir, "ytdlp-cache").apply { mkdirs() }

    private fun infoCacheDir() = File(context.cacheDir, "ytinfo")

    private fun normalizedCacheIdentity(url: String): String {
        val uri = android.net.Uri.parse(url.trim())
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty().trim('/')
        val id = when {
            host == "youtu.be" || host.endsWith(".youtu.be") -> path.substringBefore('/')
            uri.getQueryParameter("v") != null -> uri.getQueryParameter("v")
            path.startsWith("shorts/") -> path.removePrefix("shorts/").substringBefore('/')
            path.startsWith("embed/") -> path.removePrefix("embed/").substringBefore('/')
            path.startsWith("live/") -> path.removePrefix("live/").substringBefore('/')
            else -> null
        }
        return id?.takeIf { it.isNotBlank() }?.let { "youtube:$it" } ?: url.trim().substringBefore('#')
    }

    private fun infoFileFor(url: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedCacheIdentity(url).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(infoCacheDir(), "$digest.json")
    }

    private fun legacyInfoFileFor(url: String) = File(infoCacheDir(), "${url.trim().hashCode()}.json")

    private companion object {
        const val TAG = "PiraChestYtDlp"
        const val INFO_CACHE_TTL_MS = 10 * 60 * 1000L
    }

    private fun validateUrl(value: String) {
        val uri = value.trim()
        require(uri.startsWith("https://") || uri.startsWith("http://")) { "Enter a valid video URL." }
        require(uri.contains("youtube.com/") || uri.contains("youtu.be/")) { "Only YouTube URLs are enabled in this first port." }
    }
}
