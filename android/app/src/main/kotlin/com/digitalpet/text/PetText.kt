package com.digitalpet.text

/**
 * Preparing model output for consumers that cannot take it raw.
 *
 * The LLM writes for a chat window: markdown, emoji, arbitrary length. Two
 * consumers cannot cope with that. The pet's screen renders LVGL's Montserrat
 * — no markup, no emoji — and holds 240 bytes. Piper pronounces punctuation it
 * does not understand, so "**Slack**" comes out as noise around the word.
 *
 * The chat itself keeps the original text; only these two get the cleaned
 * version.
 *
 * Everything here is pure, and every function has already produced a real bug:
 * markup reaching Piper as garbled noise, and a byte-truncation that split a
 * multi-byte character in half.
 */
object PetText {

    /**
     * Below this many characters, a truncated message is not cut back to its
     * last sentence — a two-word fragment tells the reader less than a clipped
     * sentence does.
     */
    const val MIN_SENTENCE_CHARS = 20

    /**
     * Remove markdown and decorative characters.
     *
     * The model still emits markup despite being told not to. Piper reads those
     * characters literally — asterisks came out as garbled noise mid-sentence —
     * and the pet's display cannot render them either.
     */
    fun stripMarkup(text: String): String =
        text
            .replace(Regex("""[*_`~]+"""), "")                              // emphasis, code
            .replace(Regex("""^\s*#{1,6}\s*""", RegexOption.MULTILINE), "") // headings
            .replace(Regex("""^\s*[-•·]\s+""", RegexOption.MULTILINE), "")  // bullets
            .replace(Regex("""\[([^]]*)]\([^)]*\)"""), "$1")                // links -> label
            .replace(Regex("""[ \t]{2,}"""), " ")
            .trim()

    /**
     * Strip Whisper's non-speech annotations, and report whether anything the
     * user actually said is left.
     *
     * Whisper does not return an empty string for silence — it returns a bracketed
     * annotation describing what it heard: "[BLANK_AUDIO]" for a silent clip, and
     * "[MUSIC]", "(coughing)", "[ Silence ]" and similar for room noise. Those are
     * non-empty, so an `isNotEmpty()` check passes them straight through as if the
     * user had spoken them.
     *
     * Observed doing real damage: a capture of an empty room transcribed as
     * "[BLANK_AUDIO]", which went to the LLM as a prompt and came back as an
     * unprompted 240-character monologue that the pet then spoke for 12 seconds.
     * The pet talking to itself about nothing is worse than the pet staying quiet.
     *
     * Annotations are removed rather than the whole transcript being rejected,
     * because a real utterance can carry one: "[BLANK_AUDIO] tell me a joke" keeps
     * "tell me a joke". Returns "" when only annotations were present.
     */
    fun stripNonSpeech(text: String): String =
        text
            .replace(Regex("""\[[^\[\]]*]"""), " ")   // [BLANK_AUDIO], [ Silence ], [MUSIC]
            .replace(Regex("""\([^()]*\)"""), " ")    // (coughing), (buzzing)
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

    /**
     * Turn-markers the model emits to end its reply. Generation stops at the
     * first of these, but they arrive split across tokens, so a partial one can
     * be left behind when generation ends for another reason.
     */
    val CHAT_MARKERS = listOf(
        "<|user|>", "<|end|>", "<|system|>", "<|assistant|>", "user:", "assistant:"
    )

    /**
     * Drop a trailing fragment of a turn-marker.
     *
     * "<|user|>" reaches us as several tokens, so a reply that stops on the
     * token budget can end mid-marker — with "<|us" left dangling. Displayed
     * that is odd; spoken it is worse, because Piper reads the letters out and
     * the pet appears to say "user" after finishing its sentence.
     */
    fun dropTrailingPartialMarker(text: String): String {
        for (marker in CHAT_MARKERS) {
            // Only the bracketed markers. A fragment of "user:" is the word
            // "user", so stripping those turns "Talk to the user" into "Talk to
            // the" — losing a real word to protect against a rare one. "<|" does
            // not occur in ordinary prose, so its fragments are unambiguous.
            if (!marker.startsWith("<|")) continue

            // Longest fragment first: "<|user" must win over "<|", or the tail
            // left behind is the very word being complained about.
            for (len in marker.length - 1 downTo 2) {
                if (text.endsWith(marker.substring(0, len))) {
                    return text.substring(0, text.length - len).trimEnd()
                }
            }
        }
        return text
    }

    /**
     * Truncate to at most [maxBytes] of UTF-8 **without splitting a character**.
     *
     * The naive version of this — copying [maxBytes] raw bytes — produced
     * invalid UTF-8 whenever the cut landed mid-character, which the model makes
     * likely because its replies are full of non-ASCII punctuation.
     */
    fun truncateUtf8(s: String, maxBytes: Int): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return bytes
        // Continuation bytes are 10xxxxxx; walk back off any partial character.
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOf(end)
    }

    /**
     * Fit [text] into [maxBytes] for the pet's screen, cutting at a sentence
     * boundary where there is one.
     *
     * Stopping mid-sentence reads as a glitch; stopping at a full stop reads as
     * brevity. The ellipsis is what tells the reader something was dropped, so
     * its own bytes have to come out of the budget rather than be appended past
     * it — that would push the write over the firmware's limit and be rejected.
     */
    fun fitForDisplay(text: String, maxBytes: Int): ByteArray {
        val full = text.toByteArray(Charsets.UTF_8)
        if (full.size <= maxBytes) return full

        val ellipsis = "…"
        val budget = maxBytes - ellipsis.toByteArray(Charsets.UTF_8).size

        // Last sentence terminator that still fits inside the budget.
        val head = String(truncateUtf8(text, budget), Charsets.UTF_8)
        val lastEnd = head.indexOfLast { it == '.' || it == '!' || it == '?' }
        val kept = if (lastEnd >= MIN_SENTENCE_CHARS) head.substring(0, lastEnd + 1)
                   else head.trimEnd()

        return (kept + ellipsis).toByteArray(Charsets.UTF_8)
    }
}
