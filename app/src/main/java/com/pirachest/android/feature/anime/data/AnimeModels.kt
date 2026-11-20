package com.pirachest.android.feature.anime.data

data class AnimeCatalogItem(
    val anilistId: Int,
    val malId: Int?,
    val titleRomaji: String,
    val titleEnglish: String?,
    val coverUrl: String?,
    val bannerUrl: String?,
    val description: String?,
    val genres: List<String> = emptyList(),
    val format: String?,
    val episodesCount: Int?,
    val status: String?,
    val score: Int?
) {
    val displayTitle: String
        get() = titleEnglish?.takeIf { it.isNotBlank() } ?: titleRomaji
}

data class AnimeHomeSections(
    val trending: List<AnimeCatalogItem> = emptyList(),
    val popularSeason: List<AnimeCatalogItem> = emptyList(),
    val allTimePopular: List<AnimeCatalogItem> = emptyList(),
    val topRated: List<AnimeCatalogItem> = emptyList()
)

data class AnimeEpisode(
    val number: Int,
    val title: String,
    val animeTitle: String,
    val providerId: String? = null,
    val audio: String? = null,
    val sourceNumber: Int = number
)

data class VideoStreamSource(
    val providerName: String,
    val url: String,
    val quality: String,
    val isHls: Boolean,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleTrack> = emptyList()
)

data class SubtitleTrack(
    val url: String,
    val language: String,
    val label: String = language,
    val mimeType: String? = null,
    val isDefault: Boolean = false
)

interface AnimeStreamProvider {
    val id: String
    val name: String
    suspend fun discoverEpisodes(animeTitles: List<String>, animeTitle: String): List<AnimeEpisode> = emptyList()
    suspend fun resolveEpisodeStreams(animeTitles: List<String>, episodeNumber: Int): List<VideoStreamSource>
}
