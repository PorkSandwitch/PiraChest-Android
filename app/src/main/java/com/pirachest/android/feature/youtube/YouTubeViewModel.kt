package com.pirachest.android.feature.youtube

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pirachest.android.core.downloads.DownloadManagerStore
import com.pirachest.android.core.downloads.DownloadService
import com.pirachest.android.core.media.MediaToolchainUpdater
import com.pirachest.android.feature.youtube.data.YouTubeRepository
import com.pirachest.android.feature.youtube.data.YouTubeVideo
import com.pirachest.android.feature.youtube.data.VideoQuality
import com.pirachest.android.feature.youtube.data.VideoCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

data class YouTubeUiState(
    val url: String = "",
    val video: YouTubeVideo? = null,
    val selectedQuality: VideoQuality? = null,
    val selectedCodec: VideoCodec = VideoCodec.H264,
    val loading: Boolean = false,
    val activeDownloads: Int = 0,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val etaSeconds: Long = 0L,
    val message: String? = null,
    val downloadedPath: String? = null,
    val audioOnly: Boolean = false
)

class YouTubeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = YouTubeRepository(application)
    private val _state = MutableStateFlow(YouTubeUiState())
    val state = _state.asStateFlow()

    fun setUrl(value: String) {
        _state.value = _state.value.copy(
            url = value, message = null, video = null, selectedQuality = null, downloadedPath = null
        )
    }

    fun setAudioOnly(value: Boolean) {
        _state.value = _state.value.copy(audioOnly = value)
    }

    fun setQuality(quality: VideoQuality) {
        _state.value = _state.value.copy(selectedQuality = quality)
    }

    fun setCodec(codec: VideoCodec) {
        val video = _state.value.video ?: return
        val nextQuality = video.qualities
            .filter { it.codec == codec }
            .maxByOrNull { it.height ?: 0 }
        _state.value = _state.value.copy(
            selectedCodec = codec,
            selectedQuality = nextQuality,
            message = if (nextQuality == null) "${codec.label} is not available for this video." else null
        )
    }

    fun fetch() {
        val url = _state.value.url.trim()
        if (_state.value.loading) return
        if (url.isBlank()) {
            _state.value = _state.value.copy(message = "Paste a YouTube video link first.")
            return
        }

        _state.value = _state.value.copy(
            url = url,
            loading = true,
            message = null,
            video = repository.quickPreview(url),
            selectedQuality = null,
            downloadedPath = null,
            progress = 0f,
            etaSeconds = 0L
        )
        viewModelScope.launch {
            
            launch {
                runCatching { repository.fetchQuickDetails(url) }.getOrNull()?.let { preview ->
                    if (_state.value.url == url && _state.value.loading) {
                        _state.update { current ->
                            if (current.url == url && current.loading) current.copy(
                                video = current.video?.let { existing -> existing.copy(
                                    title = preview.title,
                                    uploader = preview.uploader,
                                    thumbnail = preview.thumbnail ?: existing.thumbnail
                                ) }
                            ) else current
                        }
                    }
                }
            }
            val result = try {
                MediaToolchainUpdater.awaitReady()
                Result.success(repository.inspect(url))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            result.onSuccess { video ->
                _state.value = _state.value.copy(
                    video = video,
                    selectedCodec = video.qualities.firstOrNull()?.codec ?: VideoCodec.H264,
                    selectedQuality = video.qualities.firstOrNull(),
                    loading = false
                )
            }.onFailure { error ->
                val detail = error.message
                    ?.lineSequence()
                    ?.map(String::trim)
                    ?.filter(String::isNotBlank)
                    ?.firstOrNull()
                    ?.take(240)
                _state.value = _state.value.copy(
                    loading = false,
                    
                    selectedQuality = null,
                    message = if (detail.isNullOrBlank()) {
                        "Could not inspect video. Check the link and internet connection, then try again."
                    } else {
                        "Could not inspect video: $detail"
                    }
                )
            }
        }
    }

    fun download() {
        val current = _state.value
        val video = current.video ?: return
        val quality = current.selectedQuality ?: video.qualities.firstOrNull() ?: run {
            _state.value = current.copy(message = "No compatible video formats were found.")
            return
        }
        val format = if (current.audioOnly) "MP3 audio" else "${quality.codec.label} ${quality.label}"
        val taskId = DownloadManagerStore.add(video.title, current.url, format, video.thumbnail)
        DownloadManagerStore.started(taskId)
        DownloadService.ensureRunning(getApplication<Application>())
        _state.update {
            it.copy(
                activeDownloads = it.activeDownloads + 1,
                downloading = true,
                progress = 0f,
                message = null,
                downloadedPath = null
            )
        }

        viewModelScope.launch {
            runCatching {
                MediaToolchainUpdater.awaitReady()
                repository.download(current.url, current.audioOnly, quality) { progress ->
                    val percent = progress.percent.coerceIn(0f, 100f)
                    _state.update { it.copy(progress = percent, etaSeconds = progress.etaSeconds) }
                    DownloadManagerStore.progress(taskId, percent, progress.etaSeconds)
                }
            }.onSuccess { result ->
                DownloadManagerStore.completed(taskId, result.playUri, result.savedLocation)
                _state.update {
                    val remaining = (it.activeDownloads - 1).coerceAtLeast(0)
                    it.copy(
                        activeDownloads = remaining,
                        downloading = remaining > 0,
                        progress = 100f,
                        downloadedPath = result.playUri,
                        message = "Saved to ${result.savedLocation}"
                    )
                }
            }.onFailure { error ->
                val reason = error.message ?: "Download failed"
                DownloadManagerStore.failed(taskId, reason)
                _state.update {
                    val remaining = (it.activeDownloads - 1).coerceAtLeast(0)
                    it.copy(activeDownloads = remaining, downloading = remaining > 0, message = reason)
                }
            }
        }
    }
}
