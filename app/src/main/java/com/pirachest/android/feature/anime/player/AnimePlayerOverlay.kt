package com.pirachest.android.feature.anime.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.pirachest.android.core.player.PlayerControlsLayer
import com.pirachest.android.core.player.createPlayerView
import com.pirachest.android.core.player.createStreamingLoadControl
import com.pirachest.android.core.player.enterPictureInPicture
import com.pirachest.android.core.player.findActivity
import com.pirachest.android.core.player.rememberHostWindows
import com.pirachest.android.core.player.setImmersive
import com.pirachest.android.core.player.registerScreenOffPause
import com.pirachest.android.core.player.createRenderersFactory
import com.pirachest.android.feature.anime.ProviderOption
import com.pirachest.android.feature.anime.data.VideoStreamSource
import java.util.Locale

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun AnimePlayerOverlay(
    title: String,
    stream: VideoStreamSource,
    availableStreams: List<VideoStreamSource>,
    providers: List<ProviderOption> = emptyList(),
    selectedProviderId: String = "auto",
    resolvingEpisodeNumber: Int? = null,
    onSelectProvider: (String) -> Unit = {},
    onSelectStream: (VideoStreamSource) -> Unit,
    onNextEpisode: (() -> Unit)? = null,
    
    onEnded: () -> Unit = {},
    
    preferredMaxHeight: Int? = 720,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val windows = rememberHostWindows()
    val nextEpisodeCallback by rememberUpdatedState(onNextEpisode)
    val endedCallback by rememberUpdatedState(onEnded)
    var serverOpen by remember { mutableStateOf(false) }
    var qualityOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var subtitleOpen by remember { mutableStateOf(false) }
    var speed by remember(stream.url) { mutableFloatStateOf(1f) }
    var externalSubtitle by remember(stream.url) { mutableStateOf<Uri?>(null) }
    var externalSubtitleName by remember(stream.url) { mutableStateOf<String?>(null) }
    var trackRevision by remember(stream.url) { mutableIntStateOf(0) }
    var controlsVisible by remember(stream.url) { mutableStateOf(true) }
    var playerError by remember(stream.url) { mutableStateOf<String?>(null) }
    var selectedQualityMode by remember(stream.url) { mutableStateOf(if (preferredMaxHeight == null) "auto" else "${preferredMaxHeight}p") }

    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            externalSubtitle = uri
            externalSubtitleName = uri.lastPathSegment?.substringAfterLast('/') ?: "External subtitle"
        }
    }

    DisposableEffect(activity, windows) {
        val oldOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        windows.forEach { setImmersive(it, true) }
        onDispose {
            windows.forEach { setImmersive(it, false) }
            activity?.requestedOrientation = oldOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val selector = remember(stream.url) {
        DefaultTrackSelector(context).apply {
            val builder = buildUponParameters()
                .setTunnelingEnabled(false)
                .setPreferredTextLanguage("en")
            
            if (preferredMaxHeight != null) builder.setMaxVideoSize(preferredMaxHeight * 16 / 9, preferredMaxHeight)
            parameters = builder.build()
        }
    }
    val playerKey = "${stream.url}|${externalSubtitle?.toString().orEmpty()}"
    val player = remember(playerKey) {
        val userAgent = stream.headers.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
            ?: "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
        val requestHeaders = stream.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }

        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(requestHeaders)

        val validSubs = stream.subtitles
            .distinctBy { it.url }
            .filterNot {
                it.label.contains("thumb", ignoreCase = true) ||
                    it.label.contains("sprite", ignoreCase = true) ||
                    it.url.contains("thumbnails", ignoreCase = true)
            }

        val hasExplicitDefault = validSubs.any { it.isDefault }
        val subtitleConfigs = buildList {
            validSubs.forEachIndexed { idx, s ->
                val mime = s.mimeType ?: mimeFor(s.url, s.label) ?: MimeTypes.TEXT_VTT
                val cleanLabel = formatSubtitleLabel(
                    rawLabel = s.label,
                    rawLang = s.language,
                    fallbackIndex = idx + 1,
                    providerName = stream.providerName
                )
                val selectByDefault = s.isDefault ||
                    (!hasExplicitDefault && (cleanLabel.contains("English", ignoreCase = true) || idx == 0))
                add(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                        .setMimeType(mime)
                        .setLanguage(s.language.takeIf { it.isNotBlank() && !it.equals("und", true) } ?: "en")
                        .setLabel(cleanLabel)
                        .setSelectionFlags(if (selectByDefault) C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                )
            }
            externalSubtitle?.let { uri ->
                val mime = mimeFor(uri.toString(), externalSubtitleName.orEmpty()) ?: MimeTypes.TEXT_VTT
                add(
                    MediaItem.SubtitleConfiguration.Builder(uri)
                        .setMimeType(mime)
                        .setLanguage("en")
                        .setLabel(externalSubtitleName ?: "External subtitle")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            }
        }
        val item = MediaItem.Builder().setUri(stream.url).apply {
            if (stream.isHls || stream.url.contains(".m3u8", ignoreCase = true)) {
                setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            if (subtitleConfigs.isNotEmpty()) setSubtitleConfigurations(subtitleConfigs)
        }.build()

        ExoPlayer.Builder(context, createRenderersFactory(context))
            .setLoadControl(createStreamingLoadControl())
            .setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .build().apply {
                setMediaItem(item)
                setSeekBackIncrementMs(10_000L); setSeekForwardIncrementMs(10_000L)
                prepare(); playWhenReady = true
            }
    }

    DisposableEffect(player, availableStreams) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                trackRevision++
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    playerError = null
                }
                
                if (playbackState == Player.STATE_ENDED) {
                    endedCallback()
                    nextEpisodeCallback?.invoke()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val currentIdx = availableStreams.indexOfFirst { it.url == stream.url }
                val nextStream = if (currentIdx >= 0) availableStreams.getOrNull(currentIdx + 1) else null
                if (nextStream != null) {
                    onSelectStream(nextStream)
                } else {
                    controlsVisible = true
                    playerError = "Playback failed (${error.errorCodeName}). Switch Server above."
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    DisposableEffect(player) {
        val unregisterScreenOff = registerScreenOffPause(context, player)
        onDispose { unregisterScreenOff(); player.release() }
    }
    BackHandler { onClose() }

    val revision = trackRevision
    val videoGroups = remember(player, revision) {
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
    }
    val subtitleGroups = remember(player, revision) {
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    }

    val serverEntries = remember(availableStreams, stream.url) {
        availableStreams
            .groupBy { it.providerName }
            .map { (serverName, variants) ->
                val isCurrentServer = serverName == stream.providerName
                val targetStream = if (isCurrentServer) {
                    stream
                } else {
                    variants.firstOrNull {
                        it.quality.contains("720", ignoreCase = true) &&
                            !it.quality.contains("backup", ignoreCase = true)
                    } ?: variants.firstOrNull {
                        it.quality.contains("720", ignoreCase = true)
                    } ?: variants.first()
                }
                serverName to targetStream
            }
    }

    val qualityStreamOptions = remember(availableStreams, stream.url, stream.providerName) {
        val activeBaseServer = stream.providerName.substringBefore(" backup").trim()
        val sameServerFamily = availableStreams.filter {
            it.providerName.substringBefore(" backup").trim().equals(activeBaseServer, ignoreCase = true)
        }
        if (sameServerFamily.size > 1) sameServerFamily else emptyList()
    }

    val subtitleMenuItems = remember(subtitleGroups, revision) {
        val seenLabels = mutableMapOf<String, Int>()
        buildList {
            var counter = 1
            subtitleGroups.forEach { g ->
                for (i in 0 until g.length) {
                    if (!g.isTrackSupported(i)) continue
                    val f = g.getTrackFormat(i)
                    val baseName = cleanTrackFormatLabel(f, counter++)
                    val count = (seenLabels[baseName] ?: 0) + 1
                    seenLabels[baseName] = count
                    val displayName = if (count > 1) "$baseName ($count)" else baseName
                    add(
                        SubtitleMenuEntry(
                            displayName = displayName,
                            isSelected = g.isTrackSelected(i),
                            group = g.mediaTrackGroup,
                            trackIndex = i
                        )
                    )
                }
            }
        }
    }

    val activeVideoHeight = remember(videoGroups, revision) {
        videoGroups.firstNotNullOfOrNull { g ->
            (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { idx ->
                g.getTrackFormat(idx).height.takeIf { it > 0 }
            }
        }
    }
    val displayQualityLabel = if (stream.quality.equals("auto", ignoreCase = true) && activeVideoHeight != null) {
        "${activeVideoHeight}p"
    } else {
        stream.quality
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                createPlayerView(ctx).apply {
                    this.player = player
                    subtitleView?.setApplyEmbeddedStyles(false)
                    subtitleView?.setApplyEmbeddedFontSizes(false)
                    subtitleView?.setStyle(CaptionStyleCompat(android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null))
                    subtitleView?.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
                }
            },
            
            update = { view -> if (view.player !== player) view.player = player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize()
        )

        PlayerControlsLayer(
            player = player,
            visible = controlsVisible,
            onVisibleChange = { controlsVisible = it },
            keepVisible = serverOpen || qualityOpen || speedOpen || subtitleOpen || playerError != null
        )

        if (resolvingEpisodeNumber != null) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xE8202020),
                    tonalElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
                        Column {
                            Text("Loading episode $resolvingEpisodeNumber", color = Color.White, style = MaterialTheme.typography.titleSmall)
                            Text("Finding a playable stream…", color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else if (playerError != null) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xE8202020),
                    tonalElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(playerError!!, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        val fallbackStream = availableStreams.firstOrNull { it.url != stream.url }
                        if (fallbackStream != null) {
                            Button(onClick = {
                                playerError = null
                                onSelectStream(fallbackStream)
                            }) {
                                Text("Try ${fallbackStream.providerName}")
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(.82f), Color.Transparent)))
                    .displayCutoutPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close player", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${stream.providerName}  •  $displayQualityLabel", color = Color.White.copy(.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                MenuButton("Server", serverOpen, { serverOpen = it }) {
                    serverEntries.forEach { (serverName, targetStream) ->
                        DropdownMenuItem(
                            text = { Text((if (serverName == stream.providerName) "✓ " else "") + serverName) },
                            onClick = { serverOpen = false; playerError = null; onSelectStream(targetStream) }
                        )
                    }
                    if (providers.isNotEmpty()) HorizontalDivider()
                    providers.forEach { p ->
                        DropdownMenuItem(
                            text = { Text((if (p.id == selectedProviderId) "✓ " else "") + p.name) },
                            onClick = { serverOpen = false; playerError = null; onSelectProvider(p.id) }
                        )
                    }
                }
                MenuButton("CC", subtitleOpen, { subtitleOpen = it }, icon = true) {
                    val isSubDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) ||
                        subtitleMenuItems.none { it.isSelected }
                    DropdownMenuItem(
                        text = { Text((if (isSubDisabled) "✓ " else "") + "Subtitles off") },
                        onClick = {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                            subtitleOpen = false
                        }
                    )
                    subtitleMenuItems.forEach { entry ->
                        DropdownMenuItem(
                            text = { Text((if (entry.isSelected && !isSubDisabled) "✓ " else "") + entry.displayName) },
                            onClick = {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                    .addOverride(TrackSelectionOverride(entry.group, listOf(entry.trackIndex)))
                                    .build()
                                subtitleOpen = false
                            }
                        )
                    }
                    DropdownMenuItem(text = { Text("＋ Add subtitle file…") }, onClick = {
                        subtitleOpen = false
                        subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream"))
                    })
                    if (externalSubtitle != null) DropdownMenuItem(text = { Text("Remove external subtitle") }, onClick = {
                        externalSubtitle = null; externalSubtitleName = null; subtitleOpen = false
                    })
                    if (subtitleMenuItems.isEmpty() && stream.subtitles.isEmpty() && externalSubtitle == null) {
                        DropdownMenuItem(text = { Text("No subtitles found") }, enabled = false, onClick = {})
                    }
                }
                MenuButton("${speed}×", speedOpen, { speedOpen = it }) {
                    listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { v ->
                        DropdownMenuItem(text = { Text("${v}×") }, onClick = { speed = v; player.setPlaybackSpeed(v); speedOpen = false })
                    }
                }
                MenuButton("Quality", qualityOpen, { qualityOpen = it }) {
                    val hasMultiHlsTracks = videoGroups.sumOf { it.length } > 1

                    if (qualityStreamOptions.isNotEmpty()) {
                        
                        qualityStreamOptions.forEach { qStream ->
                            val isActive = qStream.url == stream.url
                            val sameName = qStream.providerName.equals(stream.providerName, ignoreCase = true)
                            val label = if (sameName) {
                                qStream.quality
                            } else {
                                "${qStream.quality} · ${qStream.providerName}"
                            }
                            DropdownMenuItem(
                                text = { Text((if (isActive) "✓ " else "") + label) },
                                onClick = {
                                    qualityOpen = false
                                    playerError = null
                                    onSelectStream(qStream)
                                }
                            )
                        }
                    } else if (hasMultiHlsTracks) {
                        
                        DropdownMenuItem(
                            text = { Text((if (selectedQualityMode == "auto") "✓ " else "") + "Auto") },
                            onClick = {
                                selectedQualityMode = "auto"
                                selector.parameters = selector.parameters.buildUpon()
                                    .clearVideoSizeConstraints()
                                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                    .build()
                                qualityOpen = false
                            }
                        )
                        videoGroups.forEach { g ->
                            
                            val trackIndices = (0 until g.length)
                                .filter { g.isTrackSupported(it) }
                                .sortedByDescending { g.getTrackFormat(it).height }

                            for (i in trackIndices) {
                                val f = g.getTrackFormat(i)
                                val qLabel = if (f.height > 0) "${f.height}p" else "Video ${i + 1}"
                                val isChecked = (selectedQualityMode == qLabel) ||
                                    (preferredMaxHeight != null && selectedQualityMode == "${preferredMaxHeight}p" && f.height == preferredMaxHeight)
                                DropdownMenuItem(
                                    text = { Text((if (isChecked) "✓ " else "") + qLabel) },
                                    onClick = {
                                        selectedQualityMode = qLabel
                                        selector.parameters = selector.parameters.buildUpon()
                                            .clearVideoSizeConstraints()
                                            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                            .addOverride(TrackSelectionOverride(g.mediaTrackGroup, listOf(i)))
                                            .build()
                                        qualityOpen = false
                                    }
                                )
                            }
                        }
                    } else {
                        DropdownMenuItem(
                            text = { Text(if (videoGroups.isEmpty()) "Loading…" else "✓ $displayQualityLabel") },
                            enabled = false,
                            onClick = {}
                        )
                    }
                }
                if (onNextEpisode != null) IconButton(onClick = onNextEpisode) { Icon(Icons.Default.SkipNext, "Next episode", tint = Color.White) }
                IconButton(onClick = { enterPictureInPicture(activity) }) {
                    Icon(Icons.Default.PictureInPictureAlt, "Picture in picture", tint = Color.White)
                }
            }
        }

        if (controlsVisible) externalSubtitleName?.let {
            Surface(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 76.dp), color = Color.Black.copy(.55f), shape = CircleShape) {
                Text("字幕  $it", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

private data class SubtitleMenuEntry(
    val displayName: String,
    val isSelected: Boolean,
    val group: androidx.media3.common.TrackGroup,
    val trackIndex: Int
)

private fun formatSubtitleLabel(
    rawLabel: String,
    rawLang: String,
    fallbackIndex: Int,
    providerName: String
): String {
    val cleanedLabel = rawLabel.trim()
    val cleanedLang = rawLang.trim()
    val junkValues = setOf("", "und", "unknown", "null", "subtitle", "subtitles", "caption", "captions")

    if (cleanedLabel.lowercase() !in junkValues) {
        if (cleanedLabel.length in 2..3) {
            val langName = Locale.forLanguageTag(cleanedLabel).getDisplayLanguage(Locale.ENGLISH)
            if (langName.isNotBlank() && !langName.equals(cleanedLabel, true) && !langName.equals("und", true)) {
                return langName.replaceFirstChar { it.uppercase() }
            }
        }
        return cleanedLabel.replaceFirstChar { it.uppercase() }
    }

    if (cleanedLang.lowercase() !in junkValues) {
        val langName = Locale.forLanguageTag(cleanedLang).getDisplayLanguage(Locale.ENGLISH)
        if (langName.isNotBlank() && !langName.equals("und", true)) {
            return langName.replaceFirstChar { it.uppercase() }
        }
    }

    return if (providerName.contains("SUB", ignoreCase = true) && fallbackIndex == 1) {
        "English"
    } else {
        "Track $fallbackIndex"
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun cleanTrackFormatLabel(format: Format, fallbackIndex: Int): String {
    val label = format.label?.trim().orEmpty()
    val lang = format.language?.trim().orEmpty()
    val junkValues = setOf("", "und", "unknown", "null", "subtitle", "subtitles")

    if (label.lowercase() !in junkValues) {
        if (label.length in 2..3) {
            val expanded = Locale.forLanguageTag(label).getDisplayLanguage(Locale.ENGLISH)
            if (expanded.isNotBlank() && !expanded.equals("und", true)) {
                return expanded.replaceFirstChar { it.uppercase() }
            }
        }
        return label.replaceFirstChar { it.uppercase() }
    }

    if (lang.lowercase() !in junkValues) {
        val expanded = Locale.forLanguageTag(lang).getDisplayLanguage(Locale.ENGLISH)
        if (expanded.isNotBlank() && !expanded.equals("und", true)) {
            return expanded.replaceFirstChar { it.uppercase() }
        }
    }

    return "Track $fallbackIndex"
}

@Composable
private fun MenuButton(
    label: String,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    icon: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Box {
        TextButton(onClick = { onExpanded(true) }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            if (icon) Icon(Icons.Default.ClosedCaption, contentDescription = "Subtitles")
            else Text(label, maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { onExpanded(false) }, content = content)
    }
}

private fun mimeFor(url: String, label: String): String? {
    val ext = url.substringBefore("?").substringAfterLast(".", "").lowercase()
    return when (ext) {
        "vtt" -> MimeTypes.TEXT_VTT
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> when (label.lowercase()) {
            "vtt", "webvtt" -> MimeTypes.TEXT_VTT
            "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            else -> null
        }
    }
}