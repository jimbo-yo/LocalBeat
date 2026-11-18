package com.jimboyo.localbeat.library

import org.json.JSONObject

/**
 * Where each album came from, as LocalBeat Studio's phone sync says: it puts
 * `sources.json` in this app's own folder (Android/data/…/files), because the
 * app may only read audio files in Music/, not the sync's own record there.
 *
 * `{"albums": {"Artist/Album": {"from": "cd"} | {"from": "old", "format": "MP3"}}}`,
 * keyed by album folder relative to Music/.
 */
object Sources {
    fun parse(json: String): Map<String, Origin> = runCatching {
        val albums = JSONObject(json).optJSONObject("albums") ?: return emptyMap()
        buildMap {
            for (dir in albums.keys()) {
                val a = albums.optJSONObject(dir) ?: continue
                when (a.optString("from")) {
                    "cd" -> put(dir, Origin.CdRip)
                    "old" -> put(dir, Origin.OldCopy(a.optString("format")))
                }
            }
        }
    }.getOrDefault(emptyMap())

    /**
     * A song's origin. Phone songs: their album folder's entry. Server songs: the
     * server's FLAC library is the CD rips (it wins over Miscellaneous when an
     * album is in both), anything else there is an older copy.
     */
    fun originOf(t: Track, musicDir: String, albums: Map<String, Origin>): Origin {
        if (t.onServer) {
            return when (val f = Track.formatOf(t.path)) {
                "" -> Origin.Unknown
                "FLAC" -> Origin.CdRip
                else -> Origin.OldCopy(f)
            }
        }
        val rel = t.path.removePrefix(musicDir.trimEnd('/') + "/")
        if (rel == t.path || '/' !in rel) return Origin.Unknown
        return albums[rel.substringBeforeLast('/')] ?: Origin.Unknown
    }
}
