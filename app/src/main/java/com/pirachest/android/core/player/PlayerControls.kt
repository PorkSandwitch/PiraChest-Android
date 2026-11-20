package com.pirachest.android.core.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun PlayerControlsLayer(
    player: Player,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    keepVisible: Boolean = false,
    seekMs: Long = 10_000L
) {
    var playWhenReady by remember(player) { mutableStateOf(player.playWhenReady) }
    var state by remember(player) { mutableIntStateOf(player.playbackState) }
    var position by remember(player) { mutableLongStateOf(player.currentPosition) }
    var duration by remember(player) { mutableLongStateOf(player.duration.coerceAtLeast(0L)) }
    var dragFraction by remember(player) { mutableStateOf<Float?>(null) }
    var interaction by remember { mutableIntStateOf(0) }
    var flash by remember { mutableIntStateOf(0) }      
    var flashToken by remember { mutableIntStateOf(0) }
    val currentVisible by rememberUpdatedState(visible)
    
    val gestures = rememberVolumeBrightnessController()
    val inPip = rememberIsInPictureInPicture()
    LaunchedEffect(inPip) { if (inPip) onVisibleChange(false) }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayWhenReadyChanged(value: Boolean, reason: Int) { playWhenReady = value }
            override fun onPlaybackStateChanged(playbackState: Int) {
                state = playbackState
                duration = player.duration.coerceAtLeast(0L)
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    LaunchedEffect(player, visible) {
        while (visible) {
            if (dragFraction == null) position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            delay(250)
        }
    }

    LaunchedEffect(visible, playWhenReady, interaction, keepVisible, dragFraction != null) {
        if (visible && playWhenReady && !keepVisible && dragFraction == null) {
            delay(3500)
            onVisibleChange(false)
        }
    }
    LaunchedEffect(flashToken) {
        if (flash != 0) { delay(600); flash = 0 }
    }

    fun seekBy(delta: Long) {
        val limit = if (player.duration > 0) player.duration else Long.MAX_VALUE
        player.seekTo((player.currentPosition + delta).coerceIn(0L, limit))
    }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(player) {
                detectTapGestures(
                    onTap = { onVisibleChange(!currentVisible) },
                    onDoubleTap = { offset ->
                        if (offset.x < size.width / 2f) { seekBy(-seekMs); flash = -1 } else { seekBy(seekMs); flash = 1 }
                        flashToken++
                        interaction++
                    }
                )
            }
            .volumeBrightnessSwipes(gestures)
    ) {
        BoxScopeIndicator(gestures, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
        if (flash != 0) {
            Text(
                if (flash < 0) "« 10s" else "10s »",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(if (flash < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        if (state == Player.STATE_BUFFERING) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(44.dp), color = Color.White, strokeWidth = 3.dp)
        }

        AnimatedVisibility(visible && !inPip, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            if (state != Player.STATE_BUFFERING) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { seekBy(-seekMs); interaction++ }) {
                        Icon(Icons.Default.Replay10, "Back 10 seconds", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    FilledIconButton(
                        onClick = {
                            when {
                                state == Player.STATE_ENDED -> { player.seekTo(0L); player.play() }
                                playWhenReady -> player.pause()
                                else -> player.play()
                            }
                            interaction++
                        },
                        modifier = Modifier.size(64.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.55f), contentColor = Color.White)
                    ) {
                        Icon(
                            when {
                                state == Player.STATE_ENDED -> Icons.Default.Replay
                                playWhenReady -> Icons.Default.Pause
                                else -> Icons.Default.PlayArrow
                            },
                            contentDescription = "Play or pause",
                            modifier = Modifier.size(38.dp)
                        )
                    }
                    IconButton(onClick = { seekBy(seekMs); interaction++ }) {
                        Icon(Icons.Default.Forward10, "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }
            }
        }

        AnimatedVisibility(visible && !inPip, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                    .displayCutoutPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val shown = dragFraction?.let { (it * duration).toLong() } ?: position
                Text(formatTime(shown), color = Color.White, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = dragFraction ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { dragFraction = it; interaction++ },
                    onValueChangeFinished = {
                        dragFraction?.let { player.seekTo((it * duration).toLong()) }
                        position = player.currentPosition
                        dragFraction = null
                    },
                    enabled = duration > 0,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(formatTime(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}
