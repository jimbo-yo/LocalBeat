package com.jimboyo.localbeat.art

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.jimboyo.localbeat.library.Album
import com.jimboyo.localbeat.library.Track
import com.jimboyo.localbeat.tags.OggTags
import java.io.File
import java.io.FileInputStream

/**
 * Album covers, pulled out of the files' embedded pictures once and kept in the
 * cache folder. File names include the source file's modified time, so a re-ripped
 * album gets its new cover.
 */
class ArtStore(private val context: Context) {
    private val dir = File(context.cacheDir, "art").apply { mkdirs() }

    /** What Android Auto, the notification and the lock screen load the cover from. */
    fun uriFor(albumId: String): Uri = Uri.parse("content://${context.packageName}.art/album/$albumId")

    /** The album's cover file, extracting it on first use. Null if no file has a picture. Blocking. */
    fun file(album: Album): File? {
        val source = album.tracks.firstOrNull { it.hasEmbeddedArt } ?: return null
        val file = File(dir, "${album.id}-${source.modified}.img")
        if (file.exists()) return file
        val bytes = runCatching { extract(source) }
            .onFailure { Log.w("LocalBeat", "Can't read cover: ${source.path}", it) }
            .getOrNull() ?: return null
        val tmp = File.createTempFile(album.id, ".tmp", dir)
        tmp.writeBytes(bytes)
        tmp.renameTo(file)
        return file
    }

    private fun extract(track: Track): ByteArray? {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.mediaStoreId)
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                if (OggTags.isOgg(ch)) return OggTags.read(ch, wantPicture = true)?.picture?.data
            }
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(pfd.fileDescriptor)
                return mmr.embeddedPicture
            } finally {
                mmr.release()
            }
        }
    }

    /** Deletes cached covers that no longer match an album in the library. */
    fun prune(albums: List<Album>) {
        val keep = albums.mapNotNull { a -> a.tracks.firstOrNull { it.hasEmbeddedArt }?.let { "${a.id}-${it.modified}.img" } }.toSet()
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }
}
