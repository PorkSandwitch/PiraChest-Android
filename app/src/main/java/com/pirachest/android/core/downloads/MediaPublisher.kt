package com.pirachest.android.core.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.pirachest.android.core.preferences.AppPreferences
import java.io.File

data class PublishedMedia(val uri: String, val location: String)

object MediaPublisher {
    private fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "vtt" -> "text/vtt"
        "srt" -> "application/x-subrip"
        "ass", "ssa" -> "text/plain"
        else -> "video/mp4"
    }

    fun exists(context: Context, uriOrPath: String): Boolean {
        if (!uriOrPath.startsWith("content://")) return File(uriOrPath).exists()
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(uriOrPath), "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    fun delete(context: Context, uriOrPath: String): Boolean {
        return runCatching {
            if (!uriOrPath.startsWith("content://")) {
                val f = File(uriOrPath)
                !f.exists() || f.delete()
            } else {
                val uri = Uri.parse(uriOrPath)
                val removed = if (android.provider.DocumentsContract.isDocumentUri(context, uri)) {
                    android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
                } else {
                    context.contentResolver.delete(uri, null, null) > 0
                }
                removed || !exists(context, uriOrPath)
            }
        }.getOrDefault(false)
    }

    fun publish(context: Context, source: File): PublishedMedia {
        val customTree = AppPreferences.downloadTreeUri(context)?.let(Uri::parse)
        if (customTree != null) {
            val tree = DocumentFile.fromTreeUri(context, customTree)
                ?: error("The selected download folder is no longer available. Choose it again in Settings.")
            val mimeType = mimeFor(source.extension)
            val destination = tree.createFile(mimeType, source.name)
                ?: error("Could not create the download in the selected folder.")
            try {
                context.contentResolver.openOutputStream(destination.uri, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Could not write to the selected folder.")
                return PublishedMedia(destination.uri.toString(), "Selected folder/${source.name}")
            } catch (error: Throwable) {
                destination.delete()
                throw error
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val mimeType = mimeFor(source.extension)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PiraChest")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val destination = resolver.insert(collection, values)
                ?: error("Android could not create the file in Downloads/PiraChest.")
            try {
                resolver.openOutputStream(destination, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Could not open the Downloads folder for writing.")
                val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(destination, ready, null, null)
                return PublishedMedia(destination.toString(), "Internal storage/Download/PiraChest/${source.name}")
            } catch (error: Throwable) {
                resolver.delete(destination, null, null)
                throw error
            }
        }

        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PiraChest")
        if (!publicDir.exists() && !publicDir.mkdirs()) error("Could not create Downloads/PiraChest.")
        val destination = File(publicDir, source.name)
        source.copyTo(destination, overwrite = true)
        return PublishedMedia(destination.absolutePath, destination.absolutePath)
    }
}
