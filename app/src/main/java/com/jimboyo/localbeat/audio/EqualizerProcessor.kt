package com.jimboyo.localbeat.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * Runs the decoded audio through the equaliser. It sits in ExoPlayer's audio
 * sink, so it applies wherever LocalBeat plays: speaker, headphones,
 * Bluetooth, Android Auto. [settings] is read for every buffer, so changes are
 * heard within a few milliseconds.
 */
@UnstableApi
class EqualizerProcessor(private val settings: () -> EqState) : BaseAudioProcessor() {
    private var applied: EqState? = null
    private var active = false
    private var channels = 0
    private var sampleRate = 0
    private var volume = 1f
    // Coefficients per band, and two state values per band per channel.
    private val b0 = FloatArray(Eq.BANDS); private val b1 = FloatArray(Eq.BANDS); private val b2 = FloatArray(Eq.BANDS)
    private val a1 = FloatArray(Eq.BANDS); private val a2 = FloatArray(Eq.BANDS)
    private var z = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        z = FloatArray(channels * Eq.BANDS * 2)
        applied = null
    }

    override fun onReset() {
        z = FloatArray(0)
        applied = null
    }

    private fun update(s: EqState) {
        applied = s
        active = s.active && sampleRate > 0
        if (!active) { z.fill(0f); return }
        for (i in 0 until Eq.BANDS) {
            val c = Eq.coefficients(Eq.FREQS[i], s.gains[i], sampleRate)
            b0[i] = c[0].toFloat(); b1[i] = c[1].toFloat(); b2[i] = c[2].toFloat(); a1[i] = c[3].toFloat(); a2[i] = c[4].toFloat()
        }
        volume = 10.0.pow(-Eq.peakDb(s.gains) / 20).toFloat()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val s = settings()
        if (s !== applied) update(s)
        val out = replaceOutputBuffer(size)
        if (!active || channels == 0) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        var ch = 0
        while (inputBuffer.hasRemaining()) {
            var y = if (isFloat) inputBuffer.getFloat() else inputBuffer.getShort() / 32768f
            var k = ch * Eq.BANDS * 2
            for (i in 0 until Eq.BANDS) {
                // Transposed direct form II biquad.
                val x = y
                y = b0[i] * x + z[k]
                z[k] = b1[i] * x - a1[i] * y + z[k + 1]
                z[k + 1] = b2[i] * x - a2[i] * y
                k += 2
            }
            y = (y * volume).coerceIn(-1f, 1f)
            if (isFloat) out.putFloat(y) else out.putShort((y * 32767f).toInt().toShort())
            ch = if (ch + 1 == channels) 0 else ch + 1
        }
        out.flip()
    }
}
