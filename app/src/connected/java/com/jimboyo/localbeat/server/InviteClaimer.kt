package com.jimboyo.localbeat.server

import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Claims a one-time `localbeat://join/...` invite over an ephemeral embedded
 * WireGuard bootstrap tunnel (docs/onboarding.md).
 */
object InviteClaimer {

    data class ClaimedResult(
        val deviceName: String,
        val user: String,
        val settings: ServerSettings,
    )

    /**
     * Parses [inviteText], generates a permanent WireGuard keypair locally,
     * dials the bootstrap tunnel, calls `POST /claim` (with one transient retry),
     * invokes [saveSettings] durably before sending `DELETE /claim` ACK, and
     * tears down the bootstrap tunnel.
     */
    fun claim(
        inviteText: String,
        currentSettings: ServerSettings,
        saveSettings: (ServerSettings) -> Unit,
    ): Result<ClaimedResult> = runCatching {
        val invite = InviteLink.parse(inviteText).getOrThrow()
        val keys = EmbeddedTunnelBridge.generateWgKeypair()
            ?: throw IOException("Built-in WireGuard library isn't available in this build")
        val bridge = EmbeddedTunnelBridge.start(invite.bootstrapTunnel())
            ?: throw IOException("Couldn't start temporary WireGuard tunnel for invite claim")

        bridge.use {
            val base = it.baseUrlFor(InviteLink.CLAIM_PORT)
                ?: throw IOException("Temporary tunnel proxy didn't start")
            val model = Build.MODEL.orEmpty().ifBlank { "Android phone" }
            val suggested = model.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "phone" }

            val responseJson = try {
                postClaim(base, invite.token, keys.publicKey, model, suggested)
            } catch (first: IOException) {
                val msg = first.message.orEmpty()
                if (msg.contains("expired") || msg.contains("already used") || msg.contains("admin computer")) {
                    throw first
                }
                Thread.sleep(800)
                postClaim(base, invite.token, keys.publicKey, model, suggested)
            }

            val name = responseJson.getString("name")
            val user = responseJson.optString("user", "")
            val tun = responseJson.getJSONObject("tunnel")
            val music = responseJson.getJSONObject("music")

            val permanentTunnel = EmbeddedTunnelConfig(
                privateKey = keys.privateKey,
                address = tun.getString("address"),
                serverKey = tun.getString("server_key"),
                presharedKey = tun.optString("preshared_key", ""),
                endpointPublic = tun.optString("endpoint_public", "").ifBlank { null },
                endpointLan = tun.optString("endpoint_lan", "").ifBlank { null },
                allowedIps = tun.optString("allowed_ips", "${InviteLink.SERVER_TUNNEL_IP}/32"),
            )
            val newSettings = currentSettings.copy(
                enabled = true,
                address = music.getString("server"),
                username = music.getString("login"),
                password = music.getString("secret"),
                tunnel = permanentTunnel,
            )

            // Save durably BEFORE sending DELETE /claim ACK.
            saveSettings(newSettings)
            runCatching { deleteClaimAck(base, invite.token) }

            ClaimedResult(deviceName = name, user = user, settings = newSettings)
        }
    }

    private fun postClaim(
        baseUrl: String,
        token: String,
        wgPublicKey: String,
        deviceLabel: String,
        suggestedName: String,
    ): JSONObject {
        val body = JSONObject().apply {
            put("token", token)
            put("wireguard_key", wgPublicKey)
            put("device_kind", "phone")
            put("device_label", deviceLabel)
            put("suggested_name", suggestedName)
        }.toString()

        val conn = (URL("${baseUrl.trimEnd('/')}/claim").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return when (code) {
                200 -> JSONObject(text)
                404, 410 -> throw IOException("This invite has expired or was already used — ask the admin for a new one.")
                else -> {
                    val err = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                    throw IOException(err.ifBlank { "Server answered HTTP $code" })
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun deleteClaimAck(baseUrl: String, token: String) {
        val conn = (URL("${baseUrl.trimEnd('/')}/claim").openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}
