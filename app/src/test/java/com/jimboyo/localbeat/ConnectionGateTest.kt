package com.jimboyo.localbeat

import com.jimboyo.localbeat.server.ConnectionGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionGateTest {
    private val steady = 120_000L

    @Test fun firstAnswerConnects() {
        val g = ConnectionGate(steady)
        assertTrue(g.onSuccess(1_000))
        assertTrue(g.connected)
    }

    @Test fun failureDisconnectsAtOnce() {
        val g = ConnectionGate(steady)
        g.connectNow()
        assertTrue(g.onFailure(5_000))
        assertFalse(g.connected)
        assertFalse("already disconnected", g.onFailure(6_000))
    }

    @Test fun reconnectsOnlyOnceSteady() {
        val g = ConnectionGate(steady)
        g.connectNow()
        g.onFailure(0)
        assertFalse(g.onSuccess(15_000))
        assertFalse(g.onSuccess(119_999))
        assertTrue(g.onSuccess(120_000))
        assertTrue(g.connected)
    }

    @Test fun patchyCoverageStaysDisconnected() {
        val g = ConnectionGate(steady)
        g.connectNow()
        g.onFailure(0)
        // Answers come and go: every failure restarts the wait.
        var t = 0L
        repeat(20) {
            t += 15_000; assertFalse(g.onSuccess(t))
            t += 15_000; assertFalse(g.onSuccess(t))
            t += 15_000; g.onFailure(t)
        }
        assertFalse(g.connected)
        assertTrue(g.onSuccess(t + steady))
    }

    @Test fun longIdleThenAnswerConnectsAtOnce() {
        // Disconnected in the car, app opened at home an hour later: no 2-minute wait.
        val g = ConnectionGate(steady)
        g.connectNow()
        g.onFailure(0)
        assertTrue(g.onSuccess(3_600_000))
    }

    @Test fun connectNowSkipsTheWait() {
        val g = ConnectionGate(steady)
        g.onFailure(0)
        g.connectNow()
        assertTrue(g.connected)
        g.onFailure(1_000)
        g.reset()
        assertFalse(g.connected)
        assertTrue("reset forgets the last failure", g.onSuccess(2_000))
    }
}
