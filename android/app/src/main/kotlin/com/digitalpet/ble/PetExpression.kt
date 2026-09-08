package com.digitalpet.ble

/**
 * Turns emoji in the model's replies into the pet's facial expression.
 *
 * The pet renders with LVGL's built-in Montserrat font, which has no emoji
 * glyphs, so a "😊" reaches the screen as an empty box. Rather than silently
 * deleting it — and losing the emotion the model was trying to convey — the
 * emoji is read as a *mood* and applied to the pet's face, then removed from the
 * text. The limitation becomes the pet emoting.
 *
 * Emoji are also stripped from anything sent to text-to-speech, which otherwise
 * tries to pronounce them.
 */
object PetExpression {

    /** Cleaned text plus the mood implied by any emoji it contained. */
    data class Result(val text: String, val mood: PetProtocol.Mood?)

    /** Drop variation selectors / ZWJ so both spellings of an emoji compare equal. */
    private fun normalise(s: String) =
        s.replace(Regex("[\\x{FE00}-\\x{FE0F}\\x{200D}]"), "")

    // Representative emoji per mood. Deliberately small: these are the ones a
    // chatty pet actually produces. Anything unlisted still gets stripped, it
    // just does not change the expression.
    private val HAPPY = setOf(
        "😊", "😄", "😃", "🙂", "😁", "😀", "🥰", "😍", "🤗", "😸", "😺",
        "❤️", "💖", "💕", "✨", "🎉", "👍", "🙌", "😻", "🥳"
    )
    private val SLEEPY = setOf("😴", "😪", "🥱", "💤", "😌", "🌙", "😑")
    private val SURPRISED = setOf("😮", "😲", "😯", "😱", "🤯", "😳", "‼️", "❗", "❓", "🙀")

    /**
     * Matches emoji and their modifiers across the common Unicode blocks:
     * emoticons/pictographs, misc symbols, dingbats, arrows and shapes,
     * regional indicators (flags), variation selectors and zero-width joiners
     * (which glue multi-part emoji such as 👨‍👩‍👧 together).
     */
    private val EMOJI = Regex(
        "[\\x{1F000}-\\x{1FAFF}" +   // emoticons, pictographs, supplemental
            "\\x{2600}-\\x{27BF}" +  // misc symbols + dingbats
            "\\x{2B00}-\\x{2BFF}" +  // misc symbols and arrows
            "\\x{2190}-\\x{21FF}" +  // arrows (↔️ ↩️)
            "\\x{2300}-\\x{23FF}" +  // misc technical (⌚ ⏰ ⏳ ▶)
            "\\x{25A0}-\\x{25FF}" +  // geometric shapes (◀ ▪)
            "\\x{1F1E6}-\\x{1F1FF}" +// regional indicators (flags)
            "\\x{FE00}-\\x{FE0F}" +  // variation selectors
            "\\x{200D}\\x{20E3}" +   // ZWJ, combining keycap
            // Emoji that live in punctuation/letterlike blocks and so are not
            // caught by any of the ranges above.
            "\\x{203C}\\x{2049}\\x{2122}\\x{2139}\\x{24C2}]"
    )

    /**
     * Strip emoji from [text] and report the mood they implied.
     *
     * Returns a null mood when no recognised emoji was present, so the caller
     * can keep whatever expression the pet already has rather than resetting it.
     */
    fun parse(text: String): Result {
        // Match against a form with variation selectors and ZWJ removed. Many
        // emoji have both a bare and a "presentation" spelling — "❤" is U+2764
        // while "❤️" is U+2764 U+FE0F — and comparing the raw strings misses
        // whichever form the model did not happen to emit.
        val normalised = text.replace(Regex("[\\x{FE00}-\\x{FE0F}\\x{200D}]"), "")
        fun has(set: Set<String>) = set.any { normalised.contains(normalise(it)) }

        val mood = when {
            has(SURPRISED) -> PetProtocol.Mood.SURPRISED
            has(SLEEPY) -> PetProtocol.Mood.SLEEPY
            has(HAPPY) -> PetProtocol.Mood.HAPPY
            else -> null
        }

        val cleaned = EMOJI.replace(text, "")
            // Collapse the whitespace the removed emoji left behind, and tidy
            // punctuation stranded by a trailing one ("nice !" -> "nice!").
            .replace(Regex("""\s{2,}"""), " ")
            .replace(Regex("""\s+([,.!?])"""), "$1")
            .trim()

        return Result(cleaned, mood)
    }
}
