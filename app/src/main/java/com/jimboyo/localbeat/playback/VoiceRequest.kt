package com.jimboyo.localbeat.playback

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem

/**
 * "Hey Google, play … on LocalBeat" on the phone: the assistant starts an activity
 * with [MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH], the words in
 * [SearchManager.QUERY] and often the request split into title / artist / album /
 * genre / playlist extras. Turned into the same search item Android Auto's voice
 * requests arrive as, so [BrowseTree.resolve] picks what to play.
 */
object VoiceRequest {
    /** The extras [BrowseTree]'s voice search reads, plus the focus (what kind of thing was asked for). */
    val KEYS = listOf(
        MediaStore.EXTRA_MEDIA_FOCUS,
        MediaStore.EXTRA_MEDIA_TITLE,
        MediaStore.EXTRA_MEDIA_ARTIST,
        MediaStore.EXTRA_MEDIA_ALBUM,
        MediaStore.EXTRA_MEDIA_GENRE,
        BrowseTree.EXTRA_MEDIA_PLAYLIST,
    )

    fun isVoice(intent: Intent?) = intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH

    /** The query (blank: "play some music") and the non-blank fields, trimmed. */
    fun fields(query: String?, extra: (String) -> String?): Pair<String, Map<String, String>> =
        query?.trim().orEmpty() to KEYS.mapNotNull { k -> extra(k)?.trim()?.takeIf { it.isNotEmpty() }?.let { k to it } }.toMap()

    fun item(intent: Intent): MediaItem {
        val (query, fields) = fields(intent.getStringExtra(SearchManager.QUERY)) { intent.getStringExtra(it) }
        val extras = Bundle().apply { fields.forEach { (k, v) -> putString(k, v) } }
        return MediaItem.Builder()
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).setExtras(extras).build())
            .build()
    }
}
