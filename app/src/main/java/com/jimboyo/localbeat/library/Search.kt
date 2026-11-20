package com.jimboyo.localbeat.library

import java.text.Normalizer

/**
 * Search over the library (phone and music-server songs alike). Ignores case,
 * curly vs straight quotes, accents and punctuation. A name that starts with the
 * query ranks first; "springsteen thunder" also finds Thunder Road, by matching
 * every word across the name and its artist / album.
 */
object Search {
    class Results(val artists: List<Artist>, val albums: List<Album>, val tracks: List<Track>) {
        val isEmpty get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
    }

    fun run(query: String, lib: Library, limit: Int = 200): Results {
        val q = key(query)
        if (q.isEmpty()) return Results(emptyList(), emptyList(), emptyList())
        val words = q.split(' ')
        return Results(
            ranked(lib.artists, q, words, limit) { it.name to "" },
            ranked(lib.albums, q, words, limit) { it.title to it.artist },
            ranked(lib.tracks, q, words, limit) { it.title to "${it.artist} ${it.filedUnder} ${it.album}" },
        )
    }

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N} ]")
    private val SPACES = Regex("\\s+")

    /** Lowercase, folded quotes, no accents or punctuation, single spaces. */
    fun key(s: String): String =
        Normalizer.normalize(fold(s), Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
            .replace(NON_ALNUM, "").replace(SPACES, " ").trim()

    /** 3 exact, 2 starts with, 1 a word starts with, 0.5 contains, 0.25 every word somewhere, 0 no match. */
    fun score(name: String, context: String, q: String, words: List<String>): Double {
        val n = key(name)
        return when {
            n == q -> 3.0
            n.startsWith(q) -> 2.0
            n.contains(" $q") -> 1.0
            n.contains(q) -> 0.5
            words.size > 1 && key("$name $context").let { all -> words.all { all.contains(it) } } -> 0.25
            else -> 0.0
        }
    }

    private fun <T> ranked(items: List<T>, q: String, words: List<String>, limit: Int, text: (T) -> Pair<String, String>): List<T> =
        items.asSequence()
            .map { val (n, c) = text(it); it to score(n, c, q, words) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second } // stable: keeps the library's own order within a score
            .take(limit).map { it.first }.toList()

    /** The letter a name is filed under in the A–Z strip: A–Z (accents dropped), else '#'. */
    fun indexLetter(name: String): Char {
        val c = key(SortKey.of(name)).firstOrNull()?.uppercaseChar() ?: return '#'
        return if (c in 'A'..'Z') c else '#'
    }
}
