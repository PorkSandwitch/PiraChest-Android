package com.pirachest.android.core.media

import android.content.Context
import android.util.Log
import com.pirachest.android.core.preferences.AppPreferences
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object MediaToolchainUpdater {
    private const val TAG = "PiraChestMediaTools"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow("Preparing media tools…")
    val status = _status.asStateFlow()

    private const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    @Volatile private var initialized = false
    @Volatile private var ytDlpUsable = false

    fun initialize(context: Context, force: Boolean = false) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext

        scope.launch {
            try {
                _status.value = "Initializing yt-dlp and FFmpeg…"
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().init(appContext)
                    FFmpeg.getInstance().init(appContext)
                }
                ytDlpUsable = true

                _status.value = "yt-dlp ready: ${YoutubeDL.getInstance().versionName(appContext)}"

                val lastCheck = AppPreferences.ytDlpLastCheck(appContext)
                val now = System.currentTimeMillis()
                if (!force && lastCheck > 0L && now - lastCheck in 0 until UPDATE_CHECK_INTERVAL_MS) {
                    val installed = YoutubeDL.getInstance().versionName(appContext)
                    _status.value = "yt-dlp ready: $installed"
                    Log.i(TAG, _status.value)
                    return@launch
                }

                _status.value = "Checking for yt-dlp updates…"
                val result = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().updateYoutubeDL(
                        appContext,
                        YoutubeDL.UpdateChannel._STABLE
                    )
                }

                val version = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().versionName(appContext)
                }
                _status.value = when (result) {
                    YoutubeDL.UpdateStatus.DONE -> "yt-dlp updated: $version"
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp is up to date: $version"
                    else -> "yt-dlp update status: $result (using installed version $version)"
                }
                if (result == YoutubeDL.UpdateStatus.DONE || result == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) {
                    AppPreferences.setYtDlpLastCheck(appContext, System.currentTimeMillis())
                }
                Log.i(TAG, _status.value)
            } catch (error: Throwable) {

                Log.e(TAG, "Media tool initialization/update failed", error)
                _status.value = "Update check failed; using installed yt-dlp if available"
                if (!ytDlpUsable) {
                    _status.value = "Media tools failed to start: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
    }

    suspend fun awaitReady() {

        if (ytDlpUsable) return

        val ready = withTimeoutOrNull(30_000L) {
            while (!ytDlpUsable) {
                val current = status.value
                if (current.startsWith("Media tools failed")) error(current)
                kotlinx.coroutines.delay(100L)
            }
            true
        } ?: false

        if (!ready) {
            val current = status.value
            if (current.startsWith("Media tools failed")) error(current)
            error("Media tools are still initializing. Please wait a few seconds and try again.")
        }
    }

    fun checkAgain(context: Context) {
        initialized = false
        _status.value = "Preparing media tools…"
        initialize(context, force = true)
    }
}
