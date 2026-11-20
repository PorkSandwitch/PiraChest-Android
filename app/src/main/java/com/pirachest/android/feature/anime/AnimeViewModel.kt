package com.pirachest.android.feature.anime

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pirachest.android.core.downloads.DownloadManagerStore
import com.pirachest.android.core.downloads.DownloadService
import com.pirachest.android.core.downloads.DownloadStatus
import com.pirachest.android.core.media.MediaToolchainUpdater
import com.pirachest.android.core.preferences.AppPreferences
import com.pirachest.android.feature.anime.data.AnimeCatalogItem
import com.pirachest.android.feature.anime.data.AnimeEpisode
import com.pirachest.android.feature.anime.data.AnimeHistoryStore
import com.pirachest.android.feature.anime.data.AnimeHomeSections
import com.pirachest.android.feature.anime.data.AnimeRepository
import com.pirachest.android.feature.anime.data.AudioChoice
import com.pirachest.android.feature.anime.data.HistoryEntry
import com.pirachest.android.feature.anime.data.QualityChoice
import com.pirachest.android.feature.anime.data.StreamPicker
import com.pirachest.android.feature.anime.data.VideoStreamSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProviderOption(
    val id: String,
    val name: String
)

data class AnimeUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingEpisodes: Boolean = false,
    val error: String? = null,
    val homeSections: AnimeHomeSections = AnimeHomeSections(),
    val searchResults: List<AnimeCatalogItem> = emptyList(),
    val selectedAnime: AnimeCatalogItem? = null,
    val episodes: List<AnimeEpisode> = emptyList(),
    val providers: List<ProviderOption> = emptyList(),
    val selectedProviderId: String = "auto",
    val resolvingEpisodeNumber: Int? = null,
    val playingEpisodeNumber: Int? = null,
    val activeStream: VideoStreamSource? = null,
    val availableStreams: List<VideoStreamSource> = emptyList(),
    val notice: String? = null,
    
    val audio: AudioChoice = AudioChoice.SUB,
    val quality: QualityChoice = QualityChoice.BEST,
    
    val watchedEpisodes: Set<Int> = emptySet(),
    
    val continueWatching: List<HistoryEntry> = emptyList(),
    
    val playingMaxHeight: Int? = null
)

private data class QueuedDownload(val taskId: String, val label: String, val number: Int)

class AnimeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AnimeRepository(application)
    private val history = AnimeHistoryStore(application)
    private val episodeCache = mutableMapOf<String, List<AnimeEpisode>>()
    private var searchDebounceJob: Job? = null
    private var searchJob: Job? = null
    private var episodeLoadJob: Job? = null
    private var episodeLoadRequest = 0L
    private var streamResolveJob: Job? = null
    private var streamResolveRequest = 0L

    private val _uiState = MutableStateFlow(
        AnimeUiState(
            providers = listOf(ProviderOption("auto", "All Providers (Auto)")) +
                repository.streamProviders.map { ProviderOption(it.id, it.name) },
            audio = AudioChoice.fromId(AppPreferences.animeAudio(application)),
            quality = QualityChoice.fromId(AppPreferences.animeQuality(application)),
            continueWatching = history.load()
        )
    )
    val uiState: StateFlow<AnimeUiState> = _uiState.asStateFlow()

    init {
        loadHomeSections()
    }

    fun loadHomeSections() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.getHomeSections()
                .onSuccess { sections ->
                    _uiState.update { it.copy(isLoading = false, homeSections = sections) }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(isLoading = false, error = "Catalog error: ${err.message}")
                    }
                }
        }
    }

    fun onQueryChange(newQuery: String) {
        _uiState.update {
            it.copy(
                query = newQuery,
                isSearching = newQuery.isNotBlank(),
                error = null
            )
        }
        searchDebounceJob?.cancel()
        if (newQuery.isBlank()) {
            searchJob?.cancel()
            if (_uiState.value.homeSections.trending.isEmpty()) loadHomeSections()
        } else {
            
            searchDebounceJob = viewModelScope.launch {
                delay(550)
                search()
            }
        }
    }

    fun clearSearch() {
        searchDebounceJob?.cancel()
        searchJob?.cancel()
        _uiState.update {
            it.copy(query = "", isSearching = false, searchResults = emptyList(), error = null)
        }
    }

    fun search() {
        val q = _uiState.value.query.trim()
        if (q.isEmpty()) {
            clearSearch()
            return
        }

        searchDebounceJob?.cancel()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    isSearching = true,
                    error = null,
                    selectedAnime = null,
                    episodes = emptyList(),
                    isLoadingEpisodes = false
                )
            }
            repository.searchAnime(q)
                .onSuccess { items ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            searchResults = items,
                            error = if (items.isEmpty()) "No anime found for \"$q\"" else null
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(isLoading = false, error = "Search error: ${err.message}")
                    }
                }
        }
    }

    fun selectAnime(anime: AnimeCatalogItem) {
        val providerId = _uiState.value.selectedProviderId
        val cached = episodeCache[cacheKey(anime, providerId)]
        _uiState.update {
            it.copy(
                selectedAnime = anime,
                
                episodes = cached ?: emptyList(),
                isLoadingEpisodes = cached == null,
                watchedEpisodes = history.watchedFor(anime.anilistId),
                error = null
            )
        }
        if (cached == null) loadEpisodesFor(anime, providerId)
    }

    private fun cacheKey(anime: AnimeCatalogItem, providerId: String) = "${anime.anilistId}|$providerId"

    private fun loadEpisodesFor(anime: AnimeCatalogItem, providerId: String) {
        val requestId = ++episodeLoadRequest
        episodeLoadJob?.cancel()
        _uiState.update { it.copy(isLoadingEpisodes = true, error = null) }

        episodeLoadJob = viewModelScope.launch {
            try {
                
                val dbEpisodes = withTimeoutOrNull(30_000L) {
                    repository.getEpisodes(anime, providerId)
                        .distinctBy { it.number }
                        .sortedBy { it.number }
                }

                if (requestId != episodeLoadRequest) return@launch
                if (dbEpisodes == null) {
                    _uiState.update {
                        it.copy(
                            episodes = emptyList(),
                            isLoadingEpisodes = false,
                            error = "Loading episodes took too long. Check your connection and try again."
                        )
                    }
                    return@launch
                }
                if (dbEpisodes.isNotEmpty()) episodeCache[cacheKey(anime, providerId)] = dbEpisodes

                _uiState.update {
                    it.copy(
                        episodes = dbEpisodes,
                        isLoadingEpisodes = false,
                        error = if (dbEpisodes.isEmpty()) "No episodes found in database for this title." else null
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (err: Throwable) {
                if (requestId == episodeLoadRequest) {
                    _uiState.update {
                        it.copy(
                            episodes = emptyList(),
                            isLoadingEpisodes = false,
                            error = "Failed to load episodes: ${err.message ?: "Unknown error"}"
                        )
                    }
                }
            }
        }
    }

    fun cancelPendingWork() {
        ++episodeLoadRequest
        episodeLoadJob?.cancel()
        episodeLoadJob = null
        ++streamResolveRequest
        streamResolveJob?.cancel()
        streamResolveJob = null
        val wasSearching = searchJob?.isActive == true
        searchDebounceJob?.cancel()
        searchJob?.cancel()
        _uiState.update {
            val abandonedDetail = it.selectedAnime != null && it.episodes.isEmpty()
            it.copy(
                isLoading = if (wasSearching) false else it.isLoading,
                isLoadingEpisodes = false,
                resolvingEpisodeNumber = null,
                activeStream = null,
                availableStreams = emptyList(),
                playingEpisodeNumber = null,
                
                selectedAnime = if (abandonedDetail) null else it.selectedAnime,
                error = null
            )
        }
    }

    fun clearSelectedAnime() {
        ++episodeLoadRequest
        episodeLoadJob?.cancel()
        episodeLoadJob = null
        ++streamResolveRequest
        streamResolveJob?.cancel()
        streamResolveJob = null
        _uiState.update {
            it.copy(
                selectedAnime = null,
                episodes = emptyList(),
                isLoadingEpisodes = false,

                resolvingEpisodeNumber = null,
                playingEpisodeNumber = null,
                activeStream = null,
                availableStreams = emptyList(),
                error = null,
                notice = null
            )
        }
    }

    fun selectProvider(providerId: String) {
        val current = _uiState.value
        if (current.selectedProviderId == providerId) return
        _uiState.update { it.copy(selectedProviderId = providerId, error = null) }

        if (current.episodes.isEmpty()) {
            current.selectedAnime?.let { anime ->
                loadEpisodesFor(anime, providerId)
            }
        }
        current.playingEpisodeNumber?.let { playEpisodeNumber(it) }
    }

    fun playEpisode(episode: AnimeEpisode) {
        playEpisodeNumber(episode.number)
    }

    fun playNextEpisode() {
        val current = _uiState.value.playingEpisodeNumber ?: return
        val nextEp = _uiState.value.episodes
            .map { it.number }
            .filter { it > current }
            .minOrNull() ?: return
        playEpisodeNumber(nextEp)
    }

    fun setAudio(choice: AudioChoice) {
        AppPreferences.setAnimeAudio(getApplication<Application>(), choice.id)
        _uiState.update { it.copy(audio = choice) }
    }

    fun setQuality(choice: QualityChoice) {
        AppPreferences.setAnimeQuality(getApplication<Application>(), choice.id)
        _uiState.update { it.copy(quality = choice) }
    }

    private fun refreshHistory() {
        _uiState.update { it.copy(continueWatching = history.load()) }
    }

    fun setEpisodeWatched(number: Int, watched: Boolean) {
        val anime = _uiState.value.selectedAnime ?: return
        history.setWatched(anime, number, watched)
        _uiState.update { it.copy(watchedEpisodes = history.watchedFor(anime.anilistId), continueWatching = history.load()) }
    }

    fun onEpisodeEnded() {
        val number = _uiState.value.playingEpisodeNumber ?: return
        setEpisodeWatched(number, true)
    }

    fun removeFromHistory(entry: HistoryEntry) {
        history.remove(entry.anime.anilistId)
        refreshHistory()
    }

    fun continueWatching(entry: HistoryEntry) {
        selectAnime(entry.anime)
        playEpisodeNumber(entry.continueEpisode)
    }

    private fun toast(message: String) {
        Toast.makeText(getApplication<Application>(), message, Toast.LENGTH_LONG).show()
    }

    private fun playEpisodeNumber(episodeNumber: Int) {
        val snapshot = _uiState.value
        val anime = snapshot.selectedAnime ?: return
        val providerId = snapshot.selectedProviderId
        val audio = snapshot.audio
        val quality = snapshot.quality
        val requestId = ++streamResolveRequest

        history.recordStarted(anime, episodeNumber)
        streamResolveJob?.cancel()
        _uiState.update {
            it.copy(
                resolvingEpisodeNumber = episodeNumber,
                playingEpisodeNumber = episodeNumber,
                activeStream = null,
                availableStreams = emptyList(),
                playingMaxHeight = quality.maxHeight,
                continueWatching = history.load(),
                error = null
            )
        }

        streamResolveJob = viewModelScope.launch {
            try {

                var gotAny = false
                
                withTimeoutOrNull(60_000L) {
                repository.resolveStreamsProgressive(anime, episodeNumber, providerId).collect { batch ->
                    if (requestId != streamResolveRequest || batch.isEmpty()) return@collect
                    val merged = (_uiState.value.availableStreams + batch).distinctBy { it.url }
                    val pick = StreamPicker.choose(merged, audio, quality) ?: return@collect
                    val first = !gotAny
                    gotAny = true
                    _uiState.update {
                        val current = it.activeStream
                        val switchToWantedAudio = current != null &&
                            StreamPicker.audioOf(current) != audio &&
                            StreamPicker.audioOf(pick.stream) == audio
                        it.copy(
                            resolvingEpisodeNumber = null,
                            playingEpisodeNumber = episodeNumber,
                            availableStreams = merged,
                            activeStream = if (first || switchToWantedAudio) pick.stream else current,
                            error = null
                        )
                    }
                }
                }
                if (requestId != streamResolveRequest) return@launch
                if (!gotAny) {
                    val provName = _uiState.value.providers
                        .firstOrNull { it.id == providerId }?.name ?: providerId
                    _uiState.update {
                        it.copy(
                            resolvingEpisodeNumber = null,
                            activeStream = null,
                            availableStreams = emptyList(),
                            error = "No stream found on $provName for Episode $episodeNumber"
                        )
                    }
                } else {
                    
                    val all = _uiState.value.availableStreams
                    StreamPicker.choose(all, audio, quality)?.note?.let(::toast)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (err: Throwable) {
                if (requestId == streamResolveRequest) {
                    _uiState.update {
                        it.copy(
                            resolvingEpisodeNumber = null,
                            activeStream = null,
                            availableStreams = emptyList(),
                            error = "Could not load Episode $episodeNumber: ${err.message ?: "Unknown error"}"
                        )
                    }
                }
            }
        }
    }

    fun clearNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    fun downloadEpisode(episode: AnimeEpisode) = downloadEpisodes(listOf(episode.number))

    fun downloadEpisodes(numbers: List<Int>) {
        val snapshot = _uiState.value
        val anime = snapshot.selectedAnime ?: return
        val providerId = snapshot.selectedProviderId
        val audio = snapshot.audio
        val quality = snapshot.quality

        val queued = numbers.distinct().sorted().mapNotNull { number ->
            val label = "${anime.displayTitle} - Episode ${number.toString().padStart(2, '0')}"
            val alreadyRunning = DownloadManagerStore.items.value.any {
                it.title == label && (it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED)
            }
            if (alreadyRunning) null else {
                val taskId = DownloadManagerStore.add(label, "anilist:${anime.anilistId}", "Anime · Ep $number · ${audio.label}", anime.coverUrl)
                DownloadManagerStore.update(taskId) { it.copy(detail = "Waiting in queue") }
                QueuedDownload(taskId, label, number)
            }
        }
        if (queued.isEmpty()) {
            _uiState.update { it.copy(notice = "Already downloading.") }
            return
        }
        _uiState.update {
            it.copy(
                notice = if (queued.size == 1) "Download started: Episode ${queued.first().number} (${audio.label}). Track it in the Downloads tab."
                else "${queued.size} episodes queued (${audio.label}). Track them in the Downloads tab."
            )
        }
        DownloadService.ensureRunning(getApplication<Application>())

        viewModelScope.launch {
            for (task in queued) runDownload(anime, providerId, audio, quality, task)
        }
    }

    private suspend fun runDownload(
        anime: AnimeCatalogItem,
        providerId: String,
        audio: AudioChoice,
        quality: QualityChoice,
        task: QueuedDownload
    ) {
        DownloadManagerStore.update(task.taskId) {
            it.copy(status = DownloadStatus.DOWNLOADING, detail = "Finding a download link…")
        }
        runCatching {
            MediaToolchainUpdater.awaitReady()
            val streams = repository.resolveStreams(anime, task.number, providerId)
            if (streams.isEmpty()) error("No downloadable stream found for Episode ${task.number}")
            val ordered = if (quality == QualityChoice.FASTEST) {
                StreamPicker.fastestFirst(streams, audio)
            } else {
                StreamPicker.ordered(streams, audio, quality)
            }
            StreamPicker.choose(streams, audio, quality)?.note?.let { note ->
                DownloadManagerStore.update(task.taskId) { it.copy(detail = note) }
            }
            var lastError: Throwable? = null
            for (stream in ordered) {
                
                val qualityText = StreamPicker.heightOf(stream)?.let { "${it}p" }
                    ?: quality.maxHeight?.let { "up to ${it}p" }
                    ?: "best available"
                DownloadManagerStore.update(task.taskId) {
                    it.copy(
                        kind = "Anime · Ep ${task.number} · ${StreamPicker.audioOf(stream).label} · $qualityText",
                        detail = "Starting download…"
                    )
                }
                try {
                    return@runCatching repository.downloader.download(stream, task.label, quality.maxHeight) { p ->
                        DownloadManagerStore.progress(task.taskId, p.percent, p.etaSeconds)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    lastError = t
                }
            }
            throw lastError ?: IllegalStateException("Download failed")
        }.onSuccess { result ->
            DownloadManagerStore.completed(task.taskId, result.playUri, result.savedLocation)
        }.onFailure { err ->
            DownloadManagerStore.failed(task.taskId, err.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: "Download failed")
        }
    }

    fun selectStreamQuality(stream: VideoStreamSource) {
        _uiState.update { it.copy(activeStream = stream) }
    }

    fun closePlayer() {
        ++streamResolveRequest
        streamResolveJob?.cancel()
        streamResolveJob = null
        _uiState.update {
            it.copy(
                activeStream = null,
                availableStreams = emptyList(),
                resolvingEpisodeNumber = null,
                playingEpisodeNumber = null
            )
        }
    }
}