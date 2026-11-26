package com.jimboyo.localbeat

import com.jimboyo.localbeat.library.AlbumGroup
import com.jimboyo.localbeat.library.Library
import com.jimboyo.localbeat.library.Track
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The grouping cases shared with the TV app (LocalBeatTV `shared-tests/`, which
 * ports Library.kt to TypeScript): the same songs must give the same albums,
 * artists, genres, moods and decades in both. The files in
 * `src/test/resources/grouping/` are copies of LocalBeatTV's; change the rules
 * in both apps together and copy the regenerated `grouping.json` here.
 */
class GroupingCasesTest {
    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("grouping/$name")!!.use { String(it.readBytes(), Charsets.UTF_8) }

    private fun tracks(): List<Track> {
        val a = JSONObject(resource("grouping-input.json")).getJSONArray("tracks")
        return (0 until a.length()).map { i ->
            val t = a.getJSONObject(i)
            Track(
                id = t.getString("id"), mediaStoreId = -1, path = t.optString("path"), modified = 0, size = 0,
                durationMs = t.optLong("durationMs"), dateAdded = t.optLong("dateAdded"),
                title = t.optString("title"), artist = t.optString("artist"), albumArtist = t.optString("albumArtist"),
                album = t.optString("album"), trackNumber = t.optInt("trackNumber"),
                discNumber = t.optInt("discNumber", 1), discTotal = t.optInt("discTotal", 1), year = t.optInt("year"),
                genre = t.optString("genre"), musicBrainzAlbumId = t.optString("musicBrainzAlbumId"), hasEmbeddedArt = false,
                serverSongId = t.optString("serverSongId"), serverCoverArt = t.optString("serverCoverArt"),
                mood = t.optString("mood"), cdRip = t.optBoolean("cdRip"),
            )
        }
    }

    private fun ids(xs: List<String>) = JSONArray(xs)
    private fun group(g: AlbumGroup) = JSONObject().put("id", g.id).put("name", g.name).put("albums", ids(g.albums.map { it.id }))

    /** The same shape as LocalBeatTV shared-tests/snapshot.ts. */
    private fun snapshot(lib: Library): JSONObject = JSONObject()
        .put("tracks", ids(lib.tracks.map { it.id }))
        .put("albums", JSONArray(lib.albums.map { a ->
            JSONObject().put("id", a.id).put("title", a.title).put("artist", a.artist).put("artistId", a.artistId)
                .put("year", a.year).put("decade", a.decade).put("discCount", a.discCount).put("cdRip", a.cdRip)
                .put("genres", JSONArray(a.genres)).put("moods", JSONArray(a.moods)).put("tracks", ids(a.tracks.map { it.id }))
        }))
        .put("artists", JSONArray(lib.artists.map { r ->
            JSONObject().put("id", r.id).put("name", r.name).put("albums", ids(r.albums.map { it.id }))
                .put("looseTracks", ids(r.looseTracks.map { it.id }))
        }))
        .put("genres", JSONArray(lib.genres.map(::group)))
        .put("broadGenres", JSONArray(lib.broadGenres.map { g -> group(g.asGroup()).put("subgenres", JSONArray(g.subgenres.map(::group))) }))
        .put("moods", JSONArray(lib.moods.map(::group)))
        .put("decades", JSONArray(lib.decades.map(::group)))
        .put("recentlyAdded", ids(lib.recentlyAdded.map { it.id }))

    /** JSON as one line with keys sorted, an array item per line (compiled against Android's org.json: no similar()). */
    private fun canon(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canon(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",\n", "[", "]") { canon(v.get(it)) }
        is String -> JSONObject.quote(v)
        is Number -> if (v.toDouble() == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    @Test
    fun sharedGroupingCases() {
        val expected = JSONObject(resource("grouping.json"))
        val actual = snapshot(Library(tracks()))
        val keys = expected.keys().asSequence().toList()
        assertEquals(keys.sorted(), actual.keys().asSequence().toList().sorted())
        for (key in keys) assertEquals("$key differs", canon(expected.get(key)), canon(actual.get(key)))
    }
}
