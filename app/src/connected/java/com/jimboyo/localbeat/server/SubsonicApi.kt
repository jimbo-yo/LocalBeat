package com.jimboyo.localbeat.server

import com.jimboyo.localbeat.library.Track
import com.jimboyo.localbeat.library.shortHash
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
    private val username: String,
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
            val body = (if (code >= 400) c.errorStream else c.inputStream)?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            if (code >= 400 && body.isBlank()) throw IOException("HTTP $code from the server")
            return parseResponse(body)
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

    private fun paged(page: (Int) -> JSONArray?): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        while (true) {
            val a = page(out.size) ?: break
            for (i in 0 until a.length()) out += a.getJSONObject(i)
            if (a.length() < PAGE) break
        }
        return out
    }

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT = "LocalBeat"
        private const val PAGE = 500
        private val random = SecureRandom()

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
        fun toTracks(serverKey: String, albums: List<JSONObject>, songs: List<JSONObject>): List<Track> {
            val albumsById = albums.associateBy { it.optString("id") }
            return songs.mapNotNull { s ->
                val id = s.optString("id").ifEmpty { return@mapNotNull null }
                val album = albumsById[s.optString("albumId")]
                val genres = s.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }
                    ?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                    ?: listOfNotNull(s.optString("genre").takeIf { it.isNotBlank() })
                Track(
                    id = "s" + shortHash("$serverKey|$id"),
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
                )
            }
        }

        /** "2026-11-08T21:37:01.123Z" -> seconds since 1970 (0 if missing). */
        fun isoSeconds(s: String): Long = runCatching { java.time.Instant.parse(s).epochSecond }.getOrDefault(0)
    }
}
