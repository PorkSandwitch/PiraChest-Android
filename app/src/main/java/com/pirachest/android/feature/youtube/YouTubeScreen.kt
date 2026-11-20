package com.pirachest.android.feature.youtube

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import coil.compose.AsyncImage
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pirachest.android.core.media.MediaToolchainUpdater
import com.pirachest.android.feature.youtube.data.VideoCodec

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeScreen(modifier: Modifier = Modifier, vm: YouTubeViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val toolStatus by MediaToolchainUpdater.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val urlFocusRequester = remember { FocusRequester() }
    var qualityMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Text("Fetch YouTube video and download media.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f), shape = RoundedCornerShape(8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                if (toolStatus.contains("Preparing") || toolStatus.contains("Checking")) {
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(9.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(toolStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("FFmpeg runtime: bundled with PiraChest", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (toolStatus.startsWith("Update check failed")) {
                    TextButton(onClick = { MediaToolchainUpdater.checkAgain(context) }) { Text("Retry") }
                }
            }
        }
        OutlinedTextField(
            value = state.url,
            onValueChange = vm::setUrl,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(urlFocusRequester)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) keyboardController?.show()
                },
            label = { Text("Video URL") },
            placeholder = { Text("Paste a YouTube link") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { keyboardController?.hide() }
            ),
            shape = RoundedCornerShape(8.dp),
            trailingIcon = {
                if (state.url.isNotBlank()) {
                    IconButton(onClick = { vm.setUrl("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear video link")
                    }
                }
            }
        )
        Button(
            onClick = vm::fetch,
            enabled = !state.loading && state.url.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (state.loading) "Inspecting video…" else "Fetch video") }

        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        state.message?.let {
            Text(it, color = if (state.downloadedPath != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }

        state.video?.let { video ->
            Card(shape = RoundedCornerShape(9.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    video.thumbnail?.let { thumbnail ->
                        AsyncImage(
                            model = thumbnail,
                            contentDescription = "Video thumbnail",
                            modifier = Modifier.fillMaxWidth().height(190.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    }
                    Text(video.title, style = MaterialTheme.typography.titleMedium)
                    if (video.uploader.isNotBlank()) {
                        Text(video.uploader, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.loading && video.qualities.isEmpty()) {
                        Text("Loading available qualities…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        if (video.durationSeconds > 0) "Duration: ${video.durationSeconds / 60}:${(video.durationSeconds % 60).toString().padStart(2, '0')}" else "Duration unavailable",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall
                    )
                    state.selectedQuality?.estimatedBytes?.let { estimated ->
                        Text(
                            "Estimated ${state.selectedCodec.label} download size: ~${formatSize(estimated)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    HorizontalDivider()
                    Text("Video codec", style = MaterialTheme.typography.titleSmall)
                    val availableCodecs = video.qualities.map { it.codec }.distinct()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VideoCodec.values().forEach { codec ->
                            val available = codec in availableCodecs
                            FilterChip(
                                selected = state.selectedCodec == codec,
                                onClick = { vm.setCodec(codec) },
                                enabled = available,
                                label = { Text(codec.label) }
                            )
                        }
                    }
                    Text(
                        when (state.selectedCodec) {
                            VideoCodec.H264 -> "H.264: widely compatible with phones, TVs and editing apps. Files are often larger at similar quality."
                            VideoCodec.AV1 -> "AV1: more efficient compression and often smaller files. Playback support depends on your device."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (availableCodecs.size < VideoCodec.values().size) {
                        Text(
                            "Some codecs are unavailable for this video. Only formats actually offered by YouTube can be selected.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    ExposedDropdownMenuBox(
                        expanded = qualityMenuExpanded,
                        onExpandedChange = { if (!state.audioOnly) qualityMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = state.selectedQuality?.label ?: "No ${state.selectedCodec.label} formats",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Video quality") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = qualityMenuExpanded) },
                            modifier = Modifier.menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !state.audioOnly
                        )
                        ExposedDropdownMenu(
                            expanded = qualityMenuExpanded,
                            onDismissRequest = { qualityMenuExpanded = false }
                        ) {
                            video.qualities.filter { it.codec == state.selectedCodec }.forEach { quality ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            quality.label + (quality.estimatedBytes?.let { " · ~${formatSize(it)}" } ?: "")
                                        )
                                    },
                                    onClick = {
                                        vm.setQuality(quality)
                                        qualityMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Audio only", style = MaterialTheme.typography.bodyMedium)
                            Text("Extract MP3 using FFmpeg", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = state.audioOnly,
                            onCheckedChange = vm::setAudioOnly,
                            enabled = true,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    if (state.activeDownloads > 0) {
                        val eta = state.etaSeconds.takeIf { it > 0 }?.let { " · about ${it}s left" }.orEmpty()
                        Text(
                            when {
                                state.activeDownloads > 1 -> "${state.activeDownloads} downloads active · individual progress is in Downloads"
                                state.progress > 0f -> "Download active · ${state.progress.toInt()}%$eta"
                                else -> "Download active · preparing…"
                            },
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(onClick = vm::download, enabled = state.selectedQuality != null || state.audioOnly, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.audioOnly) "Download MP3" else "Download ${state.selectedCodec.label} ${state.selectedQuality?.label ?: "video"}")
                    }
                    state.downloadedPath?.let {
                        Text(
                            "Added to Downloads. You can start another quality or fetch another video while this download continues.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val mb = bytes / (1024.0 * 1024.0)
    if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
    return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
}
