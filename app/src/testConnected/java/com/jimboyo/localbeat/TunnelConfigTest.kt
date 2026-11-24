package com.jimboyo.localbeat

import com.jimboyo.localbeat.server.EmbeddedTunnelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelConfigTest {
    @Test
    fun roundTripFullAndLanOnly() {
        val full = EmbeddedTunnelConfig(
            privateKey = "PRIV==",
            address = "10.8.0.12/32",
            serverKey = "SRV==",
            presharedKey = "PSK==",
            endpointPublic = "vpn.example.com:51820",
            endpointLan = "192.168.1.10:51820",
            allowedIps = "10.8.0.1/32",
        )
        assertTrue(full.hasEndpoint())
        assertEquals(full, EmbeddedTunnelConfig.fromJson(full.toJson()))

        val lanOnly = full.copy(endpointPublic = null)
        assertTrue(lanOnly.hasEndpoint())
        val parsed = EmbeddedTunnelConfig.fromJson(lanOnly.toJson())
        assertEquals(lanOnly, parsed)
        assertNull(parsed?.endpointPublic)

        val noEp = full.copy(endpointPublic = null, endpointLan = null)
        assertFalse(noEp.hasEndpoint())
        assertNull(EmbeddedTunnelConfig.fromJson(noEp.toJson()))
    }
}
