package com.jimboyo.localbeat.tags

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Base64
import java.util.Locale

/**
 * Vorbis comments as found in Ogg Opus and Ogg Vorbis files.
 *
 * Keys are case-insensitive by spec, so they are stored upper-cased. Values are
 * always decoded as UTF-8 (also by spec): no charset guessing, ever. That guessing
 * is what turns "Sittin’" into "Sittinâ€™" in Android's own media scanner.
 */
class VorbisComments(val vendor: String, private val fields: Map<String, List<String>>) {
    operator fun get(key: String): String? = all(key).firstOrNull()?.takeIf { it.isNotBlank() }

    fun all(key: String): List<String> = fields[key.uppercase(Locale.ROOT)].orEmpty()

    /** The first key in [keys] that has a non-blank value. */
    fun first(vararg keys: String): String? = keys.firstNotNullOfOrNull { get(it) }

    val keys: Set<String> get() = fields.keys

    /** Whether the file has an embedded picture (its data is only read on request). */
    val hasPicture: Boolean get() = "METADATA_BLOCK_PICTURE" in fields
}

/** A FLAC picture block (the payload of METADATA_BLOCK_PICTURE). */
class EmbeddedPicture(val type: Int, val mimeType: String, val data: ByteArray)

class OggTagResult(val comments: VorbisComments, val picture: EmbeddedPicture?)

object OggTags {
    private const val PICTURE_KEY = "METADATA_BLOCK_PICTURE"
    private const val FRONT_COVER = 3
    private const val MAX_COMMENTS = 10_000
    private const val MAX_FIELD = 64 * 1024 * 1024

    fun isOgg(channel: FileChannel): Boolean {
        val magic = ByteBuffer.allocate(4)
        channel.read(magic, 0)
        return magic.position() == 4 && String(magic.array(), Charsets.ISO_8859_1) == "OggS"
    }

    /**
     * Reads the comment header of the first logical stream. When [wantPicture] is
     * false, picture fields (usually most of the header's size) are seeked past
     * instead of read, which keeps library scans cheap.
     *
     * Returns null if this isn't an Ogg Opus / Ogg Vorbis file.
     */
    fun read(channel: FileChannel, wantPicture: Boolean = false): OggTagResult? {
        channel.position(0)
        val stream = PageStream(channel)
        if (!stream.nextPage()) return null

        // Packet 1 (identification header) sits alone on the first page.
        val head = ByteArray(minOf(stream.remainingInPage, 8L).toInt())
        stream.readFully(head)
        val isOpus = head.size == 8 && String(head, Charsets.ISO_8859_1) == "OpusHead"
        val isVorbis = head.size >= 7 && head[0] == 1.toByte() &&
            String(head, 1, 6, Charsets.ISO_8859_1) == "vorbis"
        if (!isOpus && !isVorbis) return null
        stream.skipRestOfPage()

        // Packet 2 (comment header) starts on the next page of the same stream and
        // may span many pages. Its fields are length-prefixed, so page bodies can
        // be read as one continuous stream.
        if (!stream.nextPage()) return null
        val magic = ByteArray(if (isOpus) 8 else 7)
        stream.readFully(magic)
        val ok = if (isOpus) String(magic, Charsets.ISO_8859_1) == "OpusTags"
        else magic[0] == 3.toByte() && String(magic, 1, 6, Charsets.ISO_8859_1) == "vorbis"
        if (!ok) return null

        val vendor = String(stream.readBytes(stream.readLength()), Charsets.UTF_8)
        val count = stream.readLength(MAX_COMMENTS)
        val fields = LinkedHashMap<String, MutableList<String>>()
        val pictures = ArrayList<String>()
        repeat(count) {
            val length = stream.readLength()
            // Read just enough to find the key; keys are short ASCII.
            val prefix = stream.readBytes(minOf(length, 64))
            val eq = prefix.indexOf('='.code.toByte())
            if (eq <= 0) {
                stream.skip((length - prefix.size).toLong())
                return@repeat
            }
            val key = String(prefix, 0, eq, Charsets.US_ASCII).uppercase(Locale.ROOT)
            val rest = length - prefix.size
            if (key == PICTURE_KEY && !wantPicture) {
                stream.skip(rest.toLong())
                fields.getOrPut(key) { ArrayList() }.add("")
                return@repeat
            }
            val valueBytes = ByteArray(length - eq - 1)
            System.arraycopy(prefix, eq + 1, valueBytes, 0, prefix.size - eq - 1)
            stream.readFully(valueBytes, prefix.size - eq - 1, rest)
            val value = String(valueBytes, Charsets.UTF_8)
            if (key == PICTURE_KEY) {
                pictures.add(value)
                fields.getOrPut(key) { ArrayList() }.add("") // presence only; see [hasPicture]
            } else {
                fields.getOrPut(key) { ArrayList() }.add(value)
            }
        }

        val picture = if (wantPicture) pickPicture(pictures) else null
        return OggTagResult(VorbisComments(vendor, fields), picture)
    }

    private fun pickPicture(encoded: List<String>): EmbeddedPicture? {
        val decoded = encoded.mapNotNull { runCatching { parsePictureBlock(Base64.getMimeDecoder().decode(it)) }.getOrNull() }
        return decoded.firstOrNull { it.type == FRONT_COVER } ?: decoded.firstOrNull()
    }

    /** Parses a FLAC METADATA_BLOCK_PICTURE (all integers big-endian). */
    fun parsePictureBlock(block: ByteArray): EmbeddedPicture {
        val buf = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN)
        val type = buf.int
        val mime = ByteArray(buf.int).also { buf.get(it) }
        val description = buf.int
        buf.position(buf.position() + description)
        buf.position(buf.position() + 16) // width, height, depth, colours
        val data = ByteArray(buf.int).also { buf.get(it) }
        return EmbeddedPicture(type, String(mime, Charsets.US_ASCII), data)
    }

    /** The bodies of the Ogg pages of one logical stream, read as a continuous byte stream. */
    private class PageStream(private val channel: FileChannel) {
        private var serial: Int? = null
        var remainingInPage = 0L
            private set
        private val header = ByteBuffer.allocate(27).order(ByteOrder.LITTLE_ENDIAN)
        private val lacing = ByteBuffer.allocate(255)

        /** Moves to the next page of our stream. Returns false at end of file. */
        fun nextPage(): Boolean {
            while (true) {
                header.clear()
                if (!fill(header)) return false
                if (header.getInt(0) != 0x5367674f) throw IOException("Lost Ogg page sync") // "OggS"
                val pageSerial = header.getInt(14)
                val segments = header.get(26).toInt() and 0xff
                lacing.clear().limit(segments)
                if (!fill(lacing)) return false
                var size = 0L
                for (i in 0 until segments) size += lacing.get(i).toInt() and 0xff
                if (serial == null) serial = pageSerial
                if (pageSerial == serial) {
                    remainingInPage = size
                    return true
                }
                channel.position(channel.position() + size)
            }
        }

        fun skipRestOfPage() {
            channel.position(channel.position() + remainingInPage)
            remainingInPage = 0
        }

        fun readFully(dst: ByteArray, offset: Int = 0, length: Int = dst.size - offset) {
            var done = 0
            while (done < length) {
                if (remainingInPage == 0L && !nextPage()) throw EOFException()
                val n = minOf(remainingInPage, (length - done).toLong()).toInt()
                val buf = ByteBuffer.wrap(dst, offset + done, n)
                if (!fill(buf)) throw EOFException()
                done += n
                remainingInPage -= n
            }
        }

        fun readBytes(n: Int): ByteArray = ByteArray(n).also { readFully(it) }

        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                if (remainingInPage == 0L && !nextPage()) throw EOFException()
                val step = minOf(remainingInPage, left)
                channel.position(channel.position() + step)
                remainingInPage -= step
                left -= step
            }
        }

        /** A little-endian u32 length, sanity-checked against corrupt files. */
        fun readLength(max: Int = MAX_FIELD): Int {
            val b = readBytes(4)
            val v = (b[0].toLong() and 0xff) or ((b[1].toLong() and 0xff) shl 8) or
                ((b[2].toLong() and 0xff) shl 16) or ((b[3].toLong() and 0xff) shl 24)
            if (v > max) throw IOException("Implausible length $v in Ogg comment header")
            return v.toInt()
        }

        private fun fill(buf: ByteBuffer): Boolean {
            while (buf.hasRemaining()) {
                if (channel.read(buf) < 0) return false
            }
            return true
        }
    }
}
