package com.pirachest.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pirachest.android.R
import com.pirachest.android.feature.anime.AnimeScreen
import com.pirachest.android.feature.downloads.DownloadsScreen
import com.pirachest.android.feature.youtube.YouTubeScreen
import com.pirachest.android.feature.youtube.player.DownloadedVideoPlayer
import com.pirachest.android.feature.settings.SettingsScreen
import com.pirachest.android.core.preferences.AppPreferences
import com.pirachest.android.ui.theme.PiraTheme
import androidx.compose.ui.platform.LocalContext

private enum class Page(val label: String) {
    HOME("Home"), ANIME("Anime"), YOUTUBE("YouTube"), DOWNLOADS("Downloads"),
    MUSIC("Music"), MANGA("Manga"), MOVIES("Movies"), GAMES("Games"), SETTINGS("Settings")
}

@Composable
fun PiraChestApp() {
    val context = LocalContext.current
    var themeId by remember { mutableStateOf(AppPreferences.themeId(context)) }
    var themeMode by remember { mutableStateOf(AppPreferences.themeMode(context)) }
    var page by remember { mutableStateOf(Page.HOME) }
    var playingFile by remember { mutableStateOf<String?>(null) }
    var playingTitle by remember { mutableStateOf<String?>(null) }

    val tabStateHolder = rememberSaveableStateHolder()

    PiraTheme(themeId, themeMode) {

        playingFile?.let { file ->
            Dialog(
                onDismissRequest = { playingFile = null },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false
                )
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    DownloadedVideoPlayer(
                        filePath = file,
                        title = playingTitle,
                        modifier = Modifier.fillMaxSize(),
                        onBack = { playingFile = null }
                    )
                }
            }
        }
        Scaffold(
            topBar = {
                if (page == Page.HOME) PiraTopBar() else SectionTopBar(page.label)
            },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    listOf(Page.HOME, Page.ANIME, Page.YOUTUBE, Page.DOWNLOADS, Page.SETTINGS).forEach { item ->
                        val icon = when (item) {
                            Page.HOME -> Icons.Rounded.Home
                            Page.ANIME -> Icons.Rounded.Movie
                            Page.YOUTUBE -> Icons.Rounded.PlayCircle
                            Page.DOWNLOADS -> Icons.Rounded.Download
                            else -> Icons.Rounded.Settings
                        }
                        NavigationBarItem(
                            selected = page == item,
                            onClick = { page = item },
                            icon = { Icon(icon, contentDescription = item.label) },
                            label = { Text(item.label, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            tabStateHolder.SaveableStateProvider(page.name) {
            when (page) {
                Page.HOME -> HomeScreen(Modifier.padding(padding), onOpen = { page = it })
                Page.ANIME -> AnimeScreen(Modifier.padding(padding))
                Page.YOUTUBE -> YouTubeScreen(Modifier.padding(padding))
                Page.DOWNLOADS -> DownloadsScreen(
                    Modifier.padding(padding),
                    onPlay = { path, title -> playingTitle = title; playingFile = path }
                )
                Page.SETTINGS -> SettingsScreen(
                    modifier = Modifier.padding(padding),
                    selectedTheme = themeId,
                    themeMode = themeMode,
                    onThemeChange = { themeId = it; AppPreferences.setThemeId(context, it) },
                    onThemeModeChange = { themeMode = it; AppPreferences.setThemeMode(context, it) }
                )
                else -> ComingSoonScreen(Modifier.padding(padding), page.label)
            }
            }
        }
    }
}

@Composable
private fun PiraTopBar() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(58.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.pirachest_logo),
                contentDescription = "PiraChest",
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.width(11.dp))
            Text("PiraChest", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionTopBar(title: String) {
    CenterAlignedTopAppBar(
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

@Composable
private fun HomeScreen(modifier: Modifier, onOpen: (Page) -> Unit) {
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Home", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text("Everything in one place.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { HomeFeature("Anime", "Browse anime and watch", Icons.Rounded.Movie) { onOpen(Page.ANIME) } }
        item { HomeFeature("YouTube", "Fetch YouTube video and download media", Icons.Rounded.PlayCircle) { onOpen(Page.YOUTUBE) } }
        item {
            Text("Library", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, bottom = 1.dp))
        }
        item { ComingSoonRow("Music", "Music discovery and downloads", Icons.Rounded.MusicNote) }
        item { ComingSoonRow("Manga & books", "Reading library", Icons.AutoMirrored.Rounded.MenuBook) }
        item { ComingSoonRow("Movies & TV", "Movies, shows and channels", Icons.Rounded.LiveTv) }
        item { ComingSoonRow("Games & ROMs", "Games, repacks and emulation", Icons.Rounded.SportsEsports) }
    }
}

@Composable
private fun HomeFeature(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ComingSoonRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        Row(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text("Coming soon", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ComingSoonScreen(modifier: Modifier, name: String) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Construction, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(38.dp))
            Text(name, style = MaterialTheme.typography.headlineSmall)
            Text("Coming soon", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}