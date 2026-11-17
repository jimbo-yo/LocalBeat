package com.jimboyo.localbeat.server

import com.jimboyo.localbeat.playlists.Playlist
import org.json.JSONArray
import org.json.JSONObject

/**
 * A change to the user's playlists on the music server, made on the phone.
 * Shown at once ([PlaylistEdits.apply]) and sent in order, now or once the
 * server can be reached (docs/control.md, step 5). Songs are LocalBeat track
 * IDs of server songs; positions count the songs the server lists.
 */
sealed class PlaylistEdit {
    abstract val playlistId: String

    /** [playlistId] is a stand-in ("new-…") until the server gives the real one. */
    data class Create(override val playlistId: String, val name: String, val songs: List<String>) : PlaylistEdit()
    data class Add(override val playlistId: String, val songs: List<String>) : PlaylistEdit()
    data class Remove(override val playlistId: String, val positions: List<Int>) : PlaylistEdit()
    /** The whole list in a new order (how a move is sent). */
    data class SetSongs(override val playlistId: String, val songs: List<String>) : PlaylistEdit()
    data class Rename(override val playlistId: String, val name: String) : PlaylistEdit()
    data class Delete(override val playlistId: String) : PlaylistEdit()

    fun withId(id: String): PlaylistEdit = when (this) {
        is Create -> copy(playlistId = id)
        is Add -> copy(playlistId = id)
        is Remove -> copy(playlistId = id)
        is SetSongs -> copy(playlistId = id)
        is Rename -> copy(playlistId = id)
        is Delete -> copy(playlistId = id)
    }
}

/**
 * Edits waiting to be sent, and for each playlist they touch, its `changed`
 * time on the server when the phone last saw it: if the server's differs when
 * the edit is sent, someone changed it elsewhere and the server's version wins.
 */
data class EditQueue(
    val edits: List<PlaylistEdit> = emptyList(),
    val known: Map<String, String> = emptyMap(),
)

object PlaylistEdits {
    const val NEW = "new-"

    fun isNew(id: String) = id.startsWith(NEW)

    /** The playlists as they'll be once [e] is made (the user's own only change). */
    fun apply(lists: List<Playlist>, e: PlaylistEdit, owner: String): List<Playlist> = when (e) {
        is PlaylistEdit.Create ->
            listOf(Playlist(e.playlistId, e.name, e.songs, onServer = true, owner = owner)) + lists
        is PlaylistEdit.Delete -> lists.filter { it.id != e.playlistId }
        else -> lists.map { p ->
            if (p.id != e.playlistId || p.readOnly) p
            else when (e) {
                is PlaylistEdit.Add -> p.copy(entries = p.entries + e.songs)
                is PlaylistEdit.Remove -> e.positions.toSet().let { gone -> p.copy(entries = p.entries.filterIndexed { i, _ -> i !in gone }) }
                is PlaylistEdit.SetSongs -> p.copy(entries = e.songs)
                is PlaylistEdit.Rename -> p.copy(name = e.name)
                else -> p
            }
        }
    }

    fun applyAll(lists: List<Playlist>, edits: List<PlaylistEdit>, owner: String): List<Playlist> =
        edits.fold(lists) { acc, e -> apply(acc, e, owner) }

    /** Queues [e]; notes the playlist's `changed` time as last seen, the first time it's touched. */
    fun enqueue(q: EditQueue, e: PlaylistEdit, base: List<Playlist>): EditQueue {
        val known = if (isNew(e.playlistId) || e.playlistId in q.known) q.known
        else q.known + (e.playlistId to (base.firstOrNull { it.id == e.playlistId }?.changed.orEmpty()))
        return EditQueue(q.edits + e, known)
    }

    /** The server made the playlist [temp] as [real]: later edits go to [real]. */
    fun created(q: EditQueue, temp: String, real: String, changed: String): EditQueue = EditQueue(
        q.edits.drop(1).map { if (it.playlistId == temp) it.withId(real) else it },
        q.known - temp + (real to changed),
    )

    /** The first edit was sent; the playlist is now [changed] on the server. */
    fun sent(q: EditQueue, changed: String?): EditQueue {
        val e = q.edits.first()
        val known = if (changed == null || e is PlaylistEdit.Delete) q.known - e.playlistId else q.known + (e.playlistId to changed)
        return EditQueue(q.edits.drop(1), known)
    }

    /** Someone changed (or deleted) the playlist elsewhere: drop every edit to it. */
    fun dropPlaylist(q: EditQueue, id: String): EditQueue =
        EditQueue(q.edits.filter { it.playlistId != id }, q.known - id)

    // ---- saved on the phone ----

    fun toJson(q: EditQueue): String {
        val edits = JSONArray()
        for (e in q.edits) {
            val o = JSONObject().put("id", e.playlistId)
            when (e) {
                is PlaylistEdit.Create -> o.put("op", "create").put("name", e.name).put("songs", JSONArray(e.songs))
                is PlaylistEdit.Add -> o.put("op", "add").put("songs", JSONArray(e.songs))
                is PlaylistEdit.Remove -> o.put("op", "remove").put("positions", JSONArray(e.positions))
                is PlaylistEdit.SetSongs -> o.put("op", "set").put("songs", JSONArray(e.songs))
                is PlaylistEdit.Rename -> o.put("op", "rename").put("name", e.name)
                is PlaylistEdit.Delete -> o.put("op", "delete")
            }
            edits.put(o)
        }
        return JSONObject().put("edits", edits).put("known", JSONObject(q.known)).toString()
    }

    fun fromJson(text: String): EditQueue {
        val o = JSONObject(text)
        val a = o.optJSONArray("edits") ?: JSONArray()
        fun strings(x: JSONArray?) = (0 until (x?.length() ?: 0)).map { x!!.getString(it) }
        fun ints(x: JSONArray?) = (0 until (x?.length() ?: 0)).map { x!!.getInt(it) }
        val edits = (0 until a.length()).mapNotNull { i ->
            val e = a.getJSONObject(i)
            val id = e.getString("id")
            when (e.optString("op")) {
                "create" -> PlaylistEdit.Create(id, e.optString("name"), strings(e.optJSONArray("songs")))
                "add" -> PlaylistEdit.Add(id, strings(e.optJSONArray("songs")))
                "remove" -> PlaylistEdit.Remove(id, ints(e.optJSONArray("positions")))
                "set" -> PlaylistEdit.SetSongs(id, strings(e.optJSONArray("songs")))
                "rename" -> PlaylistEdit.Rename(id, e.optString("name"))
                "delete" -> PlaylistEdit.Delete(id)
                else -> null
            }
        }
        val k = o.optJSONObject("known") ?: JSONObject()
        return EditQueue(edits, k.keys().asSequence().associateWith { k.getString(it) })
    }
}
