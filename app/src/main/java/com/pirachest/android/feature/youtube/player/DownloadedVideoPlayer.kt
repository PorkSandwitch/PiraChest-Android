package com.pirachest.android.feature.youtube.player

import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.pirachest.android.core.downloads.MediaPublisher
import com.pirachest.android.core.player.PlayerControlsLayer
import com.pirachest.android.core.player.createPlayerView
import com.pirachest.android.core.player.createRenderersFactory
import com.pirachest.android.core.player.enterPictureInPicture
import com.pirachest.android.core.player.findActivity
import com.pirachest.android.core.player.rememberHostWindows
import com.pirachest.android.core.player.setImmersive
import com.pirachest.android.core.player.registerScreenOffPause
import com.pirachest.android.core.preferences.AppPreferences
import java.io.File

private enum class OrientationMode { AUTO, LANDSCAPE, PORTRAIT }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun DownloadedVideoPlayer(filePath: String, modifier: Modifier = Modifier, title: String? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val windows = rememberHostWindows()
    val file = remember(filePath) { File(filePath) }
    
    val sourceExists = remember(filePath) { MediaPublisher.exists(context, filePath) }
    val mediaUri = remember(filePath) {
        if (filePath.startsWith("content://")) Uri.parse(filePath) else Uri.fromFile(file)
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var speedOpen by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var videoIsWide by remember { mutableStateOf<Boolean?>(null) }
    var orientationMode by remember { mutableStateOf(OrientationMode.AUTO) }
    var error by remember { mutableStateOf<String?>(null) }

    val player = remember(filePath) {
        ExoPlayer.Builder(context, createRenderersFactory(context)).build().apply {
            setMediaItem(MediaItem.fromUri(mediaUri))
            setSeekBackIncrementMs(10_000L)
            setSeekForwardIncrementMs(10_000L)
            val saved = AppPreferences.playbackPosition(context, filePath)
            if (saved > 5_000L) seekTo(saved)
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val unregisterScreenOff = registerScreenOffPause(context, player)
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) videoIsWide = size.width >= size.height
            }
            override fun onPlayerError(e: PlaybackException) {
                error = "Playback failed (${e.errorCodeName}). Try turning on Software video decoding in Settings."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            val finished = player.playbackState == Player.STATE_ENDED
            AppPreferences.setPlaybackPosition(context, filePath, if (finished) 0L else player.currentPosition)
            unregisterScreenOff()
            player.release()
        }
    }

    DisposableEffect(windows) {
        windows.forEach { setImmersive(it, true) }
        onDispose { windows.forEach { setImmersive(it, false) } }
    }
    
    DisposableEffect(activity) {
        val old = activity?.requestedOrientation
        onDispose { activity?.requestedOrientation = old ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    
    LaunchedEffect(orientationMode, videoIsWide) {
        activity?.requestedOrientation = when (orientationMode) {
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            OrientationMode.AUTO -> if (videoIsWide == true) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BackHandler { onBack() }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (!sourceExists) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("This downloaded file could not be found.", color = Color.White)
                TextButton(onClick = onBack) { Text("Back to Downloads") }
            }
            return@Box
        }

        AndroidView(
            factory = { ctx -> createPlayerView(ctx).also { it.player = player } },
            update = { if (it.player !== player) it.player = player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize()
        )

        PlayerControlsLayer(
            player = player,
            visible = controlsVisible,
            onVisibleChange = { controlsVisible = it },
            keepVisible = speedOpen || error != null
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Text(
                    title ?: file.nameWithoutExtension,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    TextButton(onClick = { speedOpen = true }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                        Text(if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x")
                    }
                    DropdownMenu(speedOpen, { speedOpen = false }) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { v ->
                            DropdownMenuItem(
                                text = { Text((if (v == speed) "✓ " else "") + "${v}x") },
                                onClick = { speed = v; player.setPlaybackSpeed(v); speedOpen = false }
                            )
                        }
                    }
                }
                IconButton(onClick = {
                    orientationMode = if (activity?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                        OrientationMode.PORTRAIT else OrientationMode.LANDSCAPE
                }) { Icon(Icons.Default.ScreenRotation, "Rotate", tint = Color.White) }
                IconButton(onClick = { enterPictureInPicture(activity) }) {
                    Icon(Icons.Default.PictureInPictureAlt, "Picture in picture", tint = Color.White)
                }
            }
        }

        error?.let {
            Text(
                it,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center).padding(32.dp)
            )
        }
    }
}
