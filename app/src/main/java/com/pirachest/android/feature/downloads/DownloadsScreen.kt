package com.pirachest.android.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.pirachest.android.core.downloads.DownloadItem
import com.pirachest.android.core.downloads.DownloadManagerStore
import com.pirachest.android.core.downloads.DownloadStatus
import com.pirachest.android.core.downloads.MediaPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DownloadsScreen(modifier: Modifier = Modifier, onPlay: (path: String, title: String) -> Unit = { _, _ -> }) {
    val downloads by DownloadManagerStore.items.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${downloads.count { it.status == DownloadStatus.DOWNLOADING }} active · ${downloads.count { it.status == DownloadStatus.COMPLETED }} completed",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            if (downloads.any { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED }) {
                TextButton(onClick = { DownloadManagerStore.clearFinished() }) { Text("Clear list") }
            }
        }
        if (downloads.isEmpty()) {
            Text("No downloads yet. Start a download from Anime or YouTube.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(downloads, key = { it.id }) { item -> DownloadCard(item, onPlay) }
            }
        }
    }
}

@Composable
private fun DownloadCard(item: DownloadItem, onPlay: (path: String, title: String) -> Unit) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    val fileExists by produceState<Boolean?>(initialValue = null, item.filePath, item.status) {
        val path = item.filePath
        value = if (item.status != DownloadStatus.COMPLETED || path == null) null
        else withContext(Dispatchers.IO) { MediaPublisher.exists(context, path) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(9.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!item.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.thumbnailUrl,
                        contentDescription = "Download thumbnail",
                        modifier = Modifier.width(112.dp).height(68.dp),
                        contentScale = ContentScale.Crop
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                    Text("${item.kind} · ${item.status.name.lowercase().replace('_', ' ')}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall)
                }
                if (item.status == DownloadStatus.COMPLETED || item.status == DownloadStatus.FAILED) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                    }
                }
            }
            if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.QUEUED) {
                if (item.progress > 0f) {
                    LinearProgressIndicator(
                        progress = { item.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text("${item.progress.toInt()}% · ${item.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                item.savedLocation?.let { location ->
                    Text(
                        location,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (item.status == DownloadStatus.COMPLETED) {
                    val path = item.filePath
                    val ext = (item.savedLocation ?: path.orEmpty()).substringAfterLast('.', "").lowercase()
                    val isVideo = ext in setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp")
                    when {
                        fileExists == false -> Text(
                            "This file was deleted or moved outside PiraChest.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        path != null && isVideo -> Button(
                            onClick = { onPlay(path, item.title) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Play video")
                        }
                    }
                }
                if (item.status == DownloadStatus.FAILED) {
                    Text(item.detail, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (confirmDelete) {
        val hasFile = item.status == DownloadStatus.COMPLETED && item.filePath != null
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (hasFile) "Delete this download?" else "Remove from list?") },
            text = {
                Text(
                    if (hasFile) "\"${item.title}\" can be deleted from your device, or only removed from this list (the file stays in your downloads folder)."
                    else item.title
                )
            },
            confirmButton = {
                if (hasFile) {
                    TextButton(onClick = {
                        confirmDelete = false
                        val path = item.filePath
                        if (path != null) MediaPublisher.delete(context, path)
                        DownloadManagerStore.remove(item.id)
                    }) { Text("Delete file") }
                } else {
                    TextButton(onClick = { confirmDelete = false; DownloadManagerStore.remove(item.id) }) { Text("Remove") }
                }
            },
            dismissButton = {
                Row {
                    if (hasFile) {
                        TextButton(onClick = { confirmDelete = false; DownloadManagerStore.remove(item.id) }) { Text("Remove from list") }
                    }
                    TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                }
            }
        )
    }
}
