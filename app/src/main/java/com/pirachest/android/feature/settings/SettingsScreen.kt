package com.pirachest.android.feature.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import com.pirachest.android.core.media.MediaToolchainUpdater
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.DocumentsContract
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pirachest.android.BuildConfig
import com.pirachest.android.core.downloads.DownloadManagerStore
import com.pirachest.android.core.preferences.AppPreferences
import com.pirachest.android.ui.theme.PiraThemePreset
import com.pirachest.android.ui.theme.PiraThemePresets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    selectedTheme: String,
    themeMode: String,
    onThemeChange: (String) -> Unit,
    onThemeModeChange: (String) -> Unit,
) {
    val context = LocalContext.current
    var showThemes by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    val mediaStatus by MediaToolchainUpdater.status.collectAsStateWithLifecycle()
    var cacheBytes by remember { mutableLongStateOf(calculateCacheSize(context.cacheDir) + (context.externalCacheDir?.let(::calculateCacheSize) ?: 0L)) }
    val selectedTree = AppPreferences.downloadTreeUri(context)?.let(Uri::parse)
    val downloadFolder = selectedTree?.let { uri ->
        runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(":").ifBlank { "Selected folder" } }.getOrDefault("Selected folder")
    } ?: "Internal storage/Download/PiraChest"
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                AppPreferences.setDownloadTreeUri(context, uri.toString())
            }
        }
    }
    val currentTheme = PiraThemePresets.firstOrNull { it.id == selectedTheme } ?: PiraThemePresets.first()

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
                Text("Customize PiraChest and manage the app", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { SectionHeading("Appearance") }
        item {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("App theme", fontWeight = FontWeight.SemiBold)
                        Text(currentTheme.name, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { showThemes = true }) { Text("Choose") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .25f))
                Text("Theme mode", fontWeight = FontWeight.Medium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEachIndexed { index, (value, label) ->
                        SegmentedButton(selected = themeMode == value, onClick = { onThemeModeChange(value) }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(label) }
                    }
                }
                                TextButton(onClick = {
                    onThemeChange("desktop_dark")
                    onThemeModeChange("system")
                }, modifier = Modifier.align(Alignment.End)) { Text("Reset appearance") }
            }
        }
        item { SectionHeading("Playback") }
        item {
            SettingsCard {
                var softwareDecoding by remember { mutableStateOf(AppPreferences.softwareDecoding(context)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Software video decoding", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Off (recommended): uses your phone's hardware decoder for smooth playback and lower battery use. Turn on only if a video shows a black or corrupted picture. Applies next time a video is opened.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(checked = softwareDecoding, onCheckedChange = {
                        softwareDecoding = it
                        AppPreferences.setSoftwareDecoding(context, it)
                    })
                }
            }
        }
        item { SectionHeading("Downloads") }
        item {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Default download folder", fontWeight = FontWeight.SemiBold)
                        Text(downloadFolder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    if (selectedTree == null) "YouTube downloads are saved in the public Downloads/PiraChest folder."
                    else "YouTube downloads are saved in the folder you selected. Android may display the folder's parent name above.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { folderPicker.launch(selectedTree) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(7.dp)); Text("Change folder")
                    }
                    if (selectedTree != null) {
                        TextButton(onClick = { AppPreferences.setDownloadTreeUri(context, null) }) { Text("Default") }
                    }
                }
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("PiraChest download folder", downloadFolder))
                }) { Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Copy folder name") }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .25f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Clear finished download entries", fontWeight = FontWeight.Medium)
                        Text("Removes completed and failed items from the Downloads list. Files are not deleted.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { DownloadManagerStore.clearFinished() }) { Text("Clear") }
                }
            }
        }
        item { SectionHeading("Maintenance") }
        item {
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Media tools", fontWeight = FontWeight.SemiBold)
                    Text(mediaStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { MediaToolchainUpdater.checkAgain(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Check yt-dlp for updates")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .25f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Temporary cache", fontWeight = FontWeight.Medium)
                        Text("${formatBytes(cacheBytes)}. Clears temporary app cache only; downloaded videos and media tools are kept.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { showClearCacheDialog = true }) { Text("Clear") }
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        })
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.AppSettingsAlt, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open Android app settings")
                }
            }
        }
        item { SectionHeading("About") }
        item {
            SettingsCard {
                AboutRow("PiraChest version", BuildConfig.VERSION_NAME)
                AboutRow("Version code", BuildConfig.VERSION_CODE.toString())
                AboutRow("Android version", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                AboutRow("Package", BuildConfig.APPLICATION_ID)
                Text("PiraChest Android", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Text("Download history is currently kept in memory and may reset when the app process closes. Background downloads and persistent history are still being developed.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear temporary cache?") },
            text = { Text("This removes temporary cached files only. Your downloaded videos, app settings and media tools will not be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                        context.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                    }
                    cacheBytes = calculateCacheSize(context.cacheDir) + (context.externalCacheDir?.let(::calculateCacheSize) ?: 0L)
                    showClearCacheDialog = false
                }) { Text("Clear cache") }
            },
            dismissButton = { TextButton(onClick = { showClearCacheDialog = false }) { Text("Cancel") } }
        )
    }

    if (showThemes) {
        AlertDialog(
            onDismissRequest = { showThemes = false },
            title = { Text("Choose a theme") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.heightIn(max = 480.dp)) {
                    items(PiraThemePresets) { preset ->
                        ThemeOption(preset, selectedTheme == preset.id) {
                            onThemeChange(preset.id)
                            showThemes = false
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showThemes = false }) { Text("Close") } }
        )
    }
}

@Composable private fun SectionHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 1.dp))
}

@Composable private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp), content = content)
    }
}

@Composable private fun AboutRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable private fun ThemeOption(preset: PiraThemePreset, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(11.dp), border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = .35f)), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(8.dp), color = preset.background, border = BorderStroke(1.dp, preset.muted.copy(alpha = .4f)), modifier = Modifier.size(48.dp)) {
                Column(Modifier.padding(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(Modifier.fillMaxWidth().height(7.dp), color = preset.accent, shape = RoundedCornerShape(4.dp)) {}
                    Surface(Modifier.fillMaxWidth(.72f).height(5.dp), color = preset.text.copy(alpha = .75f), shape = RoundedCornerShape(4.dp)) {}
                    Surface(Modifier.fillMaxWidth(.48f).height(5.dp), color = preset.muted, shape = RoundedCornerShape(4.dp)) {}
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(preset.name, fontWeight = FontWeight.SemiBold)
                Text(if (preset.dark) "Dark palette" else "Light palette", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun calculateCacheSize(file: java.io.File): Long {
    if (!file.exists()) return 0L
    if (file.isFile) return file.length()
    return file.listFiles()?.sumOf(::calculateCacheSize) ?: 0L
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
    return String.format(java.util.Locale.US, "%.1f MB", kb / 1024.0)
}
