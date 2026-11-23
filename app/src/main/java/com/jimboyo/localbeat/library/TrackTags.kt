package com.jimboyo.localbeat.library

import com.jimboyo.localbeat.tags.VorbisComments

/** The tags LocalBeat uses, from whichever tag format the file has. */
data class TrackTags(
    val title: String = "",
    val artist: String = "",
    val albumArtist: String = "",
    val album: String = "",
    val trackNumber: Int = 0,
    val discNumber: Int = 1,
    val discTotal: Int = 1,
    val year: Int = 0,
    val genre: String = "",
    val mood: String = "",
    val musicBrainzAlbumId: String = "",
    val hasEmbeddedArt: Boolean = false,
    /** `LOCALBEAT_SOURCE=CD`: Studio's phone sync made this file from a verified CD rip. */
    val cdRip: Boolean = false,
) {
    companion object {
        /** cyanrip writes lowercase keys and whipper uppercase; lookups are case-insensitive. */
        fun from(c: VorbisComments) = TrackTags(
            title = c["TITLE"].orEmpty(),
            artist = c.all("ARTIST").filter { it.isNotBlank() }.joinToString(", "),
            albumArtist = c.first("ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST").orEmpty(),
            album = c["ALBUM"].orEmpty(),
            trackNumber = leadingInt(c["TRACKNUMBER"]) ?: 0,
            discNumber = leadingInt(c["DISCNUMBER"]) ?: 1,
            discTotal = leadingInt(c.first("DISCTOTAL", "TOTALDISCS"))
                ?: trailingTotal(c["DISCNUMBER"]) ?: 1,
            // The album's first release, not this CD's (Kink Kronikles: 1972, not the 1989 CD).
            year = year(c.first("ORIGINALDATE", "ORIGINALYEAR", "DATE", "YEAR")),
            genre = c.all("GENRE").filter { it.isNotBlank() }.joinToString("; "),
            mood = c.all("MOOD").filter { it.isNotBlank() }.joinToString("; "),
            musicBrainzAlbumId = c["MUSICBRAINZ_ALBUMID"].orEmpty(),
            hasEmbeddedArt = c.hasPicture,
            cdRip = c["LOCALBEAT_SOURCE"].equals("CD", ignoreCase = true),
        )

        /** "07", "7/12" -> 7 */
        fun leadingInt(s: String?): Int? = s?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()

        /** "2/3" -> 3 */
        private fun trailingTotal(s: String?): Int? = s?.substringAfter('/', "")?.trim()?.toIntOrNull()

        /** "1989-09-06", "1989" -> 1989 */
        fun year(s: String?): Int = s?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() } ?: 0

        private val numberedName = Regex("""^\s*(\d+\.)?\d+\s*[-.]\s*""")

        /**
         * A title for an untagged file from its name: "07. Artist - Title.opus"
         * or "07 - Artist - Title.opus" becomes "Artist - Title".
         */
        fun titleFromFileName(name: String): String =
            name.substringBeforeLast('.').replace(numberedName, "").ifBlank { name }
    }
}
