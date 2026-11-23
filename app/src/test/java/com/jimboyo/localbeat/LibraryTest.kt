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

    private fun track(rel: String, title: String, artist: String, album: String, year: Int = 0, genre: String = "", mood: String = "", n: Int = 1) = Track(
        id = Track.idForPath(root + rel), mediaStoreId = 1, path = root + rel, modified = 0, size = 0, durationMs = 180_000,
        dateAdded = 0, title = title, artist = artist, albumArtist = artist, album = album, trackNumber = n, discNumber = 1,
        discTotal = 1, year = year, genre = genre, musicBrainzAlbumId = "", hasEmbeddedArt = false, mood = mood,
    )

    private val lib = Library(
        listOf(
            track("Guns N' Roses - Appetite/01 - Welcome.opus", "Welcome to the Jungle", "Guns N' Roses", "Appetite for Destruction", 1987, "Rock", "Energetic; Driving"),
            track("Guns N’ Roses/Use Your Illusion I/01. Right Next Door.opus", "Right Next Door to Hell", "Guns N’ Roses", "Use Your Illusion I", 1991, "rock; hard rock", "gritty; driving"),
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
        assertEquals(listOf("Driving", "Energetic", "Gritty"), lib.moods.map { it.name })
        assertEquals(2, lib.moods.first { it.name == "Driving" }.albums.size)
        assertEquals(listOf("1960s", "1980s", "1990s"), lib.decades.map { it.name })

        // LibraryCache v4 round-trips mood tags too.
        val tmp = java.io.File.createTempFile("lb-cache", ".bin")
        try {
            com.jimboyo.localbeat.library.LibraryCache.write(tmp, lib.tracks)
            val read = com.jimboyo.localbeat.library.LibraryCache.read(tmp)!!
            assertEquals(lib.tracks, read)
        } finally {
            tmp.delete()
        }
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

        // Paths starting with '#' and names with '\r' round-trip without losing entries or injecting lines.
        val hashPath = "#1 Hits/01. Song.opus"
        val hashText = M3u.write("Line1\rLine2\nLine3", listOf(hashPath, rel), lib)
        val hashParsed = M3u.parse(hashText)
        assertEquals("Line1 Line2 Line3", hashParsed.name)
        assertEquals(listOf(hashPath, rel), hashParsed.paths)

        // MoveToServer.pathKey normalizes backslashes and preserves dotted folder names when extensionless.
        assertEquals("r.e.m./track", com.jimboyo.localbeat.playlists.MoveToServer.pathKey("R.E.M.\\Track"))
        assertEquals("r.e.m./01. losing my religion", com.jimboyo.localbeat.playlists.MoveToServer.pathKey("R.E.M.\\01. Losing My Religion.flac"))
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

    /** Albums that came apart: a bonus track without the release ID, a compilation without an album artist. */
    @Test
    fun songsInOneFolderStayOneAlbum() {
        fun s(id: String, path: String, title: String, artist: String, album: String, mbid: String = "") =
            serverTrack(id, title, artist, album, 1, mbid).copy(path = "server:$path", albumArtist = artist)
        val tracks = listOf(
            // Enriched old album: the release ID on all but one song.
            s("1", "Dido/No Angel/01 Here With Me.m4a", "Here With Me", "Dido", "No Angel", mbid = "5395286e"),
            s("2", "Dido/No Angel/03 Don't Think of Me.m4a", "Don't Think of Me", "Dido", "No Angel", mbid = "5395286e"),
            s("3", "Dido/No Angel/02 Hunter.m4a", "Hunter", "Dido", "No Angel"),
            // A compilation: each song's artist as its album artist.
            s("4", "Compilations/Water Music/01.mp3", "Ste No.1 in F", "Handel", "Water Music"),
            s("5", "Compilations/Water Music/02.mp3", "Ste No.2 in D", "Lentement", "Water Music"),
            s("6", "Compilations/Water Music/03.mp3", "Ste No.3 in G", "Menuet", "Water Music"),
            // Two releases in one folder stay apart.
            s("7", "Destiny's Child/Survivor.m4a", "Survivor", "Destiny's Child", "Survivor", mbid = "5dd0e560"),
            s("8", "Destiny's Child/Bootylicious.m4a", "Bootylicious", "Destiny's Child", "Survivor", mbid = "efb2e63e"),
            // Same title elsewhere (another folder, another artist): another album.
            s("9", "Somebody/No Angel/01.mp3", "No Angel", "Somebody", "No Angel"),
        )
        val lib = Library(tracks)
        val noAngel = lib.albums.filter { it.title == "No Angel" }
        assertEquals(2, noAngel.size)
        assertEquals(listOf("Here With Me", "Don't Think of Me", "Hunter").sorted(), noAngel.first { it.artist == "Dido" }.tracks.map { it.title }.sorted())
        val water = lib.albums.single { it.title == "Water Music" }
        assertEquals(3, water.tracks.size)
        assertEquals(Library.VARIOUS_ARTISTS, water.artist)
        assertEquals(2, lib.albums.count { it.title == "Survivor" })

        // Two multi-track single-artist albums dumped in the same Compilations/Greatest Hits folder stay apart.
        val collided = Library(
            listOf(
                s("10", "Compilations/Greatest Hits/01 Swingtown.m4a", "Swingtown", "Steve Miller Band", "Greatest Hits"),
                s("11", "Compilations/Greatest Hits/02 Jungle Love.m4a", "Jungle Love", "Steve Miller Band", "Greatest Hits"),
                s("12", "Compilations/Greatest Hits/03 The Joker.m4a", "The Joker", "Steve Miller Band", "Greatest Hits"),
                s("13", "Compilations/Greatest Hits/White Wedding.m4a", "White Wedding", "Billy Idol", "Greatest Hits"),
                s("14", "Compilations/Greatest Hits/Rebel Yell.m4a", "Rebel Yell", "Billy Idol", "Greatest Hits"),
                s("15", "Compilations/Greatest Hits/Mony Mony.m4a", "Mony Mony", "Billy Idol", "Greatest Hits"),
            )
        )
        assertEquals(2, collided.albums.size)
        assertEquals(listOf("Billy Idol", "Steve Miller Band"), collided.albums.map { it.artist }.sorted())

        // A true compilation where two artists happen to have 3 tracks each among many others still merges.
        val bigComp = Library(
            (1..3).map { i -> s("h$i", "Compilations/Water Music/0$i.mp3", "H$i", "Handel", "Water Music") } +
                (1..3).map { i -> s("m$i", "Compilations/Water Music/1$i.mp3", "M$i", "Minuet", "Water Music") } +
                (1..10).map { i -> s("o$i", "Compilations/Water Music/2$i.mp3", "O$i", "Artist $i", "Water Music") }
        )
        assertEquals(1, bigComp.albums.size)
        assertEquals(Library.VARIOUS_ARTISTS, bigComp.albums.single().artist)
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
        // albumOf(trackId) resolves merged server track IDs via standIns.
        assertEquals(lib.albumOf(hello), lib.albumOf("s1"))
        // Empty voice search query must score 0, not match everything.
        assertEquals(0.0, com.jimboyo.localbeat.playback.BrowseTree.score("Road trip", ""), 0.0)

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

    @Test
    fun broadGenresHierarchyAndSearchAndVoice() {
        val rich = Library(
            listOf(
                track("10k/In My Tribe/01.opus", "What's the Matter Here?", "10,000 Maniacs", "In My Tribe", 1987, "Alternative & Indie; Jangle Pop; Folk Rock", "Thoughtful; Warm; Mellow"),
                track("GNR/Appetite/01.opus", "Welcome to the Jungle", "Guns N' Roses", "Appetite for Destruction", 1987, "Rock; Hard Rock", "Energetic; Driving; Gritty"),
                track("ACDC/Back/01.opus", "Hells Bells", "AC/DC", "Back in Black", 1980, "Hard Rock", "Driving; Anthemic"),
                track("Zep/IV/01.opus", "Black Dog", "Led Zeppelin", "Led Zeppelin IV", 1971, "Rock; Classic Rock", "Driving; Gritty"),
            )
        )
        // Broad genres: "Alternative & Indie" and "Rock"; legacy single-tag "Hard Rock" folds under "Rock".
        assertEquals(listOf("Alternative & Indie", "Rock"), rich.broadGenres.map { it.name })
        val rock = rich.broadGenreByName("Rock")!!
        assertEquals(3, rock.albums.size)
        assertEquals(listOf("Hard Rock", "Classic Rock"), rock.subgenres.map { it.name })
        assertEquals(2, rock.subgenres.first { it.name == "Hard Rock" }.albums.size)

        // Search finds moods, genres/subgenres, decades ("80s"), playlists, and multi-word tag queries.
        val pl = com.jimboyo.localbeat.playlists.Playlist("p1", "Road Trip", listOf("GNR/Appetite/01.opus"))
        assertEquals(listOf("Driving"), com.jimboyo.localbeat.library.Search.run("driving", rich).moods.map { it.name })
        assertEquals(listOf("Jangle Pop"), com.jimboyo.localbeat.library.Search.run("jangle", rich).genres.map { it.name })
        assertEquals(listOf("1980s"), com.jimboyo.localbeat.library.Search.run("80s", rich).decades.map { it.name })
        assertEquals(listOf("Road Trip"), com.jimboyo.localbeat.library.Search.run("road", rich, listOf(pl)).playlists.map { it.name })

        // BrowseTree tracksFor resolves moods, broad genres (including folded legacy albums), subgenres, shuffle-all, and random album.
        val tree = com.jimboyo.localbeat.playback.BrowseTree(null, { listOf(pl) })
        assertEquals(3, tree.tracksFor(com.jimboyo.localbeat.playback.BrowseTree.GENRE + rock.id, rich)!!.size)
        assertEquals(3, tree.tracksFor(com.jimboyo.localbeat.playback.BrowseTree.SHUFFLE_GENRE + rock.id, rich)!!.size)
        val driving = rich.moodByName("Driving")!!
        assertEquals(3, tree.tracksFor(com.jimboyo.localbeat.playback.BrowseTree.MOOD + driving.id, rich)!!.size)
        assertEquals(3, tree.tracksFor(com.jimboyo.localbeat.playback.BrowseTree.SHUFFLE_MOOD + driving.id, rich)!!.size)
        assertEquals(4, tree.tracksFor(com.jimboyo.localbeat.playback.BrowseTree.SHUFFLE_ALL, rich)!!.size)
        assertEquals(1, tree.tracksFor(com.jimboyo.localbeat.playback.BrowseTree.RANDOM_ALBUM, rich)!!.size)
        // A Shuffle button's queue: every song of the list, mixed song by song (not album by album).
        val rockSongs = tree.tracksFor(com.jimboyo.localbeat.playback.BrowseTree.GENRE + rock.id, rich)!!
        assertEquals(rockSongs.toSet(), tree.tracksFor(com.jimboyo.localbeat.playback.BrowseTree.SHUFFLED + com.jimboyo.localbeat.playback.BrowseTree.GENRE + rock.id, rich)!!.toSet())
        // "Shuffle <artist>" and "Shuffle <playlist>" play the same songs as the artist and the playlist.
        val acdc = rich.artists.first { it.name == "AC/DC" }
        assertEquals(acdc.tracks, tree.tracksFor(com.jimboyo.localbeat.playback.BrowseTree.SHUFFLE_ARTIST + acdc.id, rich))
        assertEquals(
            tree.tracksFor(com.jimboyo.localbeat.playback.BrowseTree.PLAYLIST + pl.id, rich)!!.toSet(),
            tree.tracksFor(com.jimboyo.localbeat.playback.BrowseTree.SHUFFLE_PLAYLIST + pl.id, rich)!!.toSet(),
        )

        // Voice search resolves mood ("driving music"), subgenre ("jangle pop"), and decade ("80s music").
        fun voiceReq(q: String) = androidx.media3.common.MediaItem.Builder()
            .setRequestMetadata(androidx.media3.common.MediaItem.RequestMetadata.Builder().setSearchQuery(q).build())
            .build()
        assertEquals(3, tree.resolve(voiceReq("driving music"), rich).tracks.size)
        assertEquals(listOf("What's the Matter Here?"), tree.resolve(voiceReq("jangle pop"), rich).tracks.map { it.title })
        assertEquals(3, tree.resolve(voiceReq("80s music"), rich).tracks.size)
        assertEquals(listOf("Black Dog"), tree.resolve(voiceReq("seventies"), rich).tracks.map { it.title })
    }
}
