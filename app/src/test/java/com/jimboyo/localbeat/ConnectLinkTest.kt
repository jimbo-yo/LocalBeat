package com.jimboyo.localbeat

import com.jimboyo.localbeat.server.ConnectLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectLinkTest {
    // Exactly what LocalBeatServer's localbeat-people prints.
    private val link = "localbeat://connect?server=http%3A%2F%2F10.8.0.1%3A4533&user=my-phone&secret=Xq7mP2vL9sKd4wRt8yBn3cHj"

    @Test fun parsesTheServersLink() {
        assertEquals(ConnectLink("http://10.8.0.1:4533", "my-phone", "Xq7mP2vL9sKd4wRt8yBn3cHj"), ConnectLink.parse(link))
    }

    @Test fun toleratesOrderCaseSpacesAndATrailingSlash() {
        assertEquals(
            ConnectLink("https://music.example:8443", "sam", "s3cret+=/x"),
            ConnectLink.parse("  LocalBeat://Connect?secret=s3cret%2B%3D%2Fx&user=sam&server=https%3A%2F%2Fmusic.example%3A8443%2F  "),
        )
        // An unencoded server value works too.
        assertEquals("http://192.168.1.10:4533", ConnectLink.parse("localbeat://connect?server=http://192.168.1.10:4533&user=a&secret=b")?.address)
    }

    @Test fun rejectsAnythingElse() {
        val bad = listOf(
            "",
            "not a link",
            "https://connect?server=http%3A%2F%2F10.8.0.1%3A4533&user=a&secret=b", // wrong scheme
            "localbeat://other?server=http%3A%2F%2F10.8.0.1%3A4533&user=a&secret=b", // wrong host
            "localbeat://connect", // nothing
            "localbeat://connect?user=a&secret=b", // no server
            "localbeat://connect?server=http%3A%2F%2F10.8.0.1%3A4533&secret=b", // no user
            "localbeat://connect?server=http%3A%2F%2F10.8.0.1%3A4533&user=a", // no secret
            "localbeat://connect?server=ftp%3A%2F%2F10.8.0.1&user=a&secret=b", // not http(s)
            "localbeat://connect?server=javascript%3Aalert(1)&user=a&secret=b",
            "localbeat://connect?server=http%3A%2F%2Fevil%4010.8.0.1&user=a&secret=b", // login in the URL
            "localbeat://connect?server=http%3A%2F%2F10.8.0.1%2Frest&user=a&secret=b", // a path
            "localbeat://connect?server=http%3A%2F%2F10.8.0.1&user=a%20b&secret=c", // space in user
            "localbeat://connect?server=http%3A%2F%2F10.8.0.1&user=a&secret=b%0Ac", // control character
            "localbeat://connect?server=http%3A%2F%2F10.8.0.1&user=a&secret=%ZZ", // bad escape
        )
        for (b in bad) assertNull(b, ConnectLink.parse(b))
    }
}
