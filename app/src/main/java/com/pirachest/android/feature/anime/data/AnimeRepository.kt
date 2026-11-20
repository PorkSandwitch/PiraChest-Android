package com.pirachest.android.feature.anime.data

import android.content.Context
import android.util.Log
import com.pirachest.android.feature.anime.data.providers.AnimeGGStreamProvider
import com.pirachest.android.feature.anime.data.providers.AnipubStreamProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AnimeRepository(context: Context) {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val catalog = AniListCatalogClient(okHttpClient)
    val downloader = AnimeDownloader(context.applicationContext)

    val streamProviders: List<AnimeStreamProvider> = listOf(
        AnimeGGStreamProvider(context.applicationContext, okHttpClient),
        AnipubStreamProvider(context.applicationContext, okHttpClient),
    )

    suspend fun getHomeSections(): Result<AnimeHomeSections> = withContext(Dispatchers.IO) {
        runCatching { catalog.getHomeSections() }
    }

    suspend fun searchAnime(query: String): Result<List<AnimeCatalogItem>> = withContext(Dispatchers.IO) {
        runCatching { catalog.searchAnime(query) }
    }

    suspend fun getEpisodes(
        anime: AnimeCatalogItem,
        preferredProviderId: String? = null
    ): List<AnimeEpisode> {
        val count = anime.episodesCount ?: 0
        if (count > 0) {
            return (1..count).map { num ->
                AnimeEpisode(
                    number = num,
                    title = "Episode $num",
                    animeTitle = anime.displayTitle,
                    providerId = "anilist",
                    sourceNumber = num
                )
            }
        }

        return withContext(Dispatchers.IO) {
            val titles = listOfNotNull(anime.titleEnglish, anime.titleRomaji)
                .filter { it.isNotBlank() }
                .distinct()

            val targets = if (preferredProviderId.isNullOrBlank() || preferredProviderId == "auto") {
                streamProviders
            } else {
                streamProviders.filter { it.id == preferredProviderId }
            }

            supervisorScope {
                targets.map { provider ->
                    async {
                        runCatching {
                            provider.discoverEpisodes(titles, anime.displayTitle)
                        }.onFailure {
                            Log.e("AnimeRepo", "${provider.name} episode discovery failed", it)
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }.distinctBy { it.number }
                .sortedBy { it.number }
        }
    }

    fun buildEpisodes(anime: AnimeCatalogItem): List<AnimeEpisode> {
        val count = anime.episodesCount ?: 0
        if (count <= 0) return emptyList()
        return (1..count).map { num ->
            AnimeEpisode(
                number = num,
                title = "Episode $num",
                animeTitle = anime.displayTitle,
                providerId = "anilist",
                sourceNumber = num
            )
        }
    }

    fun resolveStreamsProgressive(
        anime: AnimeCatalogItem,
        episodeNumber: Int,
        preferredProviderId: String? = null
    ): Flow<List<VideoStreamSource>> = channelFlow {
        val baseTitles = listOfNotNull(anime.titleEnglish, anime.titleRomaji).filter { it.isNotBlank() }.distinct()
        val titleCandidates = buildList {
            anime.malId?.let { add("mal:$it") }
            addAll(baseTitles)
        }
        val targets = if (preferredProviderId.isNullOrBlank() || preferredProviderId == "auto") {
            streamProviders
        } else {
            streamProviders.filter { it.id == preferredProviderId }
        }
        supervisorScope {
            targets.forEach { provider ->
                launch(Dispatchers.IO) {
                    val streams = runCatching { provider.resolveEpisodeStreams(titleCandidates, episodeNumber) }
                        .onFailure { Log.e("AnimeRepo", "${provider.name} failed for ep $episodeNumber", it) }
                        .getOrDefault(emptyList())
                    send(streams)
                }
            }
        }
    }

    suspend fun resolveStreams(
        anime: AnimeCatalogItem,
        episodeNumber: Int,
        preferredProviderId: String? = null
    ): List<VideoStreamSource> = withContext(Dispatchers.IO) {
        val baseTitles = listOfNotNull(anime.titleEnglish, anime.titleRomaji)
            .filter { it.isNotBlank() }
            .distinct()

        val titleCandidates = buildList {
            anime.malId?.let { add("mal:$it") }
            addAll(baseTitles)
        }

        val targets = if (preferredProviderId.isNullOrBlank() || preferredProviderId == "auto") {
            streamProviders
        } else {
            streamProviders.filter { it.id == preferredProviderId }
        }

        supervisorScope {
            targets.map { provider ->
                async {
                    runCatching {
                        provider.resolveEpisodeStreams(titleCandidates, episodeNumber)
                    }.onFailure {
                        Log.e("AnimeRepo", "${provider.name} failed for ep $episodeNumber", it)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
    }
}