package com.digitalpet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Emoji reaching the pet as empty boxes was the original problem;
 * [PetExpression] turns them into the face instead.
 *
 * Two things can go wrong and only one of them is obvious. An emoji the regex
 * misses arrives on screen as tofu — visible immediately. A regex that is too
 * greedy quietly eats ordinary punctuation out of every reply, which nobody
 * notices until the pet has been dropping em-dashes for a week. The ranges here
 * already had to be widened once, for emoji that live in the punctuation and
 * letterlike blocks and are in no emoji range at all.
 */
class PetExpressionTest {

    // ---- mood mapping ------------------------------------------------------

    @Test
    fun `a happy emoji sets a happy face and leaves the words`() {
        val r = PetExpression.parse("Good morning! 😊")
        assertEquals(PetProtocol.Mood.HAPPY, r.mood)
        assertEquals("Good morning!", r.text)
    }

    @Test
    fun `sleepy and surprised have their own faces`() {
        assertEquals(PetProtocol.Mood.SLEEPY, PetExpression.parse("night 😴").mood)
        assertEquals(PetProtocol.Mood.SURPRISED, PetExpression.parse("oh 😮").mood)
    }

    @Test
    fun `no emoji means no mood, not a reset to neutral`() {
        // Null rather than NEUTRAL on purpose: the caller keeps whatever face
        // the pet already has. Returning NEUTRAL here would blank the
        // expression on every sentence that happened to carry no emoji.
        assertNull(PetExpression.parse("just some text").mood)
    }

    @Test
    fun `surprise wins over happy when a reply carries both`() {
        // The order is a deliberate priority, not an accident of the `when`.
        // Pinning it means reordering the branches has to be a choice.
        assertEquals(
            PetProtocol.Mood.SURPRISED,
            PetExpression.parse("wow 😮 that's great 😊").mood
        )
    }

    @Test
    fun `both spellings of an emoji are recognised`() {
        // "❤" is U+2764; "❤️" is U+2764 U+FE0F. The model emits whichever it
        // likes, and comparing raw strings misses the other one.
        assertEquals(PetProtocol.Mood.HAPPY, PetExpression.parse("❤️").mood)
        assertEquals(PetProtocol.Mood.HAPPY, PetExpression.parse("❤").mood)
    }

    // ---- stripping ---------------------------------------------------------

    @Test
    fun `an unrecognised emoji is removed but changes no face`() {
        // Everything undrawable has to go, whether or not we have a face for it.
        val r = PetExpression.parse("a rocket 🚀 launched")
        assertNull(r.mood)
        assertEquals("a rocket launched", r.text)
    }

    @Test
    fun `emoji in the punctuation and letterlike blocks are stripped`() {
        // These are in no emoji range and had to be listed by hand. Left in,
        // they reach the screen as tofu.
        for (odd in listOf("‼️", "⁉️", "™", "ℹ️", "Ⓜ️")) {
            val text = PetExpression.parse("hi ${odd} there").text
            assertEquals("stripping $odd", "hi there", text)
        }
    }

    @Test
    fun `multi-part joined emoji leave nothing behind`() {
        // A ZWJ sequence is several codepoints glued together; missing the
        // joiner leaves an invisible character on the wire.
        assertEquals("family", PetExpression.parse("family 👨‍👩‍👧").text)
    }

    @Test
    fun `whitespace left by a removed emoji is collapsed`() {
        assertEquals("hello there", PetExpression.parse("hello 😊 there").text)
    }

    @Test
    fun `punctuation stranded by a trailing emoji is tidied`() {
        // Without this the pet displays "nice !", which reads as a typo.
        assertEquals("nice!", PetExpression.parse("nice 😊!").text)
    }

    // ---- the regression that matters ---------------------------------------

    @Test
    fun `ordinary punctuation is not eaten`() {
        // The expensive failure mode. These characters sit near the emoji
        // ranges and the model uses all of them constantly; losing them
        // degrades every reply invisibly.
        val prose = "It's 3 p.m. — did you eat? “Yes”… I hope so; you seemed tired."
        assertEquals(prose, PetExpression.parse(prose).text)
    }

    @Test
    fun `accented and non-latin text survives`() {
        assertEquals("café naïve résumé", PetExpression.parse("café naïve résumé").text)
        assertEquals("こんにちは", PetExpression.parse("こんにちは").text)
    }

    @Test
    fun `arithmetic and currency survive`() {
        // ×, ÷, £, €, ° and friends live close to symbol blocks.
        val text = "3 × 4 ÷ 2 = 6, about £5 or €6, at 20°C"
        assertEquals(text, PetExpression.parse(text).text)
    }

    // ---- edges -------------------------------------------------------------

    @Test
    fun `empty and emoji-only input are handled`() {
        assertEquals("", PetExpression.parse("").text)

        val onlyEmoji = PetExpression.parse("😊")
        assertEquals("", onlyEmoji.text)
        // The face still changes even though there is nothing left to display.
        assertEquals(PetProtocol.Mood.HAPPY, onlyEmoji.mood)
    }
}
