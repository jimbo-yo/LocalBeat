package com.jimboyo.localbeat.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The equaliser's settings: on/off and one gain (dB) per band in [Eq.FREQS]. */
data class EqState(val enabled: Boolean = false, val gains: List<Float> = Eq.FLAT) {
    /** True when it changes the sound at all. */
    val active get() = enabled && gains.any { it != 0f }
}

data class EqPreset(val name: String, val gains: List<Float>, val builtIn: Boolean = true)

/**
 * A 10-band graphic equaliser: one peaking filter per octave band (RBJ audio EQ
 * cookbook biquads), the same on every phone. The filter math lives here so the
 * screen can draw exactly the curve the audio gets.
 */
object Eq {
    val FREQS = floatArrayOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    val BANDS = FREQS.size
    val FLAT = List(BANDS) { 0f }
    const val MAX_DB = 12f
    /** About one octave wide, so neighbouring bands blend into a smooth curve. */
    private const val Q = 1.41

    val PRESETS = listOf(
        EqPreset("Flat", FLAT),
        EqPreset("Bass Boost", listOf(6f, 5f, 4f, 2f, 0.5f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("Bass Cut", listOf(-6f, -5f, -4f, -2f, -0.5f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("Treble Boost", listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 2f, 4f, 5f, 6f)),
        EqPreset("Treble Cut", listOf(0f, 0f, 0f, 0f, 0f, -0.5f, -2f, -4f, -5f, -6f)),
        EqPreset("Loudness", listOf(5f, 4f, 2f, 0f, -1f, 0f, -1f, 1f, 3f, 4f)),
        EqPreset("Rock", listOf(4f, 3f, 2f, 0f, -1f, -1f, 1f, 2f, 3f, 4f)),
        EqPreset("Pop", listOf(-1f, 0f, 2f, 3f, 3f, 2f, 0f, -1f, -1f, -1f)),
        EqPreset("Jazz", listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f)),
        EqPreset("Classical", listOf(3f, 2f, 1f, 0f, 0f, 0f, -1f, -1f, 1f, 2f)),
        EqPreset("Acoustic", listOf(3f, 3f, 2f, 1f, 1f, 1f, 2f, 2f, 2f, 1f)),
        EqPreset("Electronic", listOf(4f, 4f, 1f, 0f, -2f, 0f, 1f, 1f, 3f, 4f)),
        EqPreset("Hip-Hop", listOf(5f, 4f, 2f, 3f, -1f, -1f, 1f, 0f, 1f, 2f)),
        EqPreset("Vocal", listOf(-2f, -2f, -1f, 0f, 2f, 3f, 3f, 2f, 0f, -1f)),
    )

    /** Normalised biquad coefficients [b0, b1, b2, a1, a2] for one band. */
    fun coefficients(freq: Float, gainDb: Float, sampleRate: Int): DoubleArray {
        // A band at or above Nyquist can't be represented: leave it out.
        if (gainDb == 0f || freq >= sampleRate * 0.45) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * freq / sampleRate
        val alpha = sin(w0) / (2 * Q)
        val cw = cos(w0)
        val a0 = 1 + alpha / a
        return doubleArrayOf(
            (1 + alpha * a) / a0, (-2 * cw) / a0, (1 - alpha * a) / a0,
            (-2 * cw) / a0, (1 - alpha / a) / a0,
        )
    }

    /** The combined response, in dB, at [freq] Hz. */
    fun responseDb(gains: List<Float>, freq: Double, sampleRate: Int = 48_000): Double {
        val w = 2 * PI * freq / sampleRate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        var db = 0.0
        for (i in 0 until BANDS) {
            if (gains[i] == 0f) continue
            val c = coefficients(FREQS[i], gains[i], sampleRate)
            val b0 = c[0]; val b1 = c[1]; val b2 = c[2]; val a1 = c[3]; val a2 = c[4]
            val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
            val dr = 1 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
            db += 20 * log10(sqrt((nr * nr + ni * ni) / (dr * dr + di * di)))
        }
        return db
    }

    /** The highest point of the curve: that much is taken off the volume so boosts can't clip. */
    fun peakDb(gains: List<Float>): Double {
        if (gains.all { it <= 0f }) return 0.0
        var peak = 0.0
        var f = 20.0
        while (f <= 20_000) { peak = max(peak, responseDb(gains, f)); f *= 1.03 }
        return peak
    }
}
