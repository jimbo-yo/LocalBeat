package com.jimboyo.localbeat.server

import org.json.JSONObject
import java.util.Base64

/**
 * One-time onboarding invite (`localbeat://join/<base64url-no-pad>`), shared
 * with LocalBeatServer and LocalBeatStudio (docs/onboarding.md).
 *
 * Plain JVM code so it is tested without Android.
 */
data class InviteLink(
    val version: Int,
    val token: String,
    val bootstrapPrivateKey: String,
    val bootstrapPresharedKey: String,
    val bootstrapAddress: String,
    val serverKey: String,
    val endpointPublic: String?,
    val endpointLan: String?,
    val role: String,
) {
    fun bootstrapTunnel(): EmbeddedTunnelConfig = EmbeddedTunnelConfig(
        privateKey = bootstrapPrivateKey,
        address = bootstrapAddress,
        serverKey = serverKey,
        presharedKey = bootstrapPresharedKey,
        endpointPublic = endpointPublic,
        endpointLan = endpointLan,
        allowedIps = "$SERVER_TUNNEL_IP/32",
    )

    companion object {
        const val PREFIX = "localbeat://join/"
        const val SCHEMA_VERSION = 1
        const val SERVER_TUNNEL_IP = "10.8.0.1"
        const val CLAIM_PORT = 8444

        private val URI_REGEX = Regex("localbeat://join/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)

        /** Extracts the first `localbeat://join/<payload>` URI from arbitrary pasted text (e.g. an SMS). */
        fun extract(text: String): String? = URI_REGEX.find(text)?.value

        /** Extracts, decodes, and validates an invite for a phone. */
        fun parse(input: String): Result<InviteLink> = runCatching {
            val match = URI_REGEX.find(input)
                ?: throw IllegalArgumentException("No LocalBeat invite found (expected a localbeat://join/... link)")
            val payload = match.groupValues[1]
            val jsonBytes = Base64.getUrlDecoder().decode(payload)
            val j = JSONObject(String(jsonBytes, Charsets.UTF_8))
            val version = j.optInt("v", -1)
            if (version != SCHEMA_VERSION) {
                throw IllegalArgumentException("Unsupported invite version $version — update LocalBeat and try again")
            }
            val token = j.optString("t", "").trim()
            val priv = j.optString("k", "").trim()
            val psk = j.optString("p", "").trim()
            val addr = j.optString("a", "").trim()
            val srvKey = j.optString("s", "").trim()
            val epPub = j.optString("e", "").trim().ifBlank { null }
            val epLan = j.optString("l", "").trim().ifBlank { null }
            val role = j.optString("r", "listener").trim().lowercase()

            if (token.isEmpty()) throw IllegalArgumentException("Invalid invite link (missing token)")
            if (priv.isEmpty() || psk.isEmpty() || addr.isEmpty() || srvKey.isEmpty()) {
                throw IllegalArgumentException("Invalid invite link (missing tunnel details)")
            }
            if (epPub == null && epLan == null) {
                throw IllegalArgumentException("Invalid invite link (missing server endpoint)")
            }
            if (role == "admin") {
                throw IllegalArgumentException("This invite is for an admin computer (LocalBeat Studio).")
            }
            InviteLink(
                version = version,
                token = token,
                bootstrapPrivateKey = priv,
                bootstrapPresharedKey = psk,
                bootstrapAddress = addr,
                serverKey = srvKey,
                endpointPublic = epPub,
                endpointLan = epLan,
                role = role,
            )
        }
    }
}
