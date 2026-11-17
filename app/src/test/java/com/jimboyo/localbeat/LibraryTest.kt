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

    // ---- the music server's songs merged with the phone's ----

    private fun serverTrack(id: String, title: String, artist: String, album: String, n: Int, mbid: String = "") = track("x", title, artist, album, n = n)
        .copy(id = "s$id", mediaStoreId = -1, path = "server:$id", serverSongId = id, musicBrainzAlbumId = mbid)

    @Test
    fun serverSongsAlsoOnThePhoneAppearOnce() {
        val phone = listOf(
            track("Oasis/Morning Glory/01. Hello.opus", "Hello", "Oasis", "(What’s the Story) Morning Glory?", n = 1),
            track("Oasis/Morning Glory/02. Roll With It.opus", "Roll With It", "Oasis", "(What’s the Story) Morning Glory?", n = 2),
        )
        val server = listOf(
            // Straight apostrophe, different case: still the same album and songs.
            serverTrack("1", "Hello", "oasis", "(What's The Story) Morning Glory?", 1),
            serverTrack("2", "Roll with It", "Oasis", "(What's the Story) Morning Glory?", 2),
            serverTrack("3", "Wonderwall", "Oasis", "(What's the Story) Morning Glory?", 3),
            serverTrack("4", "Thunder Road", "Bruce Springsteen", "Born to Run", 1),
        )
        val merged = Library.merge(phone, server)
        assertEquals(listOf("Hello", "Roll With It", "Wonderwall", "Thunder Road"), merged.map { it.title })
        // Wonderwall joins the phone's album rather than making a second one.
        val lib = Library(merged)
        assertEquals(2, lib.albums.size)
        val glory = lib.albums.first { it.title.startsWith("(What") }
        assertEquals(listOf(false, false, true), glory.tracks.map { it.onServer })
    }

    /** Moving a phone playlist to the server: each song becomes the server's (docs/control.md step 5). */
    @Test
    fun phonePlaylistsMoveToTheServer() {
        val hello = track("Oasis/Morning Glory/01. Oasis - Hello.opus", "Hello", "Oasis", "Morning Glory", n = 1)
        val roll = track("Oasis/Morning Glory/02. Oasis - Roll With It.opus", "Roll With It", "Oasis", "Morning Glory", n = 2)
        val phoneOnly = track("Mine/Demo/01. Me - Demo.opus", "Demo", "Me", "Demo", n = 1)
        val server = listOf(
            serverTrack("1", "Hello", "Oasis", "Morning Glory", 1),
            // Not merged with the phone's copy (another album name), but at the same path.
            serverTrack("2", "Roll With It", "Oasis", "Morning Glory (Remastered)", 2).copy(path = "server:Oasis/Morning Glory/02. Oasis - Roll With It.flac"),
        )
        val phone = listOf(hello, roll, phoneOnly)
        val lib = Library(Library.merge(phone, server), Library.standIns(phone, server))
        assertEquals("s1", lib.serverId(hello))
        assertNull(lib.serverId(phoneOnly))

        val p = com.jimboyo.localbeat.playlists.Playlist(
            "x", "Mix", listOf("Oasis/Morning Glory/01. Oasis - Hello.opus", "Oasis/Morning Glory/02. Oasis - Roll With It.opus", "Mine/Demo/01. Me - Demo.opus", "Gone/x.opus"),
        )
        val r = com.jimboyo.localbeat.playlists.MoveToServer.serverSongs(p, lib, server)
        assertEquals(listOf("s1", "s2"), r.trackIds)
        assertEquals(listOf("Mine/Demo/01. Me - Demo.opus", "Gone/x.opus"), r.notFound)
    }

    @Test
    fun mergeMatchesByMusicBrainzOrByTagsWhenThePhoneCopyLacksTheId() {
        val phone = listOf(track("Old/01.opus", "Track One", "Artist", "Album", n = 1)) // old rip, no MBID
        val server = listOf(serverTrack("1", "Track One", "Artist", "Album", 1, mbid = "ABC-123"))
        assertEquals(1, Library.merge(phone, server).size)
        // Same disc and track number but a different title (retagged): still the phone's.
        assertEquals(1, Library.merge(phone, listOf(serverTrack("2", "Track 1 (Remastered)", "Artist", "Album", 1))).size)
        assertEquals(2, Library.merge(phone, listOf(serverTrack("3", "Other", "Artist", "Other Album", 1))).size)
        assertEquals(phone, Library.merge(phone, emptyList()))
    }

    // ---- search and the A–Z strip ----

    @Test
    fun searchIgnoresCaseQuotesAndAccentsAndMatchesWordsAcrossFields() {
        val lib = Library(listOf(
            track("a/1.opus", "Thunder Road", "Bruce Springsteen", "Born to Run", n = 1),
            track("b/1.opus", "Hello", "Oasis", "(What’s the Story) Morning Glory?", n = 1),
            track("c/1.opus", "Café del Mar", "Energy 52", "Café del Mar", n = 1),
            track("d/1.opus", "Road to Nowhere", "Talking Heads", "Little Creatures", n = 1),
        ))
        fun songs(q: String) = com.jimboyo.localbeat.library.Search.run(q, lib).tracks.map { it.title }
        fun albums(q: String) = com.jimboyo.localbeat.library.Search.run(q, lib).albums.map { it.title }
        assertEquals(listOf("(What’s the Story) Morning Glory?"), albums("whats the story"))
        assertEquals(listOf("(What’s the Story) Morning Glory?"), albums("WHAT'S"))
        assertEquals(listOf("Café del Mar"), songs("cafe"))
        // Starts-with ranks above contains.
        assertEquals(listOf("Road to Nowhere", "Thunder Road"), songs("road"))
        // Every word somewhere across title, artist and album.
        assertEquals(listOf("Thunder Road"), songs("springsteen thunder"))
        assertEquals(emptyList<String>(), songs("   "))
    }

    @Test
    fun indexLettersFollowTheListsSortOrder() {
        val letter = com.jimboyo.localbeat.library.Search::indexLetter
        assertEquals('B', letter("The Beatles"))
        assertEquals('W', letter("(What’s the Story) Morning Glory?"))
        assertEquals('E', letter("Élan"))
        assertEquals('#', letter("100% Deep House"))
        assertEquals('#', letter(""))
        assertEquals('A', letter("‘Allo ‘Allo"))
    }
}
