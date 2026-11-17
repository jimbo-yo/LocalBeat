package com.jimboyo.localbeat.server

/**
 * Whether the music server counts as connected, with a steadiness rule so patchy
 * coverage (country roads) doesn't flip the app in and out: a failure disconnects
 * at once, but reconnecting needs [steadyMs] without a failure. Times are from any
 * monotonic clock (`SystemClock.elapsedRealtime()` in the app).
 */
class ConnectionGate(private val steadyMs: Long, private val freshMs: Long = 60_000L) {
    var connected = false
        private set
    /** When the last failure was seen, or null if none since the last reset. */
    private var lastFailureAt: Long? = null
    /** Until when the first answer on a newly joined network connects at once ([newNetwork]). */
    private var freshUntil: Long? = null

    /**
     * The phone just joined a steady network (Wi-Fi after airplane mode, arriving
     * home): the first answer within [freshMs] connects without the wait, even if
     * the tunnel took a few tries to come up. Dropouts on mobile data don't call
     * this, so they still wait [steadyMs].
     */
    fun newNetwork(now: Long) {
        if (!connected) freshUntil = now + freshMs
    }

    /** The server answered. Returns true if that (re)connects. */
    fun onSuccess(now: Long): Boolean {
        if (connected) return false
        val fresh = freshUntil
        freshUntil = null
        if (fresh != null && now <= fresh) {
            connected = true
            lastFailureAt = null
            return true
        }
        val last = lastFailureAt
        if (last != null && now - last < steadyMs) return false
        connected = true
        return true
    }

    /** The server didn't answer (or can't be used, e.g. on mobile data). Returns true if that disconnects. */
    fun onFailure(now: Long): Boolean {
        lastFailureAt = now
        if (!connected) return false
        connected = false
        return true
    }

    /** Connected right now, without waiting (the user asked, or a fresh connection succeeded). */
    fun connectNow() {
        connected = true
        lastFailureAt = null
    }

    /** Back to the start: not connected, no history (server turned off or settings changed). */
    fun reset() {
        connected = false
        lastFailureAt = null
        freshUntil = null
    }
}
