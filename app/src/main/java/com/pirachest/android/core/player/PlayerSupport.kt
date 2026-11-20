package com.pirachest.android.core.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.pirachest.android.core.preferences.AppPreferences

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private val SoftwareFirstVideoCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
    val defaultCodecs = MediaCodecSelector.DEFAULT.getDecoderInfos(
        mimeType,
        requiresSecureDecoder,
        requiresTunnelingDecoder
    )
    if (mimeType.startsWith("video/", ignoreCase = true)) {
        val (software, hardware) = defaultCodecs.partition { info: MediaCodecInfo ->
            info.softwareOnly ||
                info.name.startsWith("c2.android.", ignoreCase = true) ||
                info.name.startsWith("OMX.google.", ignoreCase = true)
        }
        software + hardware
    } else {
        defaultCodecs
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun createRenderersFactory(context: Context): DefaultRenderersFactory {
    val factory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
    if (AppPreferences.softwareDecoding(context)) {
        factory.setMediaCodecSelector(SoftwareFirstVideoCodecSelector)
    }
    return factory
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun createStreamingLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(20_000, 60_000, 1_500, 3_000)
    .build()

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun rememberHostWindows(): List<Window> {
    val view = LocalView.current
    return remember(view) {
        listOfNotNull(view.context.findActivity()?.window, (view.parent as? DialogWindowProvider)?.window)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun createPlayerView(context: Context): PlayerView = PlayerView(context).apply {
    useController = false 
    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    keepScreenOn = true
}

fun setImmersive(window: Window?, immersive: Boolean) {
    if (window == null) return
    if (immersive) window.setBackgroundDrawableResource(android.R.color.black)
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (immersive)
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
    }
    val types = WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        window.insetsController?.let {
            if (immersive) {
                it.hide(types)
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                it.show(types)
            }
        }
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (immersive) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
    }
}
