package com.pirachest.android.core.player

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import kotlin.math.roundToInt

@Stable
class VolumeBrightnessController(
    private val context: Context,
    private val windows: List<Window>
) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    private var volumeFraction = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
    private var brightnessFraction = readStartBrightness()
    private var brightnessChanged = false

    var label by mutableStateOf<String?>(null)
        private set
    
    var token by mutableStateOf(0)
        private set

    private fun readStartBrightness(): Float {
        val fromWindow = windows.firstNotNullOfOrNull { w ->
            w.attributes.screenBrightness.takeIf { it >= 0f }
        }
        if (fromWindow != null) return fromWindow
        return runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f).coerceIn(0.05f, 1f)
    }

    fun beginDrag() {
        volumeFraction = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
    }

    fun adjustVolume(delta: Float) {
        volumeFraction = (volumeFraction + delta).coerceIn(0f, 1f)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volumeFraction * maxVolume).roundToInt(), 0)
        label = "Volume ${(volumeFraction * 100).roundToInt()}%"
        token++
    }

    fun adjustBrightness(delta: Float) {
        brightnessFraction = (brightnessFraction + delta).coerceIn(0.02f, 1f)
        brightnessChanged = true
        windows.forEach { window ->
            val params = window.attributes
            params.screenBrightness = brightnessFraction
            window.attributes = params
        }
        label = "Brightness ${(brightnessFraction * 100).roundToInt()}%"
        token++
    }

    fun hideLabel() { label = null }

    fun restore() {
        if (!brightnessChanged) return
        windows.forEach { window ->
            val params = window.attributes
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = params
        }
    }
}

@Composable
fun rememberVolumeBrightnessController(): VolumeBrightnessController {
    val context = LocalContext.current
    val windows = rememberHostWindows()
    val controller = remember(context, windows) { VolumeBrightnessController(context, windows) }
    DisposableEffect(controller) { onDispose { controller.restore() } }
    return controller
}

fun Modifier.volumeBrightnessSwipes(controller: VolumeBrightnessController): Modifier =
    pointerInput(controller) {
        var brightnessSide = true
        detectVerticalDragGestures(
            onDragStart = { offset ->
                brightnessSide = offset.x < size.width / 2f
                controller.beginDrag()
            },
            onDragEnd = { },
            onDragCancel = { },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                val delta = -dragAmount / size.height.coerceAtLeast(1) * 1.3f
                if (brightnessSide) controller.adjustBrightness(delta) else controller.adjustVolume(delta)
            }
        )
    }

@Composable
fun BoxScopeIndicator(controller: VolumeBrightnessController, modifier: Modifier = Modifier) {
    val label = controller.label
    LaunchedEffect(controller.token) {
        delay(900)
        controller.hideLabel()
    }
    if (label != null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                label,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            )
        }
    }
}
