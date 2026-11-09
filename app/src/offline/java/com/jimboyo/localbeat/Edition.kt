package com.jimboyo.localbeat

import com.jimboyo.localbeat.server.MusicServer

/** The offline edition: no network code, so no music server. */
object Edition {
    fun musicServer(app: LocalBeatApp): MusicServer? = null
}
