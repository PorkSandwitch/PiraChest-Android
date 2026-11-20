package com.pirachest.android.feature.anime.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pirachest.android.feature.anime.data.AudioChoice
import com.pirachest.android.feature.anime.data.QualityChoice

@Composable
fun AudioQualityChooser(
    audio: AudioChoice,
    quality: QualityChoice,
    onAudio: (AudioChoice) -> Unit,
    onQuality: (QualityChoice) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Audio", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioChoice.entries.forEach { choice ->
                FilterChip(
                    selected = audio == choice,
                    onClick = { onAudio(choice) },
                    label = { Text(if (choice == AudioChoice.SUB) "Sub (Japanese + subtitles)" else "Dub (English)") }
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text("Quality", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QualityChoice.entries.forEach { choice ->
                FilterChip(
                    selected = quality == choice,
                    onClick = { onQuality(choice) },
                    label = { Text(when (choice) { QualityChoice.BEST -> "Best available"; QualityChoice.FASTEST -> "Fastest"; else -> choice.label }) }
                )
            }
        }
        Text(
            "Quality choices prefer the exact resolution; if it is unavailable, the nearest available resolution is used. Fastest tests the available stream links.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeOptionsSheet(
    animeTitle: String,
    episodeNumber: Int,
    isWatched: Boolean,
    audio: AudioChoice,
    quality: QualityChoice,
    onAudio: (AudioChoice) -> Unit,
    onQuality: (QualityChoice) -> Unit,
    onWatch: () -> Unit,
    onDownload: () -> Unit,
    onToggleWatched: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column {
                Text(
                    animeTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Episode $episodeNumber" + if (isWatched) " · watched" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AudioQualityChooser(audio, quality, onAudio, onQuality)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onWatch, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Watch")
                }
                OutlinedButton(onClick = onDownload, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Download")
                }
            }
            TextButton(onClick = onToggleWatched, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (isWatched) "Mark as not watched" else "Mark as watched")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadRangeSheet(
    episodeNumbers: List<Int>,
    watched: Set<Int>,
    audio: AudioChoice,
    quality: QualityChoice,
    onAudio: (AudioChoice) -> Unit,
    onQuality: (QualityChoice) -> Unit,
    onDownload: (List<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    val sorted = remember(episodeNumbers) { episodeNumbers.sorted() }
    var from by remember { mutableStateOf(sorted.firstOrNull()?.toString().orEmpty()) }
    var to by remember { mutableStateOf(sorted.lastOrNull()?.toString().orEmpty()) }
    var unwatchedOnly by remember { mutableStateOf(false) }

    val fromNumber = from.toIntOrNull()
    val toNumber = to.toIntOrNull()
    val selected = if (fromNumber == null || toNumber == null) emptyList() else sorted.filter {
        it in fromNumber..toNumber && (!unwatchedOnly || it !in watched)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Download episodes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = from,
                    onValueChange = { from = it.filter(Char::isDigit).take(4) },
                    label = { Text("From") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = to,
                    onValueChange = { to = it.filter(Char::isDigit).take(4) },
                    label = { Text("To") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = unwatchedOnly, onCheckedChange = { unwatchedOnly = it })
                Spacer(Modifier.width(10.dp))
                Text("Skip episodes I already watched")
            }

            AudioQualityChooser(audio, quality, onAudio, onQuality)

            Text(
                "Episodes download one after another. Large batches take a while on a phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { onDownload(selected) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (selected.isEmpty()) "No episodes in this range" else "Download ${selected.size} episode${if (selected.size == 1) "" else "s"}")
            }
        }
    }
}
