package com.jimboyo.localbeat.art

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.jimboyo.localbeat.LocalBeatApp
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

/**
 * Serves album covers as content://<package>.art/album/<albumId>, read-only.
 * Android Auto and the system media UI load artwork from their own processes,
 * so the covers have to be reachable through a provider.
 */
class ArtworkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read-only")
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != "album") throw FileNotFoundException(uri.toString())
        val app = context!!.applicationContext as LocalBeatApp
        val library = runBlocking { app.library.awaitLibrary() }
        val album = library.album(segments[1]) ?: throw FileNotFoundException(uri.toString())
        val file = app.art.file(album) ?: throw FileNotFoundException("No cover for ${album.title}")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/*"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
