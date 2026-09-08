package com.digitalpet.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [Resampler] converts Piper's 22050 Hz output to the 16 kHz the pet decodes.
 *
 * Worth testing because it is pure arithmetic sitting in the audio path where
 * mistakes are inaudible individually and ruinous in aggregate: a wrong length
 * shifts pitch, a missing low-pass folds sibilance back into the speech band,
 * and an overshooting filter clips peaks that were fine on the way in.
 */
class ResamplerTest {

    private companion object {
        const val PIPER = 22_050
        const val PET = 16_000
    }

    /** A sine at [hz], amplitude [amp], lasting [ms] at [rate]. */
    private fun tone(hz: Double, rate: Int, ms: Int, amp: Int = 12_000): ShortArray {
        val n = rate * ms / 1000
        return ShortArray(n) { i ->
            (amp * sin(2 * PI * hz * i / rate)).toInt().toShort()
        }
    }

    private fun rms(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return 0.0
        var acc = 0.0
        for (s in pcm) acc += s.toDouble() * s.toDouble()
        return sqrt(acc / pcm.size)
    }

    // ---- shape -----------------------------------------------------------

    @Test
    fun `matching rates return the input untouched`() {
        val pcm = tone(440.0, PET, 100)
        // Same instance, not just equal: callers apply this unconditionally and
        // a 16 kHz voice should cost nothing at all.
        assertSame(pcm, Resampler.resample(pcm, PET, PET))
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals(0, Resampler.resample(ShortArray(0), PIPER, PET).size)
    }

    @Test
    fun `output length follows the rate ratio`() {
        val oneSecond = tone(440.0, PIPER, 1000)
        val out = Resampler.resample(oneSecond, PIPER, PET)

        // A wrong length here is a pitch shift — the exact bug that would have
        // shipped had the encoder stayed at 24 kHz.
        val expected = oneSecond.size * PET / PIPER
        assertTrue(
            "expected ~$expected samples, got ${out.size}",
            abs(out.size - expected) <= 1
        )
    }

    // ---- level -----------------------------------------------------------

    @Test
    fun `a constant signal keeps its level`() {
        // DC exercises the tap normalisation: if the filter's gain is not 1 at
        // DC, every conversion quietly changes loudness.
        val dc = ShortArray(2000) { 10_000 }
        val out = Resampler.resample(dc, PIPER, PET)

        val mean = out.map { it.toInt() }.average()
        assertTrue("expected ~10000, got $mean", abs(mean - 10_000) < 50)
    }

    @Test
    fun `a full-scale input does not clip`() {
        // The regression test for the overshoot fix. A windowed-sinc rings past
        // its input around a transient, so rounding straight to Int16 clamped
        // audio that was legal going in — silently, and audibly.
        //
        // It has to be a square wave. The first version of this test used a
        // steady sine, which a low-pass passes through untouched: it never
        // overshoots, so the test passed with the guard deleted and was
        // measuring nothing. Sharp edges are the whole point.
        val n = PIPER * 200 / 1000
        val square = ShortArray(n) { i -> if ((i / 36) % 2 == 0) 32_700 else -32_700 }

        val out = Resampler.resample(square, PIPER, PET)

        // Measured: 32720 with the guard, 32767 (clamped) without.
        assertTrue(
            "peak ${Resampler.peak(out)} is pinned at full scale — overshoot was " +
                "clamped rather than scaled",
            Resampler.peak(out) < 32_767
        )

        // Measured: 0 rail samples with the guard, 3 without. A sample sitting
        // exactly at the rail is a flattened waveform, which is what clipping
        // sounds like.
        val railed = out.count { abs(it.toInt()) >= 32_767 }
        assertEquals("samples clamped at full scale", 0, railed)
    }

    @Test
    fun `speech-band content survives`() {
        // 1 kHz is squarely in the band the pet has to reproduce; losing level
        // here would make every reply quieter than the phone's own playback.
        val input = tone(1_000.0, PIPER, 300)
        val out = Resampler.resample(input, PIPER, PET)

        val ratio = rms(out) / rms(input)
        assertTrue("1 kHz lost too much level (ratio $ratio)", ratio > 0.9)
    }

    // ---- the point of the filter ------------------------------------------

    @Test
    fun `content above the new Nyquist is removed rather than folded`() {
        // 10 kHz cannot exist at 16 kHz: without a low-pass it would alias down
        // to 6 kHz and sit in the middle of the speech band, which is heard as
        // a metallic edge on sibilants and blamed on the codec.
        val input = tone(10_000.0, PIPER, 300)
        val out = Resampler.resample(input, PIPER, PET)

        val ratio = rms(out) / rms(input)
        assertTrue("10 kHz survived at ratio $ratio — it should be gone", ratio < 0.1)
    }

    @Test
    fun `a tone just inside the passband is kept`() {
        // Guards the other direction: a filter cutting at, say, 2 kHz would pass
        // the aliasing test above while gutting speech.
        val input = tone(3_000.0, PIPER, 300)
        val out = Resampler.resample(input, PIPER, PET)

        val ratio = rms(out) / rms(input)
        assertTrue("3 kHz was over-attenuated (ratio $ratio)", ratio > 0.8)
    }
}
