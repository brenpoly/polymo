package com.digitalpet.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Sample-rate conversion for the pet's speaker link.
 *
 * Piper synthesises at 22050 Hz, which Opus does not accept — it takes only
 * 8/12/16/24/48 kHz. The pet's microphone path is already 16 kHz end to end,
 * so TTS is converted *down* to 16 kHz rather than up to 24 kHz: speech band is
 * plenty for a speaker this size, it costs a third less airtime, and it leaves
 * exactly one audio format in the system, which is the property that made the
 * uplink easy to reason about.
 *
 * Downsampling needs a low-pass first. Piper's output carries energy up to
 * 11 kHz, and anything above 8 kHz would fold back into the speech band as
 * aliasing — audible as a metallic edge on sibilants, and exactly the kind of
 * damage a listener blames on the codec.
 */
object Resampler {

    /**
     * Half-length of the anti-alias FIR. 32 taps either side is a good trade
     * here: the transition band is comfortably inside the gap between 7.2 kHz
     * and Nyquist, and a few thousand multiply-adds per sentence is nothing
     * next to the synthesis that produced the audio.
     */
    private const val HALF_TAPS = 32

    /**
     * Cut-off as a fraction of the *output* sample rate. 0.45 puts the corner
     * at 7.2 kHz for a 16 kHz target, leaving room for the filter to roll off
     * before the 8 kHz fold point rather than aliasing through the transition.
     */
    private const val CUTOFF_RATIO = 0.45

    /**
     * Convert [pcm] from [fromRate] to [toRate].
     *
     * Returns the input unchanged when the rates already match, so callers can
     * apply this unconditionally and a voice that happens to be 16 kHz costs
     * nothing.
     */
    fun resample(pcm: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || pcm.isEmpty()) return pcm

        val filtered = if (toRate < fromRate) {
            lowPass(pcm, cutoffHz = toRate * CUTOFF_RATIO, sampleRate = fromRate)
        } else {
            // Upsampling cannot alias — there is no content above the old
            // Nyquist to fold — so interpolation alone is honest.
            pcm
        }

        val ratio = fromRate.toDouble() / toRate
        val outLen = (pcm.size / ratio).toInt()
        val out = ShortArray(outLen)

        for (i in 0 until outLen) {
            val src = i * ratio
            val i0 = src.toInt()
            val i1 = (i0 + 1).coerceAtMost(filtered.size - 1)
            val frac = src - i0
            val v = filtered[i0] * (1 - frac) + filtered[i1] * frac
            out[i] = v.roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /**
     * Windowed-sinc low-pass, applied in place of a proper polyphase filter.
     *
     * A Hamming window keeps the stop-band down around -50 dB, which is well
     * below anything this speaker will reproduce.
     */
    private fun lowPass(pcm: ShortArray, cutoffHz: Double, sampleRate: Int): ShortArray {
        val fc = cutoffHz / sampleRate
        val taps = DoubleArray(2 * HALF_TAPS + 1)
        var sum = 0.0

        for (n in -HALF_TAPS..HALF_TAPS) {
            val sinc = if (n == 0) {
                2 * fc
            } else {
                sin(2 * PI * fc * n) / (PI * n)
            }
            // Hamming
            val w = 0.54 - 0.46 * cos(2 * PI * (n + HALF_TAPS) / (2 * HALF_TAPS))
            val t = sinc * w
            taps[n + HALF_TAPS] = t
            sum += t
        }
        // Normalise to unity gain at DC, so the filter cannot change loudness.
        for (i in taps.indices) taps[i] /= sum

        // Filter into doubles first. Piper's output already peaks near full
        // scale, and a windowed-sinc overshoots on transients, so rounding
        // straight to Int16 would hard-clip — silently, and audibly.
        val wide = DoubleArray(pcm.size)
        var peak = 0.0
        for (i in pcm.indices) {
            var acc = 0.0
            for (k in taps.indices) {
                val j = i + k - HALF_TAPS
                // Clamp at the edges rather than zero-padding, which would put a
                // click at the start and end of every sentence.
                val s = pcm[j.coerceIn(0, pcm.size - 1)]
                acc += s * taps[k]
            }
            wide[i] = acc
            val a = abs(acc)
            if (a > peak) peak = a
        }

        // Scale back only when the filter actually overshot, so quiet speech is
        // not needlessly attenuated.
        val gain = if (peak > 32767.0) 32767.0 / peak else 1.0

        val out = ShortArray(pcm.size)
        for (i in wide.indices) {
            out[i] = (wide[i] * gain).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Peak absolute sample, for logging what a conversion did to the level. */
    fun peak(pcm: ShortArray): Int {
        var hi = 0
        for (s in pcm) {
            val a = abs(s.toInt())
            if (a > hi) hi = a
        }
        return hi
    }
}
