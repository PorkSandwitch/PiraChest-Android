package com.pirachest.android.feature.anime.data

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

enum class AudioChoice(val id: String, val label: String) {
    SUB("sub", "Sub"),
    DUB("dub", "Dub");

    companion object {
        fun fromId(id: String?): AudioChoice = entries.firstOrNull { it.id == id } ?: SUB
    }
}

enum class QualityChoice(val id: String, val label: String, val maxHeight: Int?) {
    BEST("best", "Best", null),
    Q1080("1080", "1080p", 1080),
    Q720("720", "720p", 720),
    Q480("480", "480p", 480),
    Q360("360", "360p", 360),
    FASTEST("fastest", "Fastest", null);

    companion object {
        fun fromId(id: String?): QualityChoice = entries.firstOrNull { it.id == id } ?: BEST
    }
}

data class StreamPick(
    val stream: VideoStreamSource,
    
    val note: String?
)

object StreamPicker {
    private val dubRegex = Regex("""(^|[/\s(\-_])dub(bed)?($|[/\s)\-_])""", RegexOption.IGNORE_CASE)
    private val heightRegex = Regex("""(\d{3,4})\s*p?""")

    fun audioOf(stream: VideoStreamSource): AudioChoice =
        if (dubRegex.containsMatchIn(stream.providerName) || dubRegex.containsMatchIn(stream.quality)) AudioChoice.DUB
        else AudioChoice.SUB

    fun isBackup(stream: VideoStreamSource): Boolean =
        stream.quality.contains("backup", true) || stream.providerName.contains("backup", true)

    fun heightOf(stream: VideoStreamSource): Int? =
        heightRegex.find(stream.quality)?.groupValues?.get(1)?.toIntOrNull()

    fun availableAudio(streams: List<VideoStreamSource>): Set<AudioChoice> = streams.map(::audioOf).toSet()

    private fun score(stream: VideoStreamSource, wanted: QualityChoice): Int {
        val height = heightOf(stream)
        var score = when {
            wanted.maxHeight == null -> height ?: 900
            height == null -> 500                       
            height <= wanted.maxHeight -> 10_000 + height   
            else -> 5_000 - height                      
        }
        if (isBackup(stream)) score -= 100_000
        return score
    }

    fun ordered(
        streams: List<VideoStreamSource>,
        audio: AudioChoice,
        quality: QualityChoice
    ): List<VideoStreamSource> {
        val sameAudio = streams.filter { audioOf(it) == audio }
        val pool = sameAudio.ifEmpty { streams }
        return pool.sortedByDescending { score(it, quality) }
    }

    suspend fun fastestFirst(
        streams: List<VideoStreamSource>,
        audio: AudioChoice
    ): List<VideoStreamSource> = withContext(Dispatchers.IO) {
        val sameAudio = streams.filter { audioOf(it) == audio }
        val pool = (sameAudio.ifEmpty { streams }).distinctBy { it.url }
        val measured = coroutineScope {
            pool.map { stream -> async {
                val score = runCatching {
                    val connection = (URL(stream.url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4_000
                        readTimeout = 4_000
                        requestMethod = "GET"
                        setRequestProperty("Range", "bytes=0-65535")
                        stream.headers.forEach { (key, value) -> setRequestProperty(key, value) }
                    }
                    val started = System.nanoTime()
                    try {
                        val input = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
                        val bytes = input?.use { source ->
                            val buffer = ByteArray(8192)
                            var total = 0
                            while (total < 65_536) {
                                val count = source.read(buffer, 0, minOf(buffer.size, 65_536 - total))
                                if (count <= 0) break
                                total += count
                            }
                            total
                        } ?: 0
                        val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
                        if (bytes == 0) -1.0 else bytes.toDouble() / elapsedMs
                    } finally { connection.disconnect() }
                }.getOrDefault(-1.0)
                stream to score
            } }.map { it.await() }
        }
        measured.sortedByDescending { it.second }.map { it.first }
    }

    fun choose(
        streams: List<VideoStreamSource>,
        audio: AudioChoice,
        quality: QualityChoice
    ): StreamPick? {
        if (streams.isEmpty()) return null
        val ordered = ordered(streams, audio, quality)
        val best = ordered.first()
        val notes = mutableListOf<String>()
        if (audioOf(best) != audio) {
            notes += "${audio.label} is not available for this episode, using ${audioOf(best).label}."
        }
        val height = heightOf(best)
        if (quality.maxHeight != null && height != null && height != quality.maxHeight) {
            notes += "${quality.label} is not available, using ${height}p."
        }
        return StreamPick(best, notes.joinToString(" ").ifBlank { null })
    }
}
