package com.jimboyo.localbeat.server

import java.net.URI
import java.net.URLDecoder

/**
 * A music server's settings from a `localbeat://connect` link: the second QR
 * code that LocalBeatServer's `localbeat-people` and admin page show, opened
 * with the phone's camera (LocalBeat has no scanner and no camera permission):
 *
 *     localbeat://connect?server=http%3A%2F%2F10.8.0.1%3A4533&user=NAME&secret=…
 *
 * Only the Connected edition declares the link (its own manifest), and the
 * settings change only after the user confirms. Plain JVM code, so it's tested
 * without Android.
 */
data class ConnectLink(val address: String, val username: String, val password: String) {
    companion object {
        /** The link's settings, or null if it isn't a valid `localbeat://connect` link. */
        fun parse(link: String): ConnectLink? {
            val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals("localbeat", ignoreCase = true) || !uri.host.equals("connect", ignoreCase = true)) return null
            val query = uri.rawQuery ?: return null
            val params = query.split('&').mapNotNull { pair ->
                val (k, v) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                runCatching { URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8) }.getOrNull()
            }.toMap()
            val address = params["server"]?.trim()?.trimEnd('/') ?: return null
            val user = params["user"]?.trim().orEmpty()
            val secret = params["secret"].orEmpty()
            if (!validAddress(address) || user.isEmpty() || user.length > 64 || secret.isEmpty() || secret.length > 256) return null
            if (user.any { it.isWhitespace() || it.isISOControl() } || secret.any { it.isISOControl() }) return null
            return ConnectLink(address, user, secret)
        }

        /** `http(s)://host[:port]`, nothing after it (no path, no login in the URL). */
        private fun validAddress(address: String): Boolean {
            val u = runCatching { URI(address) }.getOrNull() ?: return false
            return (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrEmpty() &&
                u.rawUserInfo == null && u.rawPath.isNullOrEmpty() && u.rawQuery == null && u.rawFragment == null
        }
    }
}
