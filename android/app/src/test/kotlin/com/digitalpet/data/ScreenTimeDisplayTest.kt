package com.digitalpet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the screen-time page puts on a row.
 *
 * The numbers here are the Claude Design 2e mockup's own — 37/25, 18/20, 6/30 —
 * because it drew the bar it wanted at percentages, and those percentages are
 * the specification. If a change makes these fail, the change is a redesign.
 */
class ScreenTimeDisplayTest {

    private fun min(m: Long) = m * 60_000L

    // ---- durations ---------------------------------------------------------

    @Test
    fun `minutes below an hour read as minutes`() {
        assertEquals("0 m", ScreenTimeDisplay.duration(0))
        assertEquals("37 m", ScreenTimeDisplay.duration(min(37)))
        assertEquals("59 m", ScreenTimeDisplay.duration(min(59)))
    }

    @Test
    fun `an hour and over reads as hours and minutes`() {
        assertEquals("1 h 0 m", ScreenTimeDisplay.duration(min(60)))
        assertEquals("2 h 5 m", ScreenTimeDisplay.duration(min(125)))
    }

    @Test
    fun `a part minute rounds DOWN, never up`() {
        // This sits beside an allowance and answers "have I gone past it".
        // Rounding up would answer yes when the truth is no, and a screen-time
        // reading that overstates you is one you stop believing.
        assertEquals("24 m", ScreenTimeDisplay.duration(min(24) + 59_000))
        assertEquals("0 m", ScreenTimeDisplay.duration(59_000))
    }

    @Test
    fun `a negative duration is not rendered as negative`() {
        // Clocks move backwards; nothing here should ever print "-2 m".
        //
        // It must be MORE than a minute negative to be a real probe: Kotlin's
        // integer division truncates toward zero, so -5000 ms comes out as 0
        // whether the clamp is there or not, and a test using it passes against
        // the broken version. Found by mutation — the first value chosen here
        // was exactly that useless.
        assertEquals("0 m", ScreenTimeDisplay.duration(-5000))
        assertEquals("0 m", ScreenTimeDisplay.duration(min(-2)))
    }

    @Test
    fun `an allowance is always minutes`() {
        assertEquals("25 min", ScreenTimeDisplay.allowance(min(25)))
        assertEquals("120 min", ScreenTimeDisplay.allowance(min(120)))
    }

    // ---- the reading, which is the SITTING ----------------------------------

    @Test
    fun `the row reads as a fraction with the unit said once`() {
        assertEquals("2 / 5 min", ScreenTimeDisplay.sitting(min(2), min(5)))
    }

    @Test
    fun `SECONDS BELOW A MINUTE, because the first minute is when anyone watches`() {
        /*
         * The reading is live while the app is open, so a fresh session spends
         * its first sixty seconds under one minute — and rounding down meant it
         * read `0` throughout, which looks exactly like nothing happening.
         */
        assertEquals("40 s / 10 min", ScreenTimeDisplay.sitting(40_000, min(10)))
        assertEquals("0 s / 10 min", ScreenTimeDisplay.sitting(0, min(10)))
    }

    @Test
    fun `the switch to minutes happens AT one minute, not after it`() {
        // The boundary, pinned. 59 seconds is still seconds; 60 is one minute.
        assertEquals("59 s / 10 min", ScreenTimeDisplay.sitting(59_999, min(10)))
        assertEquals("1 / 10 min", ScreenTimeDisplay.sitting(60_000, min(10)))
    }

    @Test
    fun `the reading says which session it is`() {
        // Four different quantities have been rendered as a bare `n / m min` on
        // this row. The label is what stops the next reader having to infer it.
        assertEquals(
            "Last session: 40 s / 10 min",
            ScreenTimeDisplay.sessionReading(40_000, min(10)),
        )
    }

    @Test
    fun `A CLOSED APP READS ZERO, which is the reset made visible`() {
        /*
         * THE BUG THIS FILE'S THIRD REVISION EXISTS FOR. The row used to divide
         * the DAY by the per-sitting allowance, so it passed the allowance
         * before lunch and stayed past it until midnight — reported as the
         * tracking never resetting, which is exactly what it did not do.
         *
         * Zero is shown rather than the row emptying, because "it reset" and "it
         * stopped counting" look identical when there is nothing there.
         */
        assertEquals("0 s / 25 min", ScreenTimeDisplay.sitting(0, min(25)))
    }

    @Test
    fun `the reading goes past the allowance rather than capping`() {
        // The bar caps; this must not, or the one place that says HOW far over
        // you are would stop saying it.
        assertEquals("37 / 25 min", ScreenTimeDisplay.sitting(min(37), min(25)))
    }

    // ---- the day, which is a fact and never a numerator ---------------------

    @Test
    fun `the day states itself in its own words`() {
        // It carries "today" so it cannot be read as belonging to the allowance
        // above it. That word is the whole guard against the previous fault.
        assertEquals("12 m today", ScreenTimeDisplay.today(min(12)))
        assertEquals("1 h 12 m today", ScreenTimeDisplay.today(min(72)))
        assertEquals("0 m today", ScreenTimeDisplay.today(0))
    }

    @Test
    fun `the day and the sitting cannot be confused for one another`() {
        // Same milliseconds, two different sentences. If these ever rendered
        // alike the row would be back to implying a comparison it is not making.
        val ms = min(37)
        assertNotEquals(ScreenTimeDisplay.today(ms), ScreenTimeDisplay.sitting(ms, min(25)))
        assertTrue(ScreenTimeDisplay.today(ms).contains("today"))
        assertTrue(!ScreenTimeDisplay.sitting(ms, min(25)).contains("today"))
    }

    // ---- the bar, which now means exactly one thing -------------------------

    @Test
    fun `the bar is the sitting against the allowance`() {
        assertEquals(0.90f, ScreenTimeDisplay.barFraction(min(18), min(20)), 0.001f)
        assertEquals(0.20f, ScreenTimeDisplay.barFraction(min(6), min(30)), 0.001f)
        assertEquals(0f, ScreenTimeDisplay.barFraction(0, min(25)), 0.001f)
    }

    @Test
    fun `the bar fills and stops, however far past the allowance you go`() {
        // The property the previous two-denominator version did not have: going
        // further over used to make the filled part SHRINK, which reads as less
        // progress. Length now only ever increases with use.
        assertEquals(1f, ScreenTimeDisplay.barFraction(min(25), min(25)), 0.001f)
        assertEquals(1f, ScreenTimeDisplay.barFraction(min(37), min(25)), 0.001f)
        assertEquals(1f, ScreenTimeDisplay.barFraction(min(600), min(25)), 0.001f)
    }

    @Test
    fun `the bar never goes below empty or above full`() {
        assertEquals(0f, ScreenTimeDisplay.barFraction(min(-5), min(25)), 0.001f)
        val f = ScreenTimeDisplay.barFraction(min(12), min(25))
        assertTrue(f in 0f..1f)
    }

    @Test
    fun `an impossible zero allowance is already spent`() {
        // Not reachable by the stepper, but reachable from stored preferences,
        // and dividing by it would be a crash rather than a glitch.
        assertEquals(0f, ScreenTimeDisplay.barFraction(0, 0), 0.001f)
        assertEquals(1f, ScreenTimeDisplay.barFraction(min(1), 0), 0.001f)
    }

    @Test
    fun `red starts exactly where the bar fills, at the allowance and not past it`() {
        // AT the limit counts. ScreenTime.overusingPackage tests `>= threshold`,
        // so the pet falls ill the moment the allowance is reached — a display
        // waiting for strictly-greater would call it healthy while it was being
        // made ill. The predecessor of this function did exactly that.
        assertTrue(ScreenTimeDisplay.hasSpentAllowance(min(25), min(25)))
        assertTrue(ScreenTimeDisplay.hasSpentAllowance(min(37), min(25)))
        assertFalse(ScreenTimeDisplay.hasSpentAllowance(min(25) - 1, min(25)))
        assertFalse(ScreenTimeDisplay.hasSpentAllowance(0, min(25)))
    }

    @Test
    fun `red and full are the same condition, by construction`() {
        // The bar's colour is derived from its own fill, so these cannot drift
        // into a bar that is completely full and still not red.
        listOf(0L, min(1), min(24), min(25), min(26), min(600)).forEach { used ->
            assertEquals(
                "used=$used",
                ScreenTimeDisplay.barFraction(used, min(25)) >= 1f,
                ScreenTimeDisplay.hasSpentAllowance(used, min(25)),
            )
        }
    }

    @Test
    fun `an empty bar on a zero allowance is not red`() {
        // barFraction returns 1f for any use against a zero allowance, so the
        // only case that must not be red is the one where nothing is drawn.
        assertFalse(ScreenTimeDisplay.hasSpentAllowance(0, 0))
        assertTrue(ScreenTimeDisplay.hasSpentAllowance(min(1), 0))
    }

    // ---- the stepper -------------------------------------------------------

    @Test
    fun `stepping moves one minute at a time`() {
        assertEquals(min(6), ScreenTimeDisplay.step(min(5), up = true))
        assertEquals(min(4), ScreenTimeDisplay.step(min(5), up = false))

        // Away from the default too, so a step that happened to be right at 5
        // and wrong elsewhere — a multiplier rather than an increment — fails.
        assertEquals(min(38), ScreenTimeDisplay.step(min(37), up = true))
        assertEquals(min(36), ScreenTimeDisplay.step(min(37), up = false))
    }

    @Test
    fun `a part-minute is dropped rather than carried`() {
        // At a step of 5 this test was about landing on the 5-grid. At a step of
        // 1 every whole minute IS the grid, so what is left to check is that
        // seconds do not survive: a stored 7 min 30 s must go to 8 and 6, never
        // to 8:30 and 6:30. Values like that exist — an older build's default
        // and hand-edited prefs both wrote arbitrary numbers.
        val sevenAndAHalf = min(7) + 30_000L
        assertEquals(min(8), ScreenTimeDisplay.step(sevenAndAHalf, up = true))
        assertEquals(min(6), ScreenTimeDisplay.step(sevenAndAHalf, up = false))
    }

    @Test
    fun `the stepper cannot go below the floor or above the cap`() {
        // The floor is MIN_MINUTES and no longer the step. Zero would be an
        // allowance spent the instant an app opens.
        assertEquals(min(1), ScreenTimeDisplay.step(min(1), up = false))
        assertEquals(min(1), ScreenTimeDisplay.step(0, up = false))
        assertEquals(min(2), ScreenTimeDisplay.step(min(1), up = true))
        assertEquals(min(120), ScreenTimeDisplay.step(min(120), up = true))
        assertEquals(min(120), ScreenTimeDisplay.step(min(500), up = true))
    }

    @Test
    fun `the default is what a newly tracked app gets, and it is reachable`() {
        // Pins the two numbers the request was about, and that the default sits
        // inside the range rather than on a boundary the stepper cannot leave.
        assertEquals(5L, ScreenTimeDisplay.DEFAULT_MINUTES)
        assertEquals(1L, ScreenTimeDisplay.STEP_MINUTES)
        assertEquals(min(5), ScreenTimeDisplay.DEFAULT_MS)
        assertTrue(ScreenTimeDisplay.DEFAULT_MINUTES > ScreenTimeDisplay.MIN_MINUTES)
        assertTrue(ScreenTimeDisplay.DEFAULT_MINUTES < ScreenTimeDisplay.MAX_MINUTES)
    }

    @Test
    fun `an allowance out of range is pulled back in by either button`() {
        // A value from an older build, or from editing prefs by hand, must not
        // be preserved just because the user pressed the button that would have
        // made it worse.
        assertEquals(min(120), ScreenTimeDisplay.step(min(300), up = true))
        assertEquals(min(120), ScreenTimeDisplay.step(min(300), up = false))
    }

    // ---- the mechanic, explained on the page that configures it -------------

    @Test
    fun `the rule states all three steps, in order`() {
        // The page used to describe the pet's condition and never the rule, so
        // the mechanic could not be learned from the screen that sets it up.
        val t = ScreenTimeDisplay.HOW_IT_WORKS
        assertTrue("does not say you set limits: $t", t.contains("Set screen time limits"))
        assertTrue("does not say the pet falls ill: $t", t.contains("falls ill"))
        assertTrue("does not say closing the app fixes it: $t", t.contains("Close the app"))
        // Order matters: what you set, what it costs, how to undo it. Read out
        // of sequence, the consequence arrives before the thing you control.
        assertTrue(
            "the three steps are out of order: $t",
            t.indexOf("Set screen time limits") < t.indexOf("falls ill") &&
                t.indexOf("falls ill") < t.indexOf("Close the app"),
        )
    }

    @Test
    fun `the rule stays short, because that is the way it keeps failing`() {
        /*
         * LENGTH IS THE FAILURE MODE HERE, not accuracy. This copy has been too
         * long twice: once in the pet's voice, once as a correct four-sentence
         * explanation that included the drained scores not returning. Every
         * sentence anyone wanted to add was TRUE, and the paragraph still
         * stopped being read — so the only useful guard is a count.
         */
        val sentences = ScreenTimeDisplay.HOW_IT_WORKS
            .split(".").map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(
            "the rule is ${sentences.size} sentences: ${ScreenTimeDisplay.HOW_IT_WORKS}",
            ScreenTimeDisplay.SENTENCE_LIMIT, sentences.size,
        )
        assertTrue(
            "the rule has grown to ${ScreenTimeDisplay.HOW_IT_WORKS.length} characters",
            ScreenTimeDisplay.HOW_IT_WORKS.length <= 120,
        )
    }

    @Test
    fun `the two state lines are one sentence each`() {
        // Same rule, applied where it is easiest to let a clause creep back in.
        listOf(ScreenTimeDisplay.NO_ACCESS, ScreenTimeDisplay.NOTHING_TRACKED).forEach {
            assertTrue("too long: $it", it.length <= 60)
        }
    }

    @Test
    fun `no line on this page is written in the pet's voice`() {
        // "Without this your pet cannot fall ill" says what state the pet is in
        // and never what the user does. The rule may name the pet — it is the
        // consequence — but the two STATE lines describe the phone.
        assertTrue(
            ScreenTimeDisplay.NO_ACCESS,
            !ScreenTimeDisplay.NO_ACCESS.contains("pet", ignoreCase = true),
        )
        assertTrue(ScreenTimeDisplay.NO_ACCESS.contains("usage access"))
        assertTrue(ScreenTimeDisplay.NOTHING_TRACKED.contains("Add one"))
    }
}
