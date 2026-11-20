package com.pirachest.android

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.pirachest.android.core.downloads.DownloadManagerStore
import com.pirachest.android.core.media.MediaToolchainUpdater

class PiraChestApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        
        DownloadManagerStore.init(this)

        MediaToolchainUpdater.initialize(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(100L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false)
        .crossfade(false)
        
        .allowRgb565(true)
        .allowHardware(true)
        .build()
}
