package com.jimboyo.localbeat

import android.provider.MediaStore
import com.jimboyo.localbeat.playback.BrowseTree
import com.jimboyo.localbeat.playback.VoiceRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Hey Google, play … on LocalBeat": the intent's query and fields. */
class VoiceRequestTest {
    @Test fun splitRequest() {
        val extras = mapOf(
            MediaStore.EXTRA_MEDIA_FOCUS to "vnd.android.cursor.item/audio",
            MediaStore.EXTRA_MEDIA_TITLE to " Nightrain ",
            MediaStore.EXTRA_MEDIA_ARTIST to "Guns N’ Roses",
            MediaStore.EXTRA_MEDIA_ALBUM to "  ",
            "unrelated" to "x",
        )
        val (query, fields) = VoiceRequest.fields(" nightrain by guns n roses ", extras::get)
        assertEquals("nightrain by guns n roses", query)
        assertEquals(
            mapOf(
                MediaStore.EXTRA_MEDIA_FOCUS to "vnd.android.cursor.item/audio",
                MediaStore.EXTRA_MEDIA_TITLE to "Nightrain",
                MediaStore.EXTRA_MEDIA_ARTIST to "Guns N’ Roses",
            ),
            fields,
        )
    }

    @Test fun playlist() {
        val (_, fields) = VoiceRequest.fields("road trip playlist", mapOf(BrowseTree.EXTRA_MEDIA_PLAYLIST to "Road Trip")::get)
        assertEquals(mapOf(BrowseTree.EXTRA_MEDIA_PLAYLIST to "Road Trip"), fields)
    }

    /** "Play some music on LocalBeat": no query at all means shuffle everything. */
    @Test fun noQuery() {
        assertEquals("" to emptyMap<String, String>(), VoiceRequest.fields(null) { null })
    }
}
