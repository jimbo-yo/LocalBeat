package com.jimboyo.localbeat

import androidx.media3.common.Player
import com.jimboyo.localbeat.playback.ControlArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The adb control channel's rules (docs/control.md step 7). */
class ControlArgsTest {
    @Test fun onlyAdbAndRoot() {
        assertTrue(ControlArgs.allowed(2000))
        assertTrue(ControlArgs.allowed(0))
        assertFalse("another app", ControlArgs.allowed(10234))
        assertFalse("system", ControlArgs.allowed(1000))
    }

    @Test fun times() {
        assertEquals(90_000L, ControlArgs.millis("1:30"))
        assertEquals(90_000L, ControlArgs.millis("90"))
        assertEquals(90_000L, ControlArgs.millis("90s"))
        assertEquals(3_723_000L, ControlArgs.millis("1:02:03"))
        assertEquals(1_500L, ControlArgs.millis("1500ms"))
        assertEquals(2_500L, ControlArgs.millis("2.5"))
        for (bad in listOf("", "x", "1:2:3:4", "-5", "-500ms", "Infinity", "NaN")) {
            try { ControlArgs.millis(bad); throw AssertionError("accepted $bad") } catch (e: IllegalArgumentException) { }
        }
    }

    @Test fun itemRecognition() {
        assertEquals("artistall:r123", ControlArgs.item("artistall:r123").mediaId)
        assertEquals("songs", ControlArgs.item("songs").mediaId)
        assertEquals("playlist:p1", ControlArgs.item("playlist:p1").mediaId)
    }

    @Test fun switches() {
        assertTrue(ControlArgs.onOff("on", false))
        assertFalse(ControlArgs.onOff("off", true))
        assertTrue("no value toggles", ControlArgs.onOff(null, false))
        assertFalse(ControlArgs.onOff("toggle", true))
        assertEquals(Player.REPEAT_MODE_ONE, ControlArgs.repeat("one"))
        assertEquals(Player.REPEAT_MODE_ALL, ControlArgs.repeat("all"))
        assertEquals(Player.REPEAT_MODE_OFF, ControlArgs.repeat("off"))
        assertEquals("all", ControlArgs.repeatName(Player.REPEAT_MODE_ALL))
    }
}
