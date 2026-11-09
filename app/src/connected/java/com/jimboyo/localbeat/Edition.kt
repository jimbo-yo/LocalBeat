package com.jimboyo.localbeat

import com.jimboyo.localbeat.server.MusicServer
import com.jimboyo.localbeat.server.SubsonicServer

/** The Connected edition: the user's own music server. */
object Edition {
    fun musicServer(app: LocalBeatApp): MusicServer? = SubsonicServer(app)
}
