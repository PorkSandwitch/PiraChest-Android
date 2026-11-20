package com.pirachest.android.feature.anime.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val anime: AnimeCatalogItem,
    val lastEpisode: Int,
    val updatedAt: Long,
    val watched: Set<Int>
) {
    
    val continueEpisode: Int
        get() = if (lastEpisode in watched) lastEpisode + 1 else lastEpisode
}

class AnimeHistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("anime_history", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<HistoryEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val animeJson = o.optJSONObject("anime") ?: continue
                    val watched = buildSet {
                        val w = o.optJSONArray("watched") ?: JSONArray()
                        for (j in 0 until w.length()) add(w.optInt(j))
                    }
                    add(
                        HistoryEntry(
                            anime = animeFromJson(animeJson),
                            lastEpisode = o.optInt("lastEpisode", 1),
                            updatedAt = o.optLong("updatedAt"),
                            watched = watched
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun watchedFor(anilistId: Int): Set<Int> = load().firstOrNull { it.anime.anilistId == anilistId }?.watched ?: emptySet()

    @Synchronized
    fun recordStarted(anime: AnimeCatalogItem, episode: Int) = update(anime) { old ->
        HistoryEntry(anime, episode, System.currentTimeMillis(), old?.watched ?: emptySet())
    }

    @Synchronized
    fun setWatched(anime: AnimeCatalogItem, episode: Int, watched: Boolean) = update(anime) { old ->
        val set = (old?.watched ?: emptySet()).let { if (watched) it + episode else it - episode }
        HistoryEntry(anime, old?.lastEpisode ?: episode, System.currentTimeMillis(), set)
    }

    @Synchronized
    fun remove(anilistId: Int) {
        save(load().filterNot { it.anime.anilistId == anilistId })
    }

    private fun update(anime: AnimeCatalogItem, transform: (HistoryEntry?) -> HistoryEntry) {
        val all = load()
        val old = all.firstOrNull { it.anime.anilistId == anime.anilistId }
        val updated = transform(old)
        save((listOf(updated) + all.filterNot { it.anime.anilistId == anime.anilistId }).take(MAX_ENTRIES))
    }

    private fun save(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(JSONObject().apply {
                put("anime", animeToJson(e.anime))
                put("lastEpisode", e.lastEpisode)
                put("updatedAt", e.updatedAt)
                put("watched", JSONArray(e.watched.sorted()))
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun animeToJson(a: AnimeCatalogItem) = JSONObject().apply {
        put("anilistId", a.anilistId)
        put("malId", a.malId ?: JSONObject.NULL)
        put("titleRomaji", a.titleRomaji)
        put("titleEnglish", a.titleEnglish ?: JSONObject.NULL)
        put("coverUrl", a.coverUrl ?: JSONObject.NULL)
        put("bannerUrl", a.bannerUrl ?: JSONObject.NULL)
        put("description", a.description?.take(600) ?: JSONObject.NULL)
        put("genres", JSONArray(a.genres))
        put("format", a.format ?: JSONObject.NULL)
        put("episodesCount", a.episodesCount ?: JSONObject.NULL)
        put("status", a.status ?: JSONObject.NULL)
        put("score", a.score ?: JSONObject.NULL)
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private fun JSONObject.optIntOrNull(name: String): Int? = if (isNull(name) || !has(name)) null else optInt(name)

    private fun animeFromJson(o: JSONObject) = AnimeCatalogItem(
        anilistId = o.optInt("anilistId"),
        malId = o.optIntOrNull("malId"),
        titleRomaji = o.optString("titleRomaji"),
        titleEnglish = o.optStringOrNull("titleEnglish"),
        coverUrl = o.optStringOrNull("coverUrl"),
        bannerUrl = o.optStringOrNull("bannerUrl"),
        description = o.optStringOrNull("description"),
        genres = buildList {
            val g = o.optJSONArray("genres") ?: JSONArray()
            for (i in 0 until g.length()) add(g.optString(i))
        },
        format = o.optStringOrNull("format"),
        episodesCount = o.optIntOrNull("episodesCount"),
        status = o.optStringOrNull("status"),
        score = o.optIntOrNull("score")
    )

    private companion object {
        const val KEY = "entries"
        const val MAX_ENTRIES = 40
    }
}
