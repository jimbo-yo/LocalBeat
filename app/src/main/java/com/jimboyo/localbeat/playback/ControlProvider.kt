package com.jimboyo.localbeat.playback

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.jimboyo.localbeat.BuildConfig
import com.jimboyo.localbeat.LocalBeatApp
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Controlling the player from a computer over adb (docs/control.md, step 7):
 *
 *     adb shell content call --uri content://com.jimboyo.localbeat.control --method status
 *     adb shell content call --uri content://com.jimboyo.localbeat.control --method play --arg playlist:p300eeabc4f4e
 *
 * Only `call`; every reply is one JSON string under "json". Only adb's shell
 * user and root may call it: no other app can, and anything with adb can
 * already control the phone. Playback goes through a MediaController on the
 * PlaybackService, as the app's own screens and Android Auto do, so the
 * notification, lock screen and car stay in step. In both editions.
 */
class ControlProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (!ControlArgs.allowed(Binder.getCallingUid())) throw SecurityException("LocalBeat control: adb only")
        val reply = try {
            // A provider is created before the app's own setup (Application.onCreate),
            // and a call can arrive first: that setup runs on the main thread, so
            // waiting for the main thread waits for it.
            onMain { }
            handle(method, arg)
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.javaClass.simpleName)
        }
        return Bundle().apply { putString("json", reply.toString()) }
    }

    private val app get() = context!!.applicationContext as LocalBeatApp

    private fun handle(method: String, arg: String?): JSONObject = when (method) {
        "version" -> JSONObject()
            .put("api_version", ControlArgs.API_VERSION)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("edition", if (BuildConfig.CONNECTED) "connected" else "offline")
            .put("methods", JSONArray(ControlArgs.METHODS))
        "status" -> control()
        "play" -> withController { c -> play(c, arg) }
        "pause" -> control(until = { it.getString("state") in setOf("paused", "stopped", "ended", "error") }) { it.pause() }
        "toggle" -> control(settleMs = 600) { c ->
            if (c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
        }
        "next" -> control(settleMs = 600) { it.seekToNextMediaItem() }
        "prev" -> control(settleMs = 600) { it.seekToPreviousMediaItem() }
        "seek" -> {
            val ms = ControlArgs.millis(arg ?: throw IllegalArgumentException("seek: a time like 1:30 or 90"))
            control(settleMs = 600) { it.seekTo(ms) }
        }
        "shuffle" -> control(settleMs = 300) { c -> c.shuffleModeEnabled = ControlArgs.onOff(arg, c.shuffleModeEnabled) }
        "repeat" -> {
            val mode = ControlArgs.repeat(arg)
            control(settleMs = 300) { it.repeatMode = mode }
        }
        "queue" -> withController { c ->
            onMain {
                val items = JSONArray()
                for (i in 0 until c.mediaItemCount) items.put(itemJson(c.getMediaItemAt(i)).put("index", i))
                JSONObject().put("index", c.currentMediaItemIndex).put("items", items)
            }
        }
        "playlists" -> JSONObject().put("playlists", JSONArray().apply {
            for (p in app.allPlaylists()) put(
                JSONObject().put("id", p.id).put("name", p.name).put("songs", p.entries.size)
                    .put("on_server", p.onServer).put("media_id", BrowseTree.PLAYLIST + p.id)
            )
        })
        else -> throw IllegalArgumentException("no method $method (try version)")
    }

    // ---- the controller, on the main thread ----

    private val main = Handler(Looper.getMainLooper())

    /** Calls [block] (off the main thread) with a controller on the playback service, then lets it go. */
    private fun <T> withController(block: (MediaController) -> T): T {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).setApplicationLooper(Looper.getMainLooper()).buildAsync()
        val controller = try {
            future.get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            future.cancel(true)
            throw IllegalStateException("can't reach the player: ${e.message}")
        }
        try {
            return block(controller)
        } finally {
            main.post { controller.release() }
        }
    }

    /**
     * Runs [command] on the main thread, then reads the status until [until]
     * holds (up to 5 s) or, without one, after [settleMs]: commands reach the
     * player as main-thread messages, so the state changes a moment later.
     */
    private fun control(
        settleMs: Long = 0,
        until: ((JSONObject) -> Boolean)? = null,
        command: (MediaController) -> Unit = {},
    ): JSONObject = withController { c ->
        onMain { command(c) }
        if (until == null) {
            if (settleMs > 0) Thread.sleep(settleMs)
            return@withController onMain { status(c) }
        }
        val deadline = System.currentTimeMillis() + 5_000
        var s = onMain { status(c) }
        while (!until(s) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            s = onMain { status(c) }
        }
        s
    }

    /**
     * Starts playback (of [arg] if given). With the app in the background,
     * Android 17's audio hardening refuses the first audio focus request: it
     * comes a moment before the music service is in the foreground, so the
     * player stays paused. (adb puts the app on the temporary allow-list
     * first, so the service may start.) Asked again once the service is in
     * the foreground, focus is granted: so if it isn't playing after 1.5 s,
     * pause and play once more. Seen on the Pixel 9, 2026-11-17.
     */
    private fun play(c: MediaController, arg: String?): JSONObject {
        onMain {
            if (!arg.isNullOrBlank()) {
                c.setMediaItem(ControlArgs.item(arg))
                c.prepare()
            } else if (c.playbackState == Player.STATE_IDLE) {
                c.prepare()
            }
            c.play()
        }
        val done = { s: JSONObject -> s.getString("state") in setOf("playing", "ended", "error") }
        fun waitUntil(ms: Long): JSONObject {
            val deadline = System.currentTimeMillis() + ms
            var s = onMain { status(c) }
            while (!done(s) && System.currentTimeMillis() < deadline) {
                Thread.sleep(200)
                s = onMain { status(c) }
            }
            return s
        }
        val s = waitUntil(1_500)
        if (!arg.isNullOrBlank() && s.getInt("queue_size") == 0) {
            return s.put("error", "nothing in the library matches \"${arg.removePrefix("search:")}\"")
        }
        if (done(s) || s.getString("state") == "loading") return if (done(s)) s else waitUntil(5_000)
        // Pause and play in one go don't ask for focus again: leave a moment between.
        onMain { c.pause() }
        Thread.sleep(400)
        onMain { c.play() }
        return waitUntil(5_000)
    }

    private fun <T> onMain(block: () -> T): T {
        val result = CompletableFuture<T>()
        main.post { try { result.complete(block()) } catch (e: Throwable) { result.completeExceptionally(e) } }
        return try {
            result.get(15, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception) ?: e
        }
    }

    private fun itemJson(item: MediaItem): JSONObject {
        val m = item.mediaMetadata
        return JSONObject()
            .put("media_id", item.mediaId)
            .put("title", m.title?.toString().orEmpty())
            .put("artist", m.artist?.toString().orEmpty())
            .put("album", m.albumTitle?.toString().orEmpty())
    }

    private fun status(c: MediaController): JSONObject {
        // "loading" only when it's meant to play: a restored queue buffers while paused.
        val state = when {
            c.playerError != null -> "error"
            c.isPlaying -> "playing"
            c.playbackState == Player.STATE_ENDED -> "ended"
            c.playbackState == Player.STATE_IDLE && c.mediaItemCount == 0 -> "stopped"
            c.playWhenReady -> "loading"
            else -> "paused"
        }
        val o = JSONObject()
            .put("state", state)
            .put("position_ms", c.currentPosition)
            .put("duration_ms", c.duration.takeIf { it != C.TIME_UNSET } ?: -1)
            .put("shuffle", c.shuffleModeEnabled)
            .put("repeat", ControlArgs.repeatName(c.repeatMode))
            .put("queue_index", c.currentMediaItemIndex)
            .put("queue_size", c.mediaItemCount)
        c.currentMediaItem?.let { o.put("song", itemJson(it)) }
        c.playerError?.let { o.put("error", it.message) }
        return o
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}

/** The control channel's arguments and rules, without Android (unit-tested). */
object ControlArgs {
    const val API_VERSION = 1
    val METHODS = listOf("version", "status", "play", "pause", "toggle", "next", "prev", "seek", "shuffle", "repeat", "queue", "playlists")

    /** adb's shell user (2000) and root (0) only. */
    fun allowed(uid: Int) = uid == 2000 || uid == 0

    /** "1:30", "90", "1:02:03", "90s", "1500ms" -> milliseconds. */
    fun millis(s: String): Long {
        val t = s.trim().lowercase()
        if (t.endsWith("ms")) return t.removeSuffix("ms").trim().toLong()
        val parts = t.removeSuffix("s").split(':').map { it.trim().toDouble() }
        require(parts.isNotEmpty() && parts.size <= 3 && parts.all { it >= 0 }) { "a time like 1:30 or 90" }
        return (parts.fold(0.0) { acc, x -> acc * 60 + x } * 1000).toLong()
    }

    fun onOff(s: String?, now: Boolean): Boolean = when (s?.trim()?.lowercase()) {
        null, "", "toggle" -> !now
        "on", "true", "1", "yes" -> true
        "off", "false", "0", "no" -> false
        else -> throw IllegalArgumentException("on or off, not $s")
    }

    fun repeat(s: String?): Int = when (s?.trim()?.lowercase()) {
        "off", "none", "0" -> Player.REPEAT_MODE_OFF
        "all", "on", "queue" -> Player.REPEAT_MODE_ALL
        "one", "song", "track" -> Player.REPEAT_MODE_ONE
        else -> throw IllegalArgumentException("repeat: off, all or one")
    }

    fun repeatName(mode: Int) = when (mode) {
        Player.REPEAT_MODE_ONE -> "one"
        Player.REPEAT_MODE_ALL -> "all"
        else -> "off"
    }

    /**
     * What to play: a browse-tree media ID ("playlist:…", "album:…", "artist:…",
     * "t:…"), else words to search for, like a voice request ("search:" optional).
     */
    fun item(arg: String): MediaItem {
        val a = arg.trim()
        val ids = listOf(BrowseTree.PLAYLIST, BrowseTree.ALBUM, BrowseTree.ARTIST, BrowseTree.TRACK, BrowseTree.GENRE, BrowseTree.DECADE)
        if (!a.startsWith("search:") && ids.any { a.startsWith(it) }) return MediaItem.Builder().setMediaId(a).build()
        val words = a.removePrefix("search:").trim()
        return MediaItem.Builder()
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(words).build())
            .build()
    }
}
