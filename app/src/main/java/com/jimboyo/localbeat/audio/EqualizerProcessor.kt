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
    private var volume = 1.0
    // Coefficients per band, and two state values per band per channel (Double avoids low-band quantization at 96/192 kHz).
    private val b0 = DoubleArray(Eq.BANDS); private val b1 = DoubleArray(Eq.BANDS); private val b2 = DoubleArray(Eq.BANDS)
    private val a1 = DoubleArray(Eq.BANDS); private val a2 = DoubleArray(Eq.BANDS)
    private var z = DoubleArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        z = DoubleArray(channels * Eq.BANDS * 2)
        applied = null
    }

    override fun onReset() {
        z = DoubleArray(0)
        applied = null
    }

    private fun update(s: EqState) {
        applied = s
        active = s.active && sampleRate > 0
        if (!active) { z.fill(0.0); return }
        for (i in 0 until Eq.BANDS) {
            val c = Eq.coefficients(Eq.FREQS[i], s.gains[i], sampleRate)
            b0[i] = c[0]; b1[i] = c[1]; b2[i] = c[2]; a1[i] = c[3]; a2[i] = c[4]
        }
        volume = 10.0.pow(-Eq.peakDb(s.gains, sampleRate) / 20)
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
            var y = if (isFloat) inputBuffer.getFloat().toDouble() else inputBuffer.getShort() / 32768.0
            var k = ch * Eq.BANDS * 2
            for (i in 0 until Eq.BANDS) {
                // Transposed direct form II biquad.
                val x = y
                y = b0[i] * x + z[k]
                z[k] = b1[i] * x - a1[i] * y + z[k + 1]
                z[k + 1] = b2[i] * x - a2[i] * y
                k += 2
            }
            val sample = (y * volume).coerceIn(-1.0, 1.0)
            if (isFloat) out.putFloat(sample.toFloat()) else out.putShort((sample * 32767.0).toInt().toShort())
            ch = if (ch + 1 == channels) 0 else ch + 1
        }
        out.flip()
    }
}
