package com.jimboyo.localbeat.playback

import android.content.Context
import androidx.core.content.edit

/** The play queue and position, kept so playback picks up where it left off. */
class QueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("queue", Context.MODE_PRIVATE)

    class Saved(val trackIds: List<String>, val index: Int, val positionMs: Long, val shuffle: Boolean, val repeatMode: Int)

    @Synchronized
    fun save(s: Saved) = prefs.edit {
        putString("ids", s.trackIds.joinToString("\n"))
        putInt("index", s.index)
        putLong("position", s.positionMs)
        putBoolean("shuffle", s.shuffle)
        putInt("repeat", s.repeatMode)
    }

    fun load(): Saved? {
        val ids = prefs.getString("ids", null)?.split('\n')?.filter { it.isNotBlank() }
        if (ids.isNullOrEmpty()) return null
        return Saved(ids, prefs.getInt("index", 0), prefs.getLong("position", 0), prefs.getBoolean("shuffle", false), prefs.getInt("repeat", 0))
    }
}
