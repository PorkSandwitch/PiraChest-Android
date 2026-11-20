package com.pirachest.android.core.downloads

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED }

data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val source: String,
    val kind: String,
    val thumbnailUrl: String? = null,
    val progress: Float = 0f,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val detail: String = "Waiting to start",
    
    val filePath: String? = null,
    val savedLocation: String? = null
)

object DownloadManagerStore {
    private const val TAG = "DownloadStore"
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items = _items.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var appContext: Context? = null

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val restored = DownloadHistoryStorage.load(context.applicationContext).map { item ->
            
            if (item.status == DownloadStatus.QUEUED || item.status == DownloadStatus.DOWNLOADING) {
                item.copy(status = DownloadStatus.FAILED, detail = "Interrupted because the app was closed. Start it again.")
            } else item
        }
        _items.value = restored + _items.value
    }

    private fun persist() {
        val ctx = appContext ?: return
        val snapshot = _items.value
        ioScope.launch {
            runCatching { DownloadHistoryStorage.save(ctx, snapshot) }
                .onFailure { Log.w(TAG, "Could not save download history", it) }
        }
    }

    @Synchronized
    fun add(title: String, source: String, kind: String, thumbnailUrl: String? = null): String {
        val item = DownloadItem(title = title, source = source, kind = kind, thumbnailUrl = thumbnailUrl)
        _items.value = listOf(item) + _items.value
        return item.id
    }

    @Synchronized
    fun update(id: String, transform: (DownloadItem) -> DownloadItem) {
        _items.value = _items.value.map { if (it.id == id) transform(it) else it }
    }

    fun started(id: String) = update(id) {
        it.copy(status = DownloadStatus.DOWNLOADING, detail = "Starting download…")
    }

    fun progress(id: String, percent: Float, etaSeconds: Long) = update(id) {
        it.copy(
            status = DownloadStatus.DOWNLOADING,
            progress = percent.coerceIn(0f, 100f),
            detail = if (etaSeconds > 0) "About ${etaSeconds}s remaining" else "Downloading…"
        )
    }

    fun completed(id: String, path: String, savedLocation: String) {
        update(id) {
            it.copy(status = DownloadStatus.COMPLETED, progress = 100f, detail = "Saved to $savedLocation", filePath = path, savedLocation = savedLocation)
        }
        persist()
    }

    fun failed(id: String, reason: String) {
        update(id) { it.copy(status = DownloadStatus.FAILED, detail = reason) }
        persist()
    }

    @Synchronized
    fun remove(id: String) {
        _items.value = _items.value.filter { it.id != id }
        persist()
    }

    @Synchronized
    fun clearFinished() {
        _items.value = _items.value.filter {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
        }
        persist()
    }
}
