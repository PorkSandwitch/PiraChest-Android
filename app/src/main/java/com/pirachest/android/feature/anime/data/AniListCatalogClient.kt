package com.pirachest.android.feature.anime.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Calendar

class AniListCatalogClient(private val client: OkHttpClient) {

    private val endpoint = "https://graphql.anilist.co"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun getHomeSections(): AnimeHomeSections {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val season = when (cal.get(Calendar.MONTH)) {
            in 0..2 -> "WINTER"
            in 3..5 -> "SPRING"
            in 6..8 -> "SUMMER"
            else -> "FALL"
        }

        val query = """
            query (${"$"}season: MediaSeason, ${"$"}seasonYear: Int) {
              trending: Page(page: 1, perPage: 16) {
                media(type: ANIME, sort: TRENDING_DESC, isAdult: false) { ...mediaFields }
              }
              popularSeason: Page(page: 1, perPage: 16) {
                media(type: ANIME, season: ${"$"}season, seasonYear: ${"$"}seasonYear, sort: POPULARITY_DESC, isAdult: false) { ...mediaFields }
              }
              allTimePopular: Page(page: 1, perPage: 16) {
                media(type: ANIME, sort: POPULARITY_DESC, isAdult: false) { ...mediaFields }
              }
              topRated: Page(page: 1, perPage: 16) {
                media(type: ANIME, sort: SCORE_DESC, isAdult: false) { ...mediaFields }
              }
            }
            fragment mediaFields on Media {
              id
              idMal
              title { romaji english }
              coverImage { extraLarge large }
              bannerImage
              description(asHtml: false)
              genres
              format
              episodes
              nextAiringEpisode { episode }
              status
              averageScore
            }
        """.trimIndent()

        val variables = JSONObject().apply {
            put("season", season)
            put("seasonYear", year)
        }

        val root = executeGraphQl(query, variables)
        val data = root.optJSONObject("data") ?: return AnimeHomeSections()

        return AnimeHomeSections(
            trending = parseMediaList(data.optJSONObject("trending")?.optJSONArray("media")),
            popularSeason = parseMediaList(data.optJSONObject("popularSeason")?.optJSONArray("media")),
            allTimePopular = parseMediaList(data.optJSONObject("allTimePopular")?.optJSONArray("media")),
            topRated = parseMediaList(data.optJSONObject("topRated")?.optJSONArray("media"))
        )
    }

    suspend fun searchAnime(search: String, page: Int = 1, perPage: Int = 25): List<AnimeCatalogItem> {
        val query = """
            query (${"$"}search: String, ${"$"}page: Int, ${"$"}perPage: Int) {
              Page(page: ${"$"}page, perPage: ${"$"}perPage) {
                media(type: ANIME, search: ${"$"}search, sort: SEARCH_MATCH, isAdult: false) {
                  id
                  idMal
                  title { romaji english }
                  coverImage { extraLarge large }
                  bannerImage
                  description(asHtml: false)
                  genres
                  format
                  episodes
                  nextAiringEpisode { episode }
                  status
                  averageScore
                }
              }
            }
        """.trimIndent()

        val variables = JSONObject().apply {
            put("search", search)
            put("page", page)
            put("perPage", perPage)
        }
        val root = executeGraphQl(query, variables)
        val mediaArray = root.optJSONObject("data")
            ?.optJSONObject("Page")
            ?.optJSONArray("media")
        return parseMediaList(mediaArray)
    }

    private fun executeGraphQl(query: String, variables: JSONObject): JSONObject {
        val payload = JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }.toString()

        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .build()

        val body = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("AniList HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        return JSONObject(body)
    }

    private fun parseMediaList(mediaArray: org.json.JSONArray?): List<AnimeCatalogItem> {
        if (mediaArray == null) return emptyList()
        return buildList {
            for (i in 0 until mediaArray.length()) {
                val m = mediaArray.getJSONObject(i)
                val titleObj = m.optJSONObject("title")
                val coverObj = m.optJSONObject("coverImage")
                val romaji = titleObj?.optString("romaji").orEmpty()
                val english = titleObj?.optString("english")?.takeIf { it != "null" && it.isNotBlank() }
                val cover = coverObj?.optString("large")?.takeIf { it.startsWith("http") }
                    ?: coverObj?.optString("extraLarge")?.takeIf { it.startsWith("http") }
                val banner = m.optString("bannerImage").takeIf { it.startsWith("http") }
                val rawDesc = m.optString("description").takeIf { it != "null" && it.isNotBlank() }
                val cleanDesc = rawDesc?.replace(Regex("<[^>]*>"), "")?.trim()

                val genresArr = m.optJSONArray("genres")
                val genres = buildList {
                    if (genresArr != null) {
                        for (g in 0 until genresArr.length()) add(genresArr.getString(g))
                    }
                }

                val totalEps = if (m.isNull("episodes")) null else m.optInt("episodes").takeIf { it > 0 }
                val nextEp = m.optJSONObject("nextAiringEpisode")?.optInt("episode", 0)?.takeIf { it > 1 }
                val effectiveEpisodes = totalEps ?: nextEp?.minus(1)

                if (romaji.isNotBlank()) {
                    add(
                        AnimeCatalogItem(
                            anilistId = m.getInt("id"),
                            malId = if (m.isNull("idMal")) null else m.optInt("idMal"),
                            titleRomaji = romaji,
                            titleEnglish = english,
                            coverUrl = cover,
                            bannerUrl = banner,
                            description = cleanDesc,
                            genres = genres,
                            format = m.optString("format").takeIf { it != "null" },
                            episodesCount = effectiveEpisodes,
                            status = m.optString("status").takeIf { it != "null" },
                            score = if (m.isNull("averageScore")) null else m.optInt("averageScore")
                        )
                    )
                }
            }
        }
    }
}