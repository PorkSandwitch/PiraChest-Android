package com.pirachest.android.feature.anime.data

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import com.pirachest.android.core.downloads.MediaPublisher
import com.pirachest.android.feature.youtube.data.DownloadProgress
import com.pirachest.android.feature.youtube.data.DownloadResult
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class AnimeDownloader(private val context: Context) {

    suspend fun download(
        stream: VideoStreamSource,
        displayName: String,
        
        maxHeight: Int? = null,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")
        val processId = UUID.randomUUID().toString()
        val taskDir = File(File(baseDir, "PiraChest"), processId).apply { mkdirs() }
        val safeName = sanitize(displayName)

        val request = YoutubeDLRequest(stream.url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--newline")
            addOption("--progress")
            addOption("--progress-delta", "1")
            addOption("--concurrent-fragments", "4")
            addOption("--retries", "5")
            addOption("--fragment-retries", "10")
            addOption("--remux-video", "mkv")
            
            addOption("--all-subs")
            addOption("--embed-subs")
            if (maxHeight != null) addOption("-S", "res:$maxHeight")
            stream.headers.forEach { (key, value) ->
                if (key.equals("User-Agent", ignoreCase = true)) addOption("--user-agent", value)
                else addOption("--add-header", "$key:$value")
            }
            addOption("-o", File(taskDir, "$safeName.%(ext)s").absolutePath)
            addOption("--print", "after_move:filepath")
        }

        var lastPercent = 0f
        var lastEmitAt = 0L
        val response = YoutubeDL.getInstance().execute(request, processId) { callbackPercent, eta, line ->
            val linePercent = PercentRegex.find(line.orEmpty())?.groupValues?.getOrNull(1)?.toFloatOrNull()
            val percent = (linePercent ?: callbackPercent).coerceIn(0f, 99f)
            val now = SystemClock.elapsedRealtime()
            if (percent > lastPercent && (percent - lastPercent >= 0.5f || now - lastEmitAt >= 250L)) {
                lastPercent = percent
                lastEmitAt = now
                onProgress(DownloadProgress(percent, eta))
            }
        }

        val printed = response.out.trim().lineSequence().lastOrNull { it.isNotBlank() }
        val file = printed?.let(::File)?.takeIf { it.exists() }
            ?: taskDir.walkTopDown()
                .filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                .maxByOrNull { it.length() }
            ?: error("Download finished but the video file was not found.")
        val title = file.nameWithoutExtension

        val subtitleFiles = downloadSubtitles(stream, taskDir, safeName)
        val published = MediaPublisher.publish(context, file)
        subtitleFiles.forEach { subtitle -> runCatching { MediaPublisher.publish(context, subtitle) } }
        runCatching { taskDir.deleteRecursively() }
        DownloadResult(file, title, published.location, published.uri)
    }

    private fun downloadSubtitles(stream: VideoStreamSource, taskDir: File, baseName: String): List<File> {
        val saved = mutableListOf<File>()
        stream.subtitles
            .distinctBy { it.url }
            .filterNot { it.label.contains("thumb", ignoreCase = true) || it.url.contains("thumbnail", ignoreCase = true) }
            
            .sortedByDescending { it.language.startsWith("en", ignoreCase = true) || it.label.contains("english", ignoreCase = true) }
            .take(4)
            .forEach { track ->
                runCatching {
                    val ext = subtitleExtension(track)
                    val lang = sanitize(track.label.ifBlank { track.language }).take(30).ifBlank { "subtitle" }
                    val target = File(taskDir, "$baseName.$lang.$ext")
                    val connection = (URL(track.url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8_000
                        readTimeout = 10_000
                        stream.headers.forEach { (key, value) -> setRequestProperty(key, value) }
                    }
                    try {
                        connection.inputStream.use { input -> target.outputStream().use { out -> input.copyTo(out) } }
                    } finally {
                        connection.disconnect()
                    }
                    if (target.length() > 0L) { saved.add(target) } else { target.delete() }
                }
            }
        return saved
    }

    private fun subtitleExtension(track: SubtitleTrack): String {
        val fromUrl = track.url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when {
            fromUrl in setOf("vtt", "srt", "ass", "ssa") -> fromUrl
            track.mimeType?.contains("subrip", ignoreCase = true) == true -> "srt"
            else -> "vtt"
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|%\p{Cntrl}]"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(120)
            .ifBlank { "Anime episode" }

    private companion object {
        val PercentRegex = Regex("""(?<!\d)(\d{1,3}(?:\.\d+)?)%""")
    }
}
