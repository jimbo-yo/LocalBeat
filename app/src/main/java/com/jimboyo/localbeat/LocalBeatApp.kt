package com.jimboyo.localbeat

import android.app.Application
import com.jimboyo.localbeat.art.ArtStore
import com.jimboyo.localbeat.audio.EqualizerStore
import com.jimboyo.localbeat.library.LibraryRepository
import com.jimboyo.localbeat.playback.QueueStore
import com.jimboyo.localbeat.playlists.PlaylistStore
import com.jimboyo.localbeat.server.MusicServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** App-wide singletons, shared by the UI, the playback service and the artwork provider. */
class LocalBeatApp : Application() {
    lateinit var library: LibraryRepository
        private set
    lateinit var art: ArtStore
        private set
    lateinit var queueStore: QueueStore
        private set
    lateinit var playlists: PlaylistStore
        private set
    lateinit var settings: Settings
        private set
    lateinit var equalizer: EqualizerStore
        private set
    /** The user's music server; null in the offline edition. */
    val server: MusicServer? by lazy { Edition.musicServer(this) }

    override fun onCreate() {
        super.onCreate()
        library = LibraryRepository(this)
        art = ArtStore(this) { track -> server?.coverBytes(track) }
        queueStore = QueueStore(this)
        playlists = PlaylistStore(this)
        settings = Settings(this)
        equalizer = EqualizerStore(this)

        // The server's songs join the library while the server is on.
        server?.let { s ->
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                s.tracks.collect { library.setServerTracks(it) }
            }
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                s.offline.collect { library.setKept(it.ready) }
            }
        }

        // After each scan, drop covers of albums that changed or are gone.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            library.generation.drop(1).collect { library.library.value?.let { art.prune(it.albums) } }
        }
    }
}
