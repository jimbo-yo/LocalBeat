package com.jimboyo.localbeat

import com.jimboyo.localbeat.library.Library
import com.jimboyo.localbeat.server.SubsonicApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URI

/** Responses shaped like Navidrome 0.64's (made up, not the real library). */
class SubsonicTest {

    @Test fun addresses() {
        assertEquals("http://10.8.0.1:4533", SubsonicApi.normalize(" 10.8.0.1:4533/ "))
        assertEquals("https://music.example.org", SubsonicApi.normalize("https://music.example.org/"))
    }

    @Test fun tokenAuth() {
        // The example from the Subsonic API docs: password "sesame", salt "c19b2d".
        assertEquals("26719a1196d2a940705a59634eb18eab", SubsonicApi.md5Hex("sesame" + "c19b2d"))
        val api = SubsonicApi("10.8.0.1:4533", "jim", "sesame")
        val url = URI(api.streamUrl("song 1"))
        val q = url.rawQuery.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("/rest/stream", url.path)
        assertEquals("song 1", q["id"])
        assertEquals("opus", q["format"])
        assertEquals("160", q["maxBitRate"])
        assertEquals(SubsonicApi.md5Hex("sesame" + q["s"]), q["t"])
        assertTrue("never the password itself", "sesame" !in url.toString())
        // A fresh salt every time.
        assertTrue(api.streamUrl("x") != api.streamUrl("x"))
    }

    @Test fun errorResponses() {
        val ok = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.64.2 (10114574)","openSubsonic":true}}"""
        assertEquals("navidrome", SubsonicApi.parseResponse(ok).getString("type"))
        val bad = """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}"""
        try {
            SubsonicApi.parseResponse(bad)
            fail()
        } catch (e: SubsonicApi.SubsonicException) {
            assertEquals(40, e.code)
        }
    }

    private val albums = listOf(
        JSONObject("""{"id":"al1","name":"Born to Run","artist":"Bruce Springsteen","year":2005,
            "musicBrainzId":"0a8e97fd-457c-30bc-938a-2fba79cb04e7","originalReleaseDate":{"year":1975,"month":8,"day":25}}"""),
        JSONObject("""{"id":"al2","name":"Moanin’ in the Moonlight","artist":"Howlin’ Wolf","year":1959}"""),
    )
    private val songs = listOf(
        JSONObject("""{"id":"s1","title":"Thunder Road","artist":"Bruce Springsteen","displayAlbumArtist":"Bruce Springsteen",
            "album":"Born to Run","albumId":"al1","track":1,"discNumber":1,"year":2005,"duration":290,"size":32000000,
            "suffix":"flac","path":"Bruce Springsteen/Born to Run/01-01 - Thunder Road.flac","coverArt":"mf-s1_0",
            "created":"2026-11-08T21:37:01.123Z","genres":[{"name":"Rock"},{"name":"Heartland Rock"}]}"""),
        JSONObject("""{"id":"s2","title":"Smokestack Lightnin’","artist":"Howlin’ Wolf","album":"Moanin’ in the Moonlight",
            "albumId":"al2","track":4,"duration":187,"genre":"Blues"}"""),
        JSONObject("""{"title":"no id: skipped"}"""),
    )

    @Test fun songsBecomeTracks() {
        val tracks = SubsonicApi.toTracks("http://h:4533|jim", albums, songs)
        assertEquals(2, tracks.size)
        val t = tracks[0]
        assertEquals("Thunder Road", t.title)
        assertEquals(1975, t.year) // the album's original release, like ORIGINALDATE
        assertEquals("0a8e97fd-457c-30bc-938a-2fba79cb04e7", t.musicBrainzAlbumId)
        assertEquals("Rock; Heartland Rock", t.genre)
        assertEquals(290_000L, t.durationMs)
        assertEquals("s1", t.serverSongId)
        assertEquals("mf-s1_0", t.serverCoverArt)
        assertTrue(t.onServer && t.hasEmbeddedArt)
        assertEquals(java.time.Instant.parse("2026-11-08T21:37:01Z").epochSecond, t.dateAdded)

        val wolf = tracks[1]
        assertEquals("Howlin’ Wolf", wolf.albumArtist) // from the album when the song lacks it
        assertEquals(1959, wolf.year)
        assertEquals("Blues", wolf.genre)
        assertEquals(1, wolf.discNumber)
        assertTrue(!wolf.hasEmbeddedArt)

        // IDs differ per server, and never collide with the phone's ("t…").
        val other = SubsonicApi.toTracks("http://other:4533|jim", albums, songs)
        assertTrue(other[0].id != t.id && t.id.startsWith("s"))
        // LocalBeat groups them itself.
        assertEquals(2, Library(tracks).albums.size)
    }
}
