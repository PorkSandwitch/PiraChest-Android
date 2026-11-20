package com.pirachest.android.core.downloads

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object DownloadHistoryStorage {
    private const val FILE_NAME = "download_history.json"
    private const val MAX_ITEMS = 200

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun save(context: Context, items: List<DownloadItem>) {
        val array = JSONArray()
        items
            .filter { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED }
            .take(MAX_ITEMS)
            .forEach { item ->
                array.put(JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("source", item.source)
                    put("kind", item.kind)
                    put("thumbnailUrl", item.thumbnailUrl ?: JSONObject.NULL)
                    put("status", item.status.name)
                    put("detail", item.detail)
                    put("filePath", item.filePath ?: JSONObject.NULL)
                    put("savedLocation", item.savedLocation ?: JSONObject.NULL)
                })
            }
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file(context))) {
            file(context).writeText(array.toString())
            tmp.delete()
        }
    }

    @Synchronized
    fun load(context: Context): List<DownloadItem> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(f.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val status = runCatching { DownloadStatus.valueOf(o.optString("status")) }
                        .getOrDefault(DownloadStatus.FAILED)
                    add(
                        DownloadItem(
                            id = o.optString("id"),
                            title = o.optString("title"),
                            source = o.optString("source"),
                            kind = o.optString("kind"),
                            thumbnailUrl = o.optString("thumbnailUrl").takeIf { it.isNotBlank() && it != "null" },
                            progress = if (status == DownloadStatus.COMPLETED) 100f else 0f,
                            status = status,
                            detail = o.optString("detail"),
                            filePath = o.optString("filePath").takeIf { it.isNotBlank() && it != "null" },
                            savedLocation = o.optString("savedLocation").takeIf { it.isNotBlank() && it != "null" }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
