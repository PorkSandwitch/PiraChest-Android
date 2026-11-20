package com.pirachest.android.feature.anime

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.pirachest.android.feature.anime.data.AnimeCatalogItem
import com.pirachest.android.feature.anime.data.AnimeEpisode
import com.pirachest.android.feature.anime.data.VideoStreamSource
import com.pirachest.android.feature.anime.components.ContinueWatchingRow
import com.pirachest.android.feature.anime.components.DownloadRangeSheet
import com.pirachest.android.feature.anime.components.EpisodeOptionsSheet
import com.pirachest.android.feature.anime.data.AudioChoice
import com.pirachest.android.feature.anime.data.HistoryEntry
import com.pirachest.android.feature.anime.data.QualityChoice
import com.pirachest.android.feature.anime.player.AnimePlayerOverlay
import kotlin.math.ceil

@Composable
fun AnimeScreen(
    modifier: Modifier = Modifier,
    viewModel: AnimeViewModel = viewModel(),
    onDownloadStream: ((title: String, stream: VideoStreamSource) -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    DisposableEffect(viewModel) {
        onDispose { viewModel.cancelPendingWork() }
    }

    state.activeStream?.let { stream ->
        val epNum = state.playingEpisodeNumber ?: 1
        val epTitle = "${state.selectedAnime?.displayTitle.orEmpty()} • Episode $epNum"
        val hasNext = state.episodes.any { it.number > epNum }

        Dialog(
            onDismissRequest = viewModel::closePlayer,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AnimePlayerOverlay(
                    title = epTitle,
                    stream = stream,
                    availableStreams = state.availableStreams,
                    providers = state.providers,
                    selectedProviderId = state.selectedProviderId,
                    resolvingEpisodeNumber = state.resolvingEpisodeNumber,
                    onSelectProvider = viewModel::selectProvider,
                    onSelectStream = viewModel::selectStreamQuality,
                    onNextEpisode = if (hasNext) viewModel::playNextEpisode else null,
                    onEnded = viewModel::onEpisodeEnded,
                    preferredMaxHeight = state.playingMaxHeight,
                    onClose = viewModel::closePlayer
                )
            }
        }
        return
    }

    if (state.selectedAnime != null) {
        BackHandler { viewModel.clearSelectedAnime() }
        AnimeDetailAndEpisodesView(
            modifier = modifier,
            anime = state.selectedAnime!!,
            episodes = state.episodes,
            isLoadingEpisodes = state.isLoadingEpisodes,
            providers = state.providers,
            selectedProviderId = state.selectedProviderId,
            resolvingEpisodeNumber = state.resolvingEpisodeNumber,
            error = state.error,
            notice = state.notice,
            onSelectProvider = viewModel::selectProvider,
            onBack = viewModel::clearSelectedAnime,
            audio = state.audio,
            quality = state.quality,
            watchedEpisodes = state.watchedEpisodes,
            onSetAudio = viewModel::setAudio,
            onSetQuality = viewModel::setQuality,
            onToggleWatched = viewModel::setEpisodeWatched,
            onPlayEpisode = viewModel::playEpisode,
            onDownloadEpisode = viewModel::downloadEpisode,
            onDownloadEpisodes = viewModel::downloadEpisodes,
            onCancelResolving = viewModel::closePlayer,
            onDismissNotice = viewModel::clearNotice
        )
    } else {
        AnimeBrowseView(
            modifier = modifier,
            state = state,
            onQueryChange = viewModel::onQueryChange,
            onSearch = viewModel::search,
            onClearSearch = viewModel::clearSearch,
            onSelectAnime = viewModel::selectAnime,
            onContinueWatching = viewModel::continueWatching,
            onRemoveHistory = viewModel::removeFromHistory
        )
    }
}

@Composable
private fun AnimeBrowseView(
    modifier: Modifier,
    state: AnimeUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onSelectAnime: (AnimeCatalogItem) -> Unit,
    onContinueWatching: (HistoryEntry) -> Unit,
    onRemoveHistory: (HistoryEntry) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp),
            placeholder = { Text("Search anime...") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = onClearSearch) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() })
        )

        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        state.error?.let { err ->
            Text(
                text = err,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        if (state.isSearching) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(state.searchResults, key = { it.anilistId }) { anime ->
                    AnimePosterCard(
                        anime = anime,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onSelectAnime(anime) }
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (state.continueWatching.isNotEmpty()) {
                    item {
                        ContinueWatchingRow(
                            entries = state.continueWatching,
                            onContinue = onContinueWatching,
                            onRemove = onRemoveHistory
                        )
                    }
                }
                if (state.homeSections.trending.isNotEmpty()) {
                    item {
                        AnimeSectionRow(
                            title = "Trending Now",
                            items = state.homeSections.trending,
                            onSelectAnime = onSelectAnime
                        )
                    }
                }
                if (state.homeSections.popularSeason.isNotEmpty()) {
                    item {
                        AnimeSectionRow(
                            title = "Popular This Season",
                            items = state.homeSections.popularSeason,
                            onSelectAnime = onSelectAnime
                        )
                    }
                }
                if (state.homeSections.allTimePopular.isNotEmpty()) {
                    item {
                        AnimeSectionRow(
                            title = "All-Time Popular",
                            items = state.homeSections.allTimePopular,
                            onSelectAnime = onSelectAnime
                        )
                    }
                }
                if (state.homeSections.topRated.isNotEmpty()) {
                    item {
                        AnimeSectionRow(
                            title = "Top Rated",
                            items = state.homeSections.topRated,
                            onSelectAnime = onSelectAnime
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeSectionRow(
    title: String,
    items: List<AnimeCatalogItem>,
    onSelectAnime: (AnimeCatalogItem) -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { "${title}_${it.anilistId}" }) { anime ->
                AnimePosterCard(
                    anime = anime,
                    modifier = Modifier.width(138.dp),
                    onClick = { onSelectAnime(anime) }
                )
            }
        }
    }
}

@Composable
private fun AnimePosterCard(
    anime: AnimeCatalogItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
            ) {
                AsyncImage(
                    model = anime.coverUrl,
                    contentDescription = anime.displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                anime.score?.let { sc ->
                    Surface(
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(bottomStart = 8.dp),
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                tint = Color(0xFFFFC107),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = String.format("%.1f", sc / 10.0),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = anime.displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                val subtitle = buildString {
                    anime.format?.let { append(it) }
                    anime.episodesCount?.let {
                        if (isNotEmpty()) append(" • ")
                        append("$it Eps")
                    }
                }
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AnimeDetailAndEpisodesView(
    modifier: Modifier,
    anime: AnimeCatalogItem,
    episodes: List<AnimeEpisode>,
    isLoadingEpisodes: Boolean,
    providers: List<ProviderOption>,
    selectedProviderId: String,
    resolvingEpisodeNumber: Int?,
    error: String?,
    notice: String?,
    onSelectProvider: (String) -> Unit,
    onBack: () -> Unit,
    audio: AudioChoice,
    quality: QualityChoice,
    watchedEpisodes: Set<Int>,
    onSetAudio: (AudioChoice) -> Unit,
    onSetQuality: (QualityChoice) -> Unit,
    onToggleWatched: (Int, Boolean) -> Unit,
    onPlayEpisode: (AnimeEpisode) -> Unit,
    onDownloadEpisode: (AnimeEpisode) -> Unit,
    onDownloadEpisodes: (List<Int>) -> Unit,
    onCancelResolving: () -> Unit,
    onDismissNotice: () -> Unit
) {
    var dialogEpisode by remember { mutableStateOf<AnimeEpisode?>(null) }
    var showRangeSheet by remember { mutableStateOf(false) }
    val pageSize = 50
    val totalPages = maxOf(1, ceil(episodes.size / pageSize.toDouble()).toInt())
    var selectedPage by remember(anime.anilistId, episodes.size) { mutableIntStateOf(0) }
    var expandSynopsis by remember(anime.anilistId) { mutableStateOf(false) }

    val safePage = selectedPage.coerceIn(0, totalPages - 1)
    val pageEpisodes = remember(episodes, safePage) {
        val from = safePage * pageSize
        val to = minOf(from + pageSize, episodes.size)
        if (from < to) episodes.subList(from, to) else emptyList()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
            ) {
                AsyncImage(
                    model = anime.bannerUrl ?: anime.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.45f),
                                    MaterialTheme.colorScheme.background
                                )
                            )
                        )
                )
                FilledTonalIconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .padding(12.dp)
                        .align(Alignment.TopStart)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    AsyncImage(
                        model = anime.coverUrl,
                        contentDescription = anime.displayTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(96.dp)
                            .height(136.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = anime.displayTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        val meta = listOfNotNull(
                            anime.format,
                            anime.status,
                            anime.episodesCount?.let { "$it Episodes" },
                            anime.score?.let { "★ ${String.format("%.1f", it / 10.0)}" }
                        ).joinToString(" • ")
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (anime.genres.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = anime.genres.take(3).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        anime.description?.let { desc ->
            item {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expandSynopsis) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable { expandSynopsis = !expandSynopsis }
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ProviderDropdownSelector(
                    providers = providers,
                    selectedProviderId = selectedProviderId,
                    onSelectProvider = onSelectProvider,
                    modifier = Modifier.weight(1f)
                )

                episodes.firstOrNull()?.let { ep1 ->
                    Button(
                        onClick = { onPlayEpisode(ep1) },
                        enabled = resolvingEpisodeNumber == null,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Watch Ep ${ep1.number}")
                    }
                }
            }
        }

        if (resolvingEpisodeNumber != null) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Extracting stream for Episode $resolvingEpisodeNumber...",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onCancelResolving) { Text("Cancel") }
                    }
                }
            }
        }

        error?.let { err ->
            item {
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }

        notice?.let { msg ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(msg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismissNotice) { Icon(Icons.Default.Close, contentDescription = "Dismiss") }
                    }
                }
            }
        }

        if (isLoadingEpisodes) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Column {
                            Text("Loading episode list…", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Checking providers for available episodes",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Episodes (${episodes.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (episodes.isNotEmpty()) {
                        TextButton(onClick = { showRangeSheet = true }) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Download episodes")
                        }
                    }
                }
                if (totalPages > 1) {
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(totalPages) { pageIdx ->
                            val start = pageIdx * pageSize + 1
                            val end = minOf((pageIdx + 1) * pageSize, episodes.size)
                            FilterChip(
                                selected = safePage == pageIdx,
                                onClick = { selectedPage = pageIdx },
                                label = { Text("$start–$end") }
                            )
                        }
                    }
                }
            }
        }

        val rows = pageEpisodes.chunked(4)
        items(rows) { rowEpisodes ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (ep in rowEpisodes) {
                    val isResolving = resolvingEpisodeNumber == ep.number
                    val isWatched = ep.number in watchedEpisodes
                    FilledTonalButton(
                        onClick = { dialogEpisode = ep },
                        enabled = resolvingEpisodeNumber == null,
                        colors = if (isWatched) ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        ) else ButtonDefaults.filledTonalButtonColors(),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isResolving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = if (isWatched) "✓ ${ep.number}" else "Ep ${ep.number}",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1
                            )
                        }
                    }
                }
                repeat(4 - rowEpisodes.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }

    dialogEpisode?.let { ep ->
        EpisodeOptionsSheet(
            animeTitle = anime.displayTitle,
            episodeNumber = ep.number,
            isWatched = ep.number in watchedEpisodes,
            audio = audio,
            quality = quality,
            onAudio = onSetAudio,
            onQuality = onSetQuality,
            onWatch = { dialogEpisode = null; onPlayEpisode(ep) },
            onDownload = { dialogEpisode = null; onDownloadEpisode(ep) },
            onToggleWatched = { onToggleWatched(ep.number, ep.number !in watchedEpisodes) },
            onDismiss = { dialogEpisode = null }
        )
    }

    if (showRangeSheet) {
        DownloadRangeSheet(
            episodeNumbers = episodes.map { it.number },
            watched = watchedEpisodes,
            audio = audio,
            quality = quality,
            onAudio = onSetAudio,
            onQuality = onSetQuality,
            onDownload = { numbers -> showRangeSheet = false; onDownloadEpisodes(numbers) },
            onDismiss = { showRangeSheet = false }
        )
    }
}

@Composable
fun ProviderDropdownSelector(
    providers: List<ProviderOption>,
    selectedProviderId: String,
    onSelectProvider: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = providers.firstOrNull { it.id == selectedProviderId }?.name
        ?: "All Providers (Auto)"

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = selectedName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Provider")
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            providers.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.name) },
                    onClick = {
                        expanded = false
                        onSelectProvider(provider.id)
                    }
                )
            }
        }
    }
}