package com.jimboyo.localbeat.library

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** The parsed library, saved between launches so startup doesn't re-read every file. */
object LibraryCache {
    private const val VERSION = 3 // 2: year prefers ORIGINALDATE; multi-value GENRE. 3: server songs
    private const val MAX_TRACKS = 500_000
    private const val MAX_STRING_BYTES = 1024 * 1024

    fun read(file: File): List<Track>? = runCatching {
        val fileSize = file.length()
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            if (input.readInt() != VERSION) return null
            val count = input.readInt()
            if (count < 0 || count > MAX_TRACKS || count.toLong() * 32 > fileSize) return null
            List(count) {
                Track(
                    id = input.str(fileSize), mediaStoreId = input.readLong(), path = input.str(fileSize),
                    modified = input.readLong(), size = input.readLong(), durationMs = input.readLong(),
                    dateAdded = input.readLong(), title = input.str(fileSize), artist = input.str(fileSize),
                    albumArtist = input.str(fileSize), album = input.str(fileSize), trackNumber = input.readInt(),
                    discNumber = input.readInt(), discTotal = input.readInt(), year = input.readInt(),
                    genre = input.str(fileSize), musicBrainzAlbumId = input.str(fileSize), hasEmbeddedArt = input.readBoolean(),
                    serverSongId = input.str(fileSize), serverCoverArt = input.str(fileSize),
                )
            }
        }
    }.getOrNull()

    fun write(file: File, tracks: List<Track>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
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
                    out.str(t.serverSongId); out.str(t.serverCoverArt)
                }
            }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    // Length-prefixed UTF-8 (writeUTF caps strings at 64 KB and uses modified UTF-8).
    private fun DataOutputStream.str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeInt(b.size); write(b)
    }

    private fun DataInputStream.str(maxFileSize: Long): String {
        val len = readInt()
        if (len < 0 || len > MAX_STRING_BYTES || len.toLong() > maxFileSize) {
            throw java.io.IOException("Invalid string length $len in library cache")
        }
        return String(ByteArray(len).also { readFully(it) }, Charsets.UTF_8)
    }
}
