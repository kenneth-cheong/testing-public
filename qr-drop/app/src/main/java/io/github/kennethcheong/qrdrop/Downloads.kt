package io.github.kennethcheong.qrdrop

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.IOException
import java.io.InputStream

/** Saves uploads to Downloads/QR Drop through MediaStore, so no storage permission is needed. */
class DownloadsSink(context: Context) : FileSink {
    private val resolver = context.applicationContext.contentResolver

    override fun save(name: String, mimeType: String?, body: InputStream): SavedFile {
        val type = mimeType?.takeIf { it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, type)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Couldn't create the file in Downloads")
        try {
            val bytes = resolver.openOutputStream(uri)?.use { out -> body.copyTo(out, 256 * 1024) }
                ?: throw IOException("Couldn't open the file in Downloads")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            // MediaStore renames duplicates ("photo (1).jpg"); report the name it actually used.
            val finalName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: name
            return SavedFile(finalName, bytes, uri.toString())
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e as? IOException ?: IOException(e.message, e)
        }
    }

    companion object {
        const val FOLDER = "QR Drop"
    }
}
