package com.jimboyo.localbeat

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.jimboyo.localbeat.audio.Eq
import com.jimboyo.localbeat.audio.EqState
import com.jimboyo.localbeat.audio.EqualizerProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** duplicate() resets the byte order to big-endian; audio buffers are native order. */
private fun ByteBuffer.dup(): ByteBuffer = duplicate().order(ByteOrder.nativeOrder())

class EqualizerTest {
    private val rate = 48_000

    private fun gains(vararg pairs: Pair<Int, Float>) = Eq.FLAT.toMutableList().also { g -> pairs.forEach { (i, db) -> g[i] = db } }

    /** Stereo 16-bit sine, half scale. */
    private fun sine(freq: Double, seconds: Double = 0.5): ByteBuffer {
        val n = (rate * seconds).toInt()
        val b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until n) {
            val s = (0.5 * sin(2 * PI * freq * i / rate) * 32767).toInt().toShort()
            b.putShort(s); b.putShort(s)
        }
        b.flip()
        return b
    }

    private fun run(state: EqState, input: ByteBuffer): ByteBuffer {
        val p = EqualizerProcessor { state }
        p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        p.queueInput(input)
        val out = p.output
        return ByteBuffer.allocate(out.remaining()).order(ByteOrder.nativeOrder()).put(out).flip() as ByteBuffer
    }

    /** RMS level in dB of the second half (after the filters settle). */
    private fun levelDb(b: ByteBuffer): Double {
        val shorts = b.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val n = shorts.remaining()
        var sum = 0.0
        for (i in n / 2 until n) { val v = shorts[i] / 32768.0; sum += v * v }
        return 20 * log10(sqrt(sum / (n - n / 2)))
    }

    @Test
    fun offOrFlatLeavesAudioUntouched() {
        val input = sine(1000.0)
        val bytes = ByteArray(input.remaining()).also { input.dup().get(it) }
        for (s in listOf(EqState(enabled = false, gains = gains(5 to 6f)), EqState(enabled = true))) {
            val out = run(s, input.dup())
            assertArrayEquals(bytes, ByteArray(out.remaining()).also { out.get(it) })
        }
    }

    @Test
    fun cutLowersThatBand() {
        val input = sine(1000.0)
        val before = levelDb(input)
        val after = levelDb(run(EqState(true, gains(5 to -6f)), input.dup())) // 1 kHz band
        assertEquals(-6.0, after - before, 0.3)
    }

    @Test
    fun boostIsBalancedByLowerVolumeSoItCannotClip() {
        val g = gains(1 to 8f)          // +8 dB at 62 Hz
        val low = sine(62.5)
        val high = sine(4000.0)
        // The boosted band comes out at about its original level; everything else is quieter.
        assertEquals(0.0, levelDb(run(EqState(true, g), low.dup())) - levelDb(low), 0.5)
        assertEquals(-Eq.peakDb(g), levelDb(run(EqState(true, g), high.dup())) - levelDb(high), 0.5)
    }

    @Test
    fun graphMatchesBandGains() {
        val g = gains(3 to 6f)
        assertEquals(6.0, Eq.responseDb(g, 250.0), 0.2)
        assertEquals(0.0, Eq.responseDb(g, 8000.0), 0.2)
        assertEquals(0.0, Eq.peakDb(Eq.FLAT), 0.0)
        Eq.PRESETS.forEach { assertEquals(it.name, Eq.BANDS, it.gains.size) }
    }
}
