package com.jimboyo.localbeat.server

import org.json.JSONObject
import java.io.Closeable

/**
 * JNI binding to `liblocalbeat_tunnel.so` (built from `tunnel-jni/`): runs a
 * pure-Rust userspace WireGuard + smoltcp loopback HTTP bridge in-process
 * without Android `VpnService` or a separate WireGuard app.
 */
object NativeTunnel {
    val available: Boolean = try {
        System.loadLibrary("localbeat_tunnel")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    @JvmStatic external fun nativeStart(configJson: String): Long
    @JvmStatic external fun nativeBaseUrl(handle: Long, targetPort: Int): String?
    @JvmStatic external fun nativeState(handle: Long): Int
    @JvmStatic external fun nativeReprobe(handle: Long)
    @JvmStatic external fun nativeStop(handle: Long)
    @JvmStatic external fun nativeGenerateWgKeypair(): String?
}

data class WgKeypair(val privateKey: String, val publicKey: String)

/** Active in-process WireGuard + loopback HTTP bridge. */
class EmbeddedTunnelBridge private constructor(
    private var handle: Long,
    val config: EmbeddedTunnelConfig,
) : Closeable {

    @Synchronized
    fun baseUrlFor(targetPort: Int): String? {
        if (handle == 0L) return null
        return NativeTunnel.nativeBaseUrl(handle, targetPort)
    }

    @Synchronized
    fun state(): TunnelLinkState {
        if (handle == 0L) return TunnelLinkState.Off
        return when (NativeTunnel.nativeState(handle)) {
            1 -> TunnelLinkState.ConnectedLan
            2 -> TunnelLinkState.ConnectedPublic
            3 -> TunnelLinkState.Stalled
            else -> TunnelLinkState.Connecting
        }
    }

    @Synchronized
    fun reprobe() {
        if (handle != 0L) NativeTunnel.nativeReprobe(handle)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            NativeTunnel.nativeStop(handle)
            handle = 0L
        }
    }

    companion object {
        fun start(config: EmbeddedTunnelConfig): EmbeddedTunnelBridge? {
            if (!NativeTunnel.available || !config.hasEndpoint()) return null
            val h = NativeTunnel.nativeStart(config.toJson())
            return if (h != 0L) EmbeddedTunnelBridge(h, config) else null
        }

        fun generateWgKeypair(): WgKeypair? {
            if (!NativeTunnel.available) return null
            val json = NativeTunnel.nativeGenerateWgKeypair() ?: return null
            val j = JSONObject(json)
            val priv = j.optString("private", "")
            val pub = j.optString("public", "")
            return if (priv.isNotBlank() && pub.isNotBlank()) WgKeypair(priv, pub) else null
        }
    }
}
