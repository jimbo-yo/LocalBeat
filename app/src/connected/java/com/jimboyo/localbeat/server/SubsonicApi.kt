package com.jimboyo.localbeat.server

import com.jimboyo.localbeat.library.Track
import com.jimboyo.localbeat.library.shortHash
import com.jimboyo.localbeat.playlists.Playlist
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The OpenSubsonic calls LocalBeat uses: the standard subset that both Navidrome
 * and LocalBeat's own server implement. Blocking: call off the main thread.
 *
 * Auth is Subsonic's token scheme: `t = md5(password + salt)` with a fresh salt
 * per request; the password itself is never sent. (It still travels inside
 * WireGuard, and over plain HTTP only on the home network while developing.)
 */
internal class SubsonicApi(
    address: String,
    val username: String,
    private val password: String,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    val base: String = normalize(address)

    class SubsonicException(val code: Int, message: String) : IOException(message)

    class Ping(val type: String, val version: String, val openSubsonic: Boolean)

    // ---- URLs ----

    fun url(method: String, params: List<Pair<String, String>> = emptyList()): String {
        val salt = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val token = md5Hex(password + salt)
        val all = params + listOf("u" to username, "t" to token, "s" to salt, "v" to API_VERSION, "c" to CLIENT, "f" to "json")
        return "$base/rest/$method?" + all.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    /** A song as 160 kbps Opus (the server converts FLAC on the fly). */
    fun streamUrl(songId: String) = url("stream", listOf("id" to songId, "format" to "opus", "maxBitRate" to "160"))

    fun coverUrl(coverArtId: String, size: Int) = url("getCoverArt", listOf("id" to coverArtId, "size" to size.toString()))

    // ---- calls ----

    /** The `subsonic-response` object; throws [SubsonicException] for an error response. */
    fun call(method: String, params: List<Pair<String, String>> = emptyList()): JSONObject {
        val c = open(URL(url(method, params)))
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 30_000
            val code = c.responseCode
            val body = (if (code >= 400) c.errorStream else c.inputStream)?.use { String(readLimited(it, MAX_JSON_BYTES), Charsets.UTF_8) }.orEmpty()
            if (code >= 400 && !body.trimStart().startsWith("{")) throw IOException("HTTP $code from the server")
            return parseResponse(body)
        } finally {
            c.disconnect()
        }
    }

    /**
     * Like [call], with [params] in a form POST body (OpenSubsonic `formPost`:
     * a long song list doesn't fit a URL). The login stays in the URL.
     */
    fun post(method: String, params: List<Pair<String, String>>): JSONObject {
        val c = open(URL(url(method)))
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 30_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val body = (if (code >= 400) c.errorStream else c.inputStream)?.use { String(readLimited(it, MAX_JSON_BYTES), Charsets.UTF_8) }.orEmpty()
            if (code >= 400 && !body.trimStart().startsWith("{")) throw IOException("HTTP $code from the server")
            return parseResponse(body)
        } finally {
            c.disconnect()
        }
    }

    /** Song lists by POST when the server takes it, else in the URL. */
    private fun send(method: String, params: List<Pair<String, String>>, formPost: Boolean) =
        if (formPost) post(method, params) else call(method, params)

    /** One playlist with its songs, or null if it's gone (or no longer visible). */
    fun playlist(id: String): JSONObject? = try {
        call("getPlaylist", listOf("id" to id)).optJSONObject("playlist")
    } catch (e: SubsonicException) {
        if (e.code == 70) null else throw e
    }

    /** A new playlist; returns it (with its ID). */
    fun createPlaylist(name: String, songIds: List<String>, formPost: Boolean): JSONObject =
        send("createPlaylist", listOf("name" to name) + songIds.map { "songId" to it }, formPost).optJSONObject("playlist")
            ?: throw IOException("The server didn't return the new playlist")

    /** Replaces a playlist's songs (how a reorder is sent). */
    fun setPlaylistSongs(id: String, songIds: List<String>, formPost: Boolean) {
        send("createPlaylist", listOf("playlistId" to id) + songIds.map { "songId" to it }, formPost)
    }

    fun updatePlaylist(id: String, name: String? = null, add: List<String> = emptyList(), remove: List<Int> = emptyList(), formPost: Boolean) {
        send(
            "updatePlaylist",
            listOf("playlistId" to id) + listOfNotNull(name?.let { "name" to it }) +
                remove.map { "songIndexToRemove" to it.toString() } + add.map { "songIdToAdd" to it },
            formPost,
        )
    }

    fun deletePlaylist(id: String) {
        call("deletePlaylist", listOf("id" to id))
    }

    /** A quick "are you there?" for watching the connection: short timeouts, true if the server answered OK. */
    fun quickPing(): Boolean {
        val c = open(URL(url("ping")))
        return try {
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            c.responseCode == 200 && parseResponse(c.inputStream.use { String(readLimited(it, 1024 * 1024), Charsets.UTF_8) }).let { true }
        } catch (e: Exception) {
            false
        } finally {
            c.disconnect()
        }
    }

    fun ping(): Ping {
        val r = call("ping")
        return Ping(r.optString("type", "subsonic"), r.optString("serverVersion", r.optString("version")), r.optBoolean("openSubsonic"))
    }

    /** The OpenSubsonic extensions the server declares (empty for plain Subsonic servers). */
    fun extensions(): Set<String> = runCatching {
        val a = call("getOpenSubsonicExtensions").optJSONArray("openSubsonicExtensions") ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it).optString("name") }.filter { it.isNotEmpty() }.toSet()
    }.getOrDefault(emptySet())

    /** Every album (paged). */
    fun albums(): List<JSONObject> = paged { offset ->
        call("getAlbumList2", listOf("type" to "alphabeticalByName", "size" to PAGE.toString(), "offset" to offset.toString()))
            .optJSONObject("albumList2")?.optJSONArray("album")
    }

    /** Every song (paged): an empty search3 query lists them all (OpenSubsonic). */
    fun songs(): List<JSONObject> = paged { offset ->
        call("search3", listOf("query" to "", "artistCount" to "0", "albumCount" to "0", "songCount" to PAGE.toString(), "songOffset" to offset.toString()))
            .optJSONObject("searchResult3")?.optJSONArray("song")
    }

    /** The user's playlists, those shared with them and public ones, each with its songs (`entry`). */
    fun playlists(): List<JSONObject> {
        val list = call("getPlaylists").optJSONObject("playlists")?.optJSONArray("playlist") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val id = list.getJSONObject(i).optString("id").ifEmpty { return@mapNotNull null }
            playlist(id)
        }
    }

    private fun paged(page: (Int) -> JSONArray?): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        var pages = 0
        while (pages < MAX_PAGES) {
            val a = page(out.size) ?: break
            var added = 0
            for (i in 0 until a.length()) {
                val obj = a.getJSONObject(i)
                val id = obj.optString("id")
                if (id.isEmpty() || seen.add(id)) {
                    out += obj
                    added++
                }
            }
            pages++
            if (a.length() < PAGE || added == 0) break
        }
        return out
    }

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT = "LocalBeat"
        private const val PAGE = 500
        private const val MAX_PAGES = 1_000
        private const val MAX_JSON_BYTES = 32 * 1024 * 1024
        private val random = SecureRandom()

        /** Reads up to [maxBytes] from [input], throwing [IOException] if exceeded. */
        fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw IOException("Response too large")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        /** "10.8.0.1:4533/" -> "http://10.8.0.1:4533" */
        fun normalize(address: String): String {
            val a = address.trim().trimEnd('/')
            return if (a.contains("://")) a else "http://$a"
        }

        fun md5Hex(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        fun parseResponse(body: String): JSONObject {
            val r = JSONObject(body).optJSONObject("subsonic-response") ?: throw IOException("Not a Subsonic server")
            if (r.optString("status") != "ok") {
                val e = r.optJSONObject("error")
                throw SubsonicException(e?.optInt("code") ?: 0, e?.optString("message").orEmpty().ifEmpty { "The server refused" })
            }
            return r
        }

        /**
         * The server's songs as LocalBeat tracks, grouped later by LocalBeat's own
         * rules (not the server's). The album's MusicBrainz release ID and original
         * date come from the album list; [serverKey] keeps IDs apart per server.
         */
        /** LocalBeat's track ID for a server song (kept apart per server and user). */
        fun trackId(serverKey: String, songId: String) = "s" + shortHash("$serverKey|$songId")

        fun toTracks(serverKey: String, albums: List<JSONObject>, songs: List<JSONObject>): List<Track> {
            val albumsById = albums.associateBy { it.optString("id") }
            return songs.mapNotNull { s ->
                val id = s.optString("id").ifEmpty { return@mapNotNull null }
                val album = albumsById[s.optString("albumId")]
                val genres = s.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }
                    ?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                    ?: listOfNotNull(s.optString("genre").takeIf { it.isNotBlank() })
                val moods = s.optJSONArray("moods")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        a.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
                            ?: a.optString(i).takeIf { it.isNotBlank() }
                    }
                }.orEmpty()
                Track(
                    id = trackId(serverKey, id),
                    mediaStoreId = -1,
                    path = "server:" + s.optString("path", id),
                    modified = 0,
                    size = s.optLong("size"),
                    durationMs = s.optLong("duration") * 1000,
                    dateAdded = isoSeconds(s.optString("created")),
                    title = s.optString("title").ifBlank { s.optString("path").substringAfterLast('/').substringBeforeLast('.') },
                    artist = s.optString("artist"),
                    albumArtist = s.optString("displayAlbumArtist").ifBlank { album?.optString("artist").orEmpty() },
                    album = s.optString("album"),
                    trackNumber = s.optInt("track"),
                    discNumber = s.optInt("discNumber", 1).coerceAtLeast(1),
                    discTotal = 1,
                    // The album's first release, like ORIGINALDATE on the phone.
                    year = album?.optJSONObject("originalReleaseDate")?.optInt("year")?.takeIf { it > 0 }
                        ?: s.optInt("year").takeIf { it > 0 } ?: album?.optInt("year") ?: 0,
                    genre = genres.joinToString("; "),
                    musicBrainzAlbumId = album?.optString("musicBrainzId").orEmpty(),
                    hasEmbeddedArt = s.optString("coverArt").isNotEmpty(),
                    serverSongId = id,
                    serverCoverArt = s.optString("coverArt"),
                    mood = moods.joinToString("; "),
                    // The server's FLAC library is the verified CD rips (LocalBeatServer serves
                    // an album from its FLAC rip when it also has an older lossy copy).
                    cdRip = s.optString("suffix").equals("flac", ignoreCase = true),
                )
            }
        }

        /**
         * A server playlist (getPlaylist's object, with `entry`) as a LocalBeat
         * playlist of server track IDs. `readonly` is LocalBeat's server's
         * (someone else's playlist); other servers: read only if the owner isn't us.
         */
        fun toPlaylist(serverKey: String, username: String, p: JSONObject): Playlist? {
            val id = p.optString("id").ifEmpty { return null }
            val entries = p.optJSONArray("entry")
            val owner = p.optString("owner")
            return Playlist(
                id = id,
                name = p.optString("name").ifBlank { "Playlist" },
                entries = (0 until (entries?.length() ?: 0)).mapNotNull { i ->
                    entries!!.getJSONObject(i).optString("id").takeIf { it.isNotEmpty() }?.let { trackId(serverKey, it) }
                },
                onServer = true,
                owner = owner,
                readOnly = if (p.has("readonly")) p.optBoolean("readonly") else owner.isNotEmpty() && owner != username,
                comment = p.optString("comment"),
                changed = p.optString("changed"),
            )
        }

        /** "2026-11-08T21:37:01.123Z" -> seconds since 1970 (0 if missing). */
        fun isoSeconds(s: String): Long = runCatching { java.time.Instant.parse(s).epochSecond }.getOrDefault(0)
    }
}

/** The server's playlists as saved on the phone (for when the server is out of reach). */
internal fun playlistsToJson(lists: List<Playlist>): String = JSONArray().apply {
    for (p in lists) put(
        JSONObject()
            .put("id", p.id).put("name", p.name).put("owner", p.owner)
            .put("readOnly", p.readOnly).put("comment", p.comment).put("changed", p.changed)
            .put("entries", JSONArray(p.entries))
    )
}.toString()

internal fun playlistsFromJson(text: String): List<Playlist> {
    val a = JSONArray(text)
    return (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        val e = o.optJSONArray("entries") ?: JSONArray()
        Playlist(
            id = o.getString("id"),
            name = o.optString("name"),
            entries = (0 until e.length()).map(e::getString),
            onServer = true,
            owner = o.optString("owner"),
            readOnly = o.optBoolean("readOnly"),
            comment = o.optString("comment"),
            changed = o.optString("changed"),
        )
    }
}
