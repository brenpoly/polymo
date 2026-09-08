package com.digitalpet.audio

import com.digitalpet.audio.FrameSequence.Gap
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gap logic decides whether missing audio gets concealed, ignored, or
 * mistaken for something it isn't.
 *
 * It had never executed once before these tests: every hardware run so far has
 * delivered every frame, so the whole branch existed only in theory. Left that
 * way it would first run in the field, on a bad connection, with nobody looking
 * at a log.
 *
 * The wrap at 256 is the part worth pinning. Sequence numbers are one byte, so
 * "5 after 3" and "1 after 254" are both gaps of one, and the arithmetic that
 * makes those agree is easy to get subtly wrong in a way that only shows up
 * once every 256 frames — about every five seconds of speech.
 */
class FrameSequenceTest {

    private companion object {
        const val MAX = 10
    }

    private fun gap(last: Int, new: Int) = FrameSequence.gap(last, new, MAX)

    // ---- the ordinary cases ------------------------------------------------

    @Test
    fun `the first frame of an utterance is never a gap`() {
        // Nothing precedes it, so concealing here would prepend invented audio
        // to every single utterance.
        assertEquals(Gap.None, gap(FrameSequence.NO_PREVIOUS, 0))
        assertEquals(Gap.None, gap(FrameSequence.NO_PREVIOUS, 200))
    }

    @Test
    fun `consecutive frames are not a gap`() {
        assertEquals(Gap.None, gap(0, 1))
        assertEquals(Gap.None, gap(41, 42))
    }

    @Test
    fun `a single lost frame is concealed`() {
        assertEquals(Gap.Conceal(1), gap(5, 7))
    }

    @Test
    fun `several lost frames are concealed`() {
        assertEquals(Gap.Conceal(3), gap(10, 14))
        assertEquals(Gap.Conceal(MAX), gap(10, 10 + MAX + 1))
    }

    @Test
    fun `a gap beyond the conceal limit is reported rather than invented`() {
        // Fabricating seconds of audio would feed Whisper noise it would
        // cheerfully transcribe into words nobody said.
        assertEquals(Gap.TooLarge(MAX + 1), gap(10, 10 + MAX + 2))
    }

    // ---- the wrap ----------------------------------------------------------

    @Test
    fun `255 to 0 is consecutive, not a jump backwards`() {
        // The counter is one byte. Without the modulo this reads as -255 and
        // every utterance longer than five seconds gets a spurious event here.
        assertEquals(Gap.None, gap(255, 0))
    }

    @Test
    fun `a gap spanning the wrap is measured correctly`() {
        assertEquals(Gap.Conceal(1), gap(255, 1))
        assertEquals(Gap.Conceal(2), gap(254, 1))
        assertEquals(Gap.Conceal(3), gap(253, 1))
    }

    @Test
    fun `the wrap behaves identically to the middle of the range`() {
        // The property that matters: position in the byte range must not change
        // the answer. Any drift between these two is a once-per-256-frames bug.
        for (missing in 0..MAX) {
            val mid = gap(100, 100 + missing + 1)
            val wrapped = gap(250, (250 + missing + 1) % 256)
            assertEquals("missing=$missing", mid, wrapped)
        }
    }

    // ---- what is not a gap at all ------------------------------------------

    @Test
    fun `a repeated frame is not a 255-frame gap`() {
        // Plain modulo arithmetic reads this as a jump of 255. That is both a
        // nonsense log line and a trap: raise the conceal limit far enough and a
        // single duplicate would inject five seconds of synthetic audio into the
        // middle of an utterance.
        assertEquals(Gap.NotNewer, gap(7, 7))
    }

    @Test
    fun `a frame arriving late is not an enormous gap`() {
        assertEquals(Gap.NotNewer, gap(7, 5))
        assertEquals(Gap.NotNewer, gap(200, 100))
    }

    @Test
    fun `a late frame across the wrap is still recognised`() {
        // seq 250 arriving after 2 is 8 frames old, not 248 frames missing.
        assertEquals(Gap.NotNewer, gap(2, 250))
    }

    @Test
    fun `the forward and backward halves meet where they should`() {
        // 128 is the boundary between "a big gap" and "an old frame". Pinning it
        // stops the two branches quietly swapping if the comparison changes.
        assertEquals(Gap.TooLarge(127), gap(0, 128))
        assertEquals(Gap.NotNewer, gap(0, 129))
    }

    // ---- limit is honoured -------------------------------------------------

    @Test
    fun `the conceal limit is respected rather than hardcoded`() {
        assertEquals(Gap.Conceal(2), FrameSequence.gap(0, 3, maxConceal = 2))
        assertEquals(Gap.TooLarge(2), FrameSequence.gap(0, 3, maxConceal = 1))
    }
}
