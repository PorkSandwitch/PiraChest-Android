package com.pirachest.android.core.preferences

import android.content.Context

object AppPreferences {
    private const val FILE = "pirachest_preferences"
    private const val THEME_ID = "theme_id"
    private const val THEME_MODE = "theme_mode"
    private const val DOWNLOAD_TREE_URI = "download_tree_uri"
    private const val SOFTWARE_DECODING = "software_decoding"
    private const val YTDLP_LAST_CHECK = "ytdlp_last_update_check"

    fun themeId(context: Context): String = prefs(context).getString(THEME_ID, "desktop_dark") ?: "desktop_dark"
    fun themeMode(context: Context): String = prefs(context).getString(THEME_MODE, "system") ?: "system"
    fun downloadTreeUri(context: Context): String? = prefs(context).getString(DOWNLOAD_TREE_URI, null)

    fun softwareDecoding(context: Context): Boolean = prefs(context).getBoolean(SOFTWARE_DECODING, false)
    fun ytDlpLastCheck(context: Context): Long = prefs(context).getLong(YTDLP_LAST_CHECK, 0L)

    fun setThemeId(context: Context, value: String) = prefs(context).edit().putString(THEME_ID, value).apply()
    fun setThemeMode(context: Context, value: String) = prefs(context).edit().putString(THEME_MODE, value).apply()
    fun setDownloadTreeUri(context: Context, value: String?) = prefs(context).edit().putString(DOWNLOAD_TREE_URI, value).apply()

    fun setSoftwareDecoding(context: Context, value: Boolean) = prefs(context).edit().putBoolean(SOFTWARE_DECODING, value).apply()
    fun setYtDlpLastCheck(context: Context, value: Long) = prefs(context).edit().putLong(YTDLP_LAST_CHECK, value).apply()

    fun playbackPosition(context: Context, key: String): Long = prefs(context).getLong("pos_$key", 0L)
    fun setPlaybackPosition(context: Context, key: String, ms: Long) =
        prefs(context).edit().putLong("pos_$key", ms).apply()

    fun animeAudio(context: Context): String = prefs(context).getString("anime_audio", "sub") ?: "sub"
    fun animeQuality(context: Context): String = prefs(context).getString("anime_quality", "best") ?: "best"
    fun setAnimeAudio(context: Context, value: String) = prefs(context).edit().putString("anime_audio", value).apply()
    fun setAnimeQuality(context: Context, value: String) = prefs(context).edit().putString("anime_quality", value).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
