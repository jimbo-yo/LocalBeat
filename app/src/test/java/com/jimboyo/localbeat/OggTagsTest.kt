package com.jimboyo.localbeat

import com.jimboyo.localbeat.tags.OggTags
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class OggTagsTest {

    @Test
    fun readsUtf8AndCaseInsensitiveKeysAcrossPages() {
        val cover = ByteArray(200_000) { (it % 251).toByte() } // forces the header over many pages
        val file = writeOpus(
            listOf(
                "title=Sittin’ on Top of the World", // cyanrip style lowercase key
                "ARTIST=Lenny Kravitz",
                "TRACKNUMBER=01",
                "METADATA_BLOCK_PICTURE=" + Base64.getEncoder().encodeToString(pictureBlock(4, ByteArray(10))),
                "METADATA_BLOCK_PICTURE=" + Base64.getEncoder().encodeToString(pictureBlock(3, cover)),
                "Album=Let Love Rule",
            )
        )
        RandomAccessFile(file, "r").channel.use { ch ->
            val fast = OggTags.read(ch)!!
            assertEquals("Sittin’ on Top of the World", fast.comments["TITLE"])
            assertEquals("Sittin’ on Top of the World", fast.comments["title"])
            assertEquals("Let Love Rule", fast.comments["album"])
            assertNull(fast.picture)

            val full = OggTags.read(ch, wantPicture = true)!!
            assertEquals(3, full.picture!!.type)
            assertArrayEquals(cover, full.picture!!.data)
            assertEquals("Let Love Rule", full.comments["ALBUM"])
        }
    }

    @Test
    fun rejectsNonOgg() {
        val f = File.createTempFile("not", ".opus").apply { writeText("ID3 hello"); deleteOnExit() }
        RandomAccessFile(f, "r").channel.use { assertNull(OggTags.read(it)) }
    }

    /** Every Opus file in the real phone library (on this laptop) must parse. Older
     *  fre:ac-era rips have no embedded art; files that declare a picture must decode one. */
    @Test
    fun realLibrary() {
        val root = File(System.getProperty("user.home"), "Music/phone")
        assumeTrue(root.isDirectory)
        val files = root.walkTopDown().filter { it.extension == "opus" }.toList()
        for (f in files) {
            RandomAccessFile(f, "r").channel.use { ch ->
                val r = OggTags.read(ch, wantPicture = true)
                assertNotNull("unreadable: $f", r)
                assertNotNull("no title: $f", r!!.comments["title"])
                if (r.comments.hasPicture) assertNotNull("bad cover: $f", r.picture)
            }
        }
        val sittin = files.firstOrNull { "Sittin" in it.name }
        if (sittin != null) RandomAccessFile(sittin, "r").channel.use {
            assertEquals("Sittin’ on Top of the World", OggTags.read(it)!!.comments["title"])
        }
        println("Parsed ${files.size} real Opus files")
    }

    // --- building test files ---

    private fun pictureBlock(type: Int, data: ByteArray): ByteArray {
        val mime = "image/jpeg".toByteArray()
        return ByteBuffer.allocate(4 + 4 + mime.size + 4 + 16 + 4 + data.size).apply {
            putInt(type); putInt(mime.size); put(mime); putInt(0)
            putInt(500); putInt(500); putInt(24); putInt(0)
            putInt(data.size); put(data)
        }.array()
    }

    private fun writeOpus(comments: List<String>): File {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 2, 0x38, 1, 0x44, 0xac.toByte(), 0, 0, 0, 0, 0)
        val tags = ByteArrayOutputStream().apply {
            write("OpusTags".toByteArray())
            le(this, 4); write("test".toByteArray())
            le(this, comments.size)
            for (c in comments) { val b = c.toByteArray(); le(this, b.size); write(b) }
        }.toByteArray()
        val out = ByteArrayOutputStream()
        var seq = 0
        writePackets(out, head, 1, seq++)
        // An unrelated stream's page in between, which must be skipped.
        writePage(out, byteArrayOf(9, 9, 9), 99, 0, intArrayOf(3))
        seq = writePackets(out, tags, 1, seq)
        return File.createTempFile("test", ".opus").apply { writeBytes(out.toByteArray()); deleteOnExit() }
    }

    /** Splits one packet into pages of at most 255 segments. Returns the next sequence number. */
    private fun writePackets(out: ByteArrayOutputStream, packet: ByteArray, serial: Int, firstSeq: Int): Int {
        var seq = firstSeq
        var offset = 0
        while (true) {
            val lacing = ArrayList<Int>()
            var size = 0
            var ended = false
            while (lacing.size < 255) {
                val n = minOf(255, packet.size - offset - size)
                lacing.add(n); size += n
                if (n < 255) { ended = true; break }
            }
            writePage(out, packet.copyOfRange(offset, offset + size), serial, seq++, lacing.toIntArray())
            offset += size
            if (ended) return seq
        }
    }

    private fun writePage(out: ByteArrayOutputStream, body: ByteArray, serial: Int, seq: Int, lacing: IntArray) {
        val h = ByteBuffer.allocate(27 + lacing.size).order(ByteOrder.LITTLE_ENDIAN)
        h.put("OggS".toByteArray()); h.put(0); h.put(0); h.putLong(0)
        h.putInt(serial); h.putInt(seq); h.putInt(0); h.put(lacing.size.toByte())
        lacing.forEach { h.put(it.toByte()) }
        out.write(h.array()); out.write(body)
    }

    private fun le(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xff); out.write((v shr 8) and 0xff); out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff)
    }
}
