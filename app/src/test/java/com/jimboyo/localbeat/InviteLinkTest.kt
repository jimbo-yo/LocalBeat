package com.jimboyo.localbeat

import com.jimboyo.localbeat.server.InviteLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class InviteLinkTest {
    private fun encode(json: String): String =
        "localbeat://join/" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))

    @Test
    fun parsesValidInviteAndExtractsFromSmsProse() {
        val uri = encode(
            """{"v":1,"t":"tok123","k":"PRIV==","p":"PSK==","a":"10.8.250.2/32","s":"SRV==","e":"vpn.example.com:51820","l":"192.168.1.10:51820","r":"listener"}"""
        )
        val sms = "Hey Mom! Open this in LocalBeat: ${uri.replaceFirst("localbeat://join/", "LocalBeat://JOIN/")}! Enjoy."
        assertTrue(InviteLink.extract(sms) != null)
        val parsed = InviteLink.parse(sms).getOrThrow()
        assertEquals("tok123", parsed.token)
        assertEquals("192.168.1.10:51820", parsed.endpointLan)
        assertEquals("vpn.example.com:51820", parsed.endpointPublic)
        assertEquals("10.8.250.2/32", parsed.bootstrapTunnel().address)

        val payload = uri.removePrefix(InviteLink.PREFIX)
        val webUrl = "https://jimbo-yo.github.io/LocalBeat/join#$payload"
        val webSms = "See https://example.com or tap ($webUrl)!"
        assertEquals(webUrl, InviteLink.extract(webSms))
        assertEquals("tok123", InviteLink.parse(webSms).getOrThrow().token)
        assertEquals("tok123", InviteLink.parse(webUrl.replace("/join#", "/JOIN/#")).getOrThrow().token)
    }

    @Test
    fun supportsLanOnlyAndRejectsAdminOrMissingEndpoints() {
        val lanOnly = encode(
            """{"v":1,"t":"tok","k":"PRIV==","p":"PSK==","a":"10.8.250.2/32","s":"SRV==","l":"192.168.1.10:51820","r":"listener"}"""
        )
        val parsed = InviteLink.parse(lanOnly).getOrThrow()
        assertNull(parsed.endpointPublic)
        assertEquals("192.168.1.10:51820", parsed.endpointLan)

        val admin = encode(
            """{"v":1,"t":"tok","k":"PRIV==","p":"PSK==","a":"10.8.250.2/32","s":"SRV==","e":"vpn.example.com:51820","r":"admin"}"""
        )
        assertTrue(InviteLink.parse(admin).exceptionOrNull()!!.message!!.contains("LocalBeat Studio"))

        val noEp = encode(
            """{"v":1,"t":"tok","k":"PRIV==","p":"PSK==","a":"10.8.250.2/32","s":"SRV==","r":"listener"}"""
        )
        assertTrue(InviteLink.parse(noEp).isFailure)
    }
}
