package com.jimboyo.localbeat

import com.jimboyo.localbeat.playlists.Playlist
import com.jimboyo.localbeat.server.EditQueue
import com.jimboyo.localbeat.server.PlaylistEdit
import com.jimboyo.localbeat.server.PlaylistEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Editing server playlists on the phone: shown at once, queued, sent in order (docs/control.md step 5). */
class PlaylistEditsTest {
    private val road = Playlist("p1", "Road trip", listOf("sA", "sB", "sC"), onServer = true, owner = "jim", changed = "2026-11-17T16:56:44Z")
    private val sams = Playlist("p2", "Sam's", listOf("sA"), onServer = true, owner = "sam", readOnly = true, changed = "2026-11-17T10:00:00Z")
    private val base = listOf(road, sams)

    private fun shown(q: EditQueue) = PlaylistEdits.applyAll(base, q.edits, "jim")

    @Test fun editsShowAtOnce() {
        var q = EditQueue()
        q = PlaylistEdits.enqueue(q, PlaylistEdit.Add("p1", listOf("sD")), base)
        q = PlaylistEdits.enqueue(q, PlaylistEdit.Remove("p1", listOf(0)), base)
        q = PlaylistEdits.enqueue(q, PlaylistEdit.SetSongs("p1", listOf("sD", "sB", "sC")), base)
        q = PlaylistEdits.enqueue(q, PlaylistEdit.Rename("p1", "Road trip 2"), base)
        val p = shown(q).first { it.id == "p1" }
        assertEquals(listOf("sD", "sB", "sC"), p.entries)
        assertEquals("Road trip 2", p.name)
        // The playlist's server time as last seen, kept once for the checks when sending.
        assertEquals(mapOf("p1" to "2026-11-17T16:56:44Z"), q.known)

        // Someone else's is never changed on the phone.
        val q2 = PlaylistEdits.enqueue(EditQueue(), PlaylistEdit.Add("p2", listOf("sZ")), base)
        assertEquals(listOf("sA"), shown(q2).first { it.id == "p2" }.entries)

        // Deleted: gone from the list straight away.
        val q3 = PlaylistEdits.enqueue(EditQueue(), PlaylistEdit.Delete("p1"), base)
        assertEquals(listOf("p2"), shown(q3).map { it.id })
    }

    @Test fun madeWhileOutOfReach() {
        var q = PlaylistEdits.enqueue(EditQueue(), PlaylistEdit.Create("new-1", "Airplane", listOf("sA")), base)
        q = PlaylistEdits.enqueue(q, PlaylistEdit.Add("new-1", listOf("sB")), base)
        val made = shown(q).first()
        assertEquals("new-1", made.id)
        assertEquals("jim", made.owner)
        assertEquals(listOf("sA", "sB"), made.entries)
        assertTrue(q.known.isEmpty())

        // The server made it as p9: the later edit goes to p9.
        q = PlaylistEdits.created(q, "new-1", "p9", "2026-11-17T18:00:00Z")
        assertEquals(listOf(PlaylistEdit.Add("p9", listOf("sB"))), q.edits)
        assertEquals(mapOf("p9" to "2026-11-17T18:00:00Z"), q.known)
        q = PlaylistEdits.sent(q, "2026-11-17T18:00:01Z")
        assertTrue(q.edits.isEmpty())
        assertEquals("2026-11-17T18:00:01Z", q.known["p9"])
    }

    @Test fun changedElsewhereDropsOnlyThatPlaylist() {
        var q = PlaylistEdits.enqueue(EditQueue(), PlaylistEdit.Add("p1", listOf("sD")), base)
        q = PlaylistEdits.enqueue(q, PlaylistEdit.Create("new-2", "Other", emptyList()), base)
        q = PlaylistEdits.enqueue(q, PlaylistEdit.Rename("p1", "Mine"), base)
        q = PlaylistEdits.dropPlaylist(q, "p1")
        assertEquals(listOf(PlaylistEdit.Create("new-2", "Other", emptyList())), q.edits)
        assertTrue("p1" !in q.known)
    }

    @Test fun savedAcrossRestarts() {
        var q = EditQueue()
        for (e in listOf(
            PlaylistEdit.Create("new-1", "Air “plane”", listOf("sA", "sB")),
            PlaylistEdit.Add("p1", listOf("sD")),
            PlaylistEdit.Remove("p1", listOf(2, 0)),
            PlaylistEdit.SetSongs("p1", listOf("sC", "sB")),
            PlaylistEdit.Rename("p1", "Road ’26"),
            PlaylistEdit.Delete("p1"),
        )) q = PlaylistEdits.enqueue(q, e, base)
        assertEquals(q, PlaylistEdits.fromJson(PlaylistEdits.toJson(q)))
    }
}
