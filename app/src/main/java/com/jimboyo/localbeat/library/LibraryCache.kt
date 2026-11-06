package com.jimboyo.localbeat.library

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** The parsed library, saved between launches so startup doesn't re-read every file. */
object LibraryCache {
    private const val VERSION = 2 // 2: year prefers ORIGINALDATE; multi-value GENRE

    fun read(file: File): List<Track>? = runCatching {
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            if (input.readInt() != VERSION) return null
            List(input.readInt()) {
                Track(
                    id = input.str(), mediaStoreId = input.readLong(), path = input.str(),
                    modified = input.readLong(), size = input.readLong(), durationMs = input.readLong(),
                    dateAdded = input.readLong(), title = input.str(), artist = input.str(),
                    albumArtist = input.str(), album = input.str(), trackNumber = input.readInt(),
                    discNumber = input.readInt(), discTotal = input.readInt(), year = input.readInt(),
                    genre = input.str(), musicBrainzAlbumId = input.str(), hasEmbeddedArt = input.readBoolean(),
                )
            }
        }
    }.getOrNull()

    fun write(file: File, tracks: List<Track>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { out ->
            out.writeInt(VERSION)
            out.writeInt(tracks.size)
            for (t in tracks) {
                out.str(t.id); out.writeLong(t.mediaStoreId); out.str(t.path)
                out.writeLong(t.modified); out.writeLong(t.size); out.writeLong(t.durationMs)
                out.writeLong(t.dateAdded); out.str(t.title); out.str(t.artist)
                out.str(t.albumArtist); out.str(t.album); out.writeInt(t.trackNumber)
                out.writeInt(t.discNumber); out.writeInt(t.discTotal); out.writeInt(t.year)
                out.str(t.genre); out.str(t.musicBrainzAlbumId); out.writeBoolean(t.hasEmbeddedArt)
            }
        }
        tmp.renameTo(file)
    }

    // Length-prefixed UTF-8 (writeUTF caps strings at 64 KB and uses modified UTF-8).
    private fun DataOutputStream.str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeInt(b.size); write(b)
    }

    private fun DataInputStream.str(): String = String(ByteArray(readInt()).also { readFully(it) }, Charsets.UTF_8)
}
