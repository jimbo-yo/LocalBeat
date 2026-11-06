package com.jimboyo.localbeat

import com.jimboyo.localbeat.library.Library
import com.jimboyo.localbeat.library.Track
import com.jimboyo.localbeat.library.TrackTags
import com.jimboyo.localbeat.playlists.M3u
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryTest {
    private val root = "/storage/emulated/0/Music/"

    private fun track(rel: String, title: String, artist: String, album: String, year: Int = 0, genre: String = "", n: Int = 1) = Track(
        id = Track.idForPath(root + rel), mediaStoreId = 1, path = root + rel, modified = 0, size = 0, durationMs = 180_000,
        dateAdded = 0, title = title, artist = artist, albumArtist = artist, album = album, trackNumber = n, discNumber = 1,
        discTotal = 1, year = year, genre = genre, musicBrainzAlbumId = "", hasEmbeddedArt = false,
    )

    private val lib = Library(
        listOf(
            track("Guns N' Roses - Appetite/01 - Welcome.opus", "Welcome to the Jungle", "Guns N' Roses", "Appetite for Destruction", 1987, "Rock"),
            track("Guns N’ Roses/Use Your Illusion I/01. Right Next Door.opus", "Right Next Door to Hell", "Guns N’ Roses", "Use Your Illusion I", 1991, "rock; hard rock"),
            track("Led Zeppelin/Led Zeppelin/01. Good Times.opus", "Good Times Bad Times", "Led Zeppelin", "Led Zeppelin", 1969),
            track("loose.mp3", "Loose", "", ""),
        )
    )

    @Test
    fun foldsApostrophesIntoOneArtist() {
        val gnr = lib.artists.filter { it.name.startsWith("Guns") }
        assertEquals(1, gnr.size)
        assertEquals(2, gnr[0].albums.size)
    }

    @Test
    fun genresAndDecades() {
        assertEquals(listOf("Hard Rock", "Rock"), lib.genres.map { it.name })
        assertEquals(2, lib.genres.first { it.name == "Rock" }.albums.size)
        assertEquals(listOf("1960s", "1980s", "1990s"), lib.decades.map { it.name })
    }

    @Test
    fun prefersOriginalYear() {
        assertEquals(1969, TrackTags.year("1969-01-12"))
        assertEquals(7, TrackTags.leadingInt("07/12"))
        assertEquals("Radiohead - Airbag", TrackTags.titleFromFileName("01. Radiohead - Airbag.opus"))
        assertEquals("Radiohead - Airbag", TrackTags.titleFromFileName("1.07 - Radiohead - Airbag.opus"))
    }

    @Test
    fun playlistRoundTripAndForeignPaths() {
        val rel = "Led Zeppelin/Led Zeppelin/01. Good Times.opus"
        val text = M3u.write("Road trip", listOf(rel, "Gone/Album/x.opus"), lib)
        val parsed = M3u.parse(text)
        assertEquals("Road trip", parsed.name)
        assertEquals(listOf(rel, "Gone/Album/x.opus"), parsed.paths)

        val zep = lib.trackByRelativePath(rel)!!
        assertEquals(zep, M3u.resolve("/storage/emulated/0/Music/$rel", lib))
        assertEquals(zep, M3u.resolve("C:\\Users\\me\\Music\\phone\\Led Zeppelin\\Led Zeppelin\\01. Good Times.opus", lib))
        assertEquals(zep, M3u.resolve("../$rel", lib))
        assertEquals(zep, M3u.resolve("file:///home/musicuser/Music/phone/Led%20Zeppelin/Led%20Zeppelin/01.%20Good%20Times.opus", lib))
        assertEquals(zep, M3u.resolve("somewhere/else/01. Good Times.opus", lib)) // unique file name
        assertNull(M3u.resolve("Nope/nothing.opus", lib))
    }
}
