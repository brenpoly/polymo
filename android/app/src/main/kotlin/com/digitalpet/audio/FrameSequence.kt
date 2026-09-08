package com.digitalpet.audio

/**
 * What to do about the gap between two audio frame sequence numbers.
 *
 * The pet stamps every frame with a counter that wraps at 256 and advances even
 * for frames it fails to send, so a jump means audio is genuinely missing.
 * Joining the survivors would silently shorten the utterance and change what
 * was said, so short gaps are filled with Opus concealment instead.
 *
 * Pulled out of the decode path because it is fiddly modular arithmetic that
 * only runs when the link drops something — which, on a desk, it never does.
 * Left inline it would first execute in the field, on a bad connection, with
 * nobody watching.
 */
object FrameSequence {

    /** No previous frame yet; the first frame of an utterance. */
    const val NO_PREVIOUS = -1

    sealed interface Gap {
        /** Frames are consecutive — the normal case. */
        data object None : Gap

        /** [frames] frames were lost and should be concealed. */
        data class Conceal(val frames: Int) : Gap

        /**
         * Too many frames lost to invent convincingly. Inventing seconds of
         * audio would feed the recogniser noise; better to leave the hole and
         * say so.
         */
        data class TooLarge(val frames: Int) : Gap

        /**
         * The sequence did not advance — a duplicate or a reordered frame.
         *
         * Distinguished from a gap on purpose. Modulo arithmetic alone reads a
         * repeat of seq 7 as a jump of 255, which is both a nonsense log line
         * and a trap: raise the conceal limit far enough and a single duplicate
         * would inject five seconds of synthetic audio.
         */
        data object NotNewer : Gap
    }

    /**
     * Classify the step from [lastSeq] to [newSeq].
     *
     * @param lastSeq  previous sequence number, or [NO_PREVIOUS] for the first
     *   frame of an utterance.
     * @param maxConceal largest gap worth filling.
     */
    fun gap(lastSeq: Int, newSeq: Int, maxConceal: Int): Gap {
        if (lastSeq == NO_PREVIOUS) return Gap.None

        val delta = (newSeq - lastSeq + 256) % 256

        // A "forward" jump of more than half the space is far more likely to be
        // a frame arriving late than a loss of 128+ frames, and treating it as
        // the latter is the expensive mistake.
        if (delta == 0 || delta > 128) return Gap.NotNewer

        val missing = delta - 1
        return when {
            missing == 0 -> Gap.None
            missing <= maxConceal -> Gap.Conceal(missing)
            else -> Gap.TooLarge(missing)
        }
    }
}
