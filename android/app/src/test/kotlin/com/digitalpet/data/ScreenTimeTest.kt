package com.digitalpet.data

import com.digitalpet.data.ScreenTime.Transition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whether the user is over a screen-time threshold *right now*.
 *
 * This is the sensor phase 4 hangs the whole mechanic on, and every way it can
 * be wrong is quiet. Saying "overusing" when they are not makes the pet ill for
 * no reason; saying "fine" when they are scrolling makes the feature do nothing
 * at all — and both look identical from outside, because the pet's face is the
 * only output either way.
 */
class ScreenTimeTest {

    private val minute = 60_000L
    private val now = 1_000_000_000L

    private val thresholds = mapOf("com.scroll" to 5 * minute)

    @Test
    fun `an app open past its threshold is overuse`() {
        val latest = mapOf("com.scroll" to Transition(resumed = true, timestamp = now - 6 * minute))
        assertEquals("com.scroll", ScreenTime.overusingPackage(latest, thresholds, now))
    }

    @Test
    fun `an app open but under its threshold is not`() {
        val latest = mapOf("com.scroll" to Transition(resumed = true, timestamp = now - 4 * minute))
        assertNull(ScreenTime.overusingPackage(latest, thresholds, now))
    }

    @Test
    fun `exactly at the threshold counts`() {
        // >= not >, matching the original service code. Pinned because flipping
        // it is invisible: the next poll a minute later would report overuse
        // anyway, so the bug would only ever be a one-minute delay nobody sees.
        val latest = mapOf("com.scroll" to Transition(resumed = true, timestamp = now - 5 * minute))
        assertEquals("com.scroll", ScreenTime.overusingPackage(latest, thresholds, now))
    }

    @Test
    fun `a closed app is never overuse however long it was open`() {
        // THE one that matters for the mechanic. Closing the app is how the
        // user makes the pet better, so a paused app still reading as overuse
        // would mean sickness could never be cured by the behaviour change the
        // whole feature exists to encourage.
        val latest = mapOf("com.scroll" to Transition(resumed = false, timestamp = now - 90 * minute))
        assertNull(ScreenTime.overusingPackage(latest, thresholds, now))
    }

    @Test
    fun `an app that is not monitored is ignored`() {
        val latest = mapOf("com.email" to Transition(resumed = true, timestamp = now - 90 * minute))
        assertNull(ScreenTime.overusingPackage(latest, thresholds, now))
    }

    @Test
    fun `no monitored apps at all is not overuse`() {
        val latest = mapOf("com.scroll" to Transition(resumed = true, timestamp = now - 90 * minute))
        assertNull(ScreenTime.overusingPackage(latest, emptyMap(), now))
    }

    @Test
    fun `each app is judged against its own threshold`() {
        val two = mapOf("com.scroll" to 5 * minute, "com.video" to 60 * minute)
        val latest = mapOf(
            "com.scroll" to Transition(resumed = true, timestamp = now - 10 * minute),
            "com.video" to Transition(resumed = false, timestamp = now - 30 * minute)
        )
        assertEquals("com.scroll", ScreenTime.overusingPackage(latest, two, now))
    }

    @Test
    fun `when two apps look resumed the most recent one wins`() {
        // Only one app is really in the foreground, but two can look resumed:
        // the older one's pause event can fall outside the one-hour lookback
        // and simply not be there to see. Picking the older one would blame the
        // wrong app in the nudge — and would keep blaming it after the user
        // moved on.
        val two = mapOf("com.scroll" to 5 * minute, "com.video" to 5 * minute)
        val latest = mapOf(
            "com.scroll" to Transition(resumed = true, timestamp = now - 55 * minute),
            "com.video" to Transition(resumed = true, timestamp = now - 20 * minute)
        )
        assertEquals("com.video", ScreenTime.overusingPackage(latest, two, now))
    }

    @Test
    fun `nothing seen at all is not overuse`() {
        assertNull(ScreenTime.overusingPackage(emptyMap(), thresholds, now))
    }

    // ---- elapsed, which only phrases the nudge ------------------------------

    @Test
    fun `elapsed measures from the resume`() {
        val latest = mapOf("com.scroll" to Transition(resumed = true, timestamp = now - 7 * minute))
        assertEquals(7 * minute, ScreenTime.elapsedMs(latest, "com.scroll", now))
    }

    @Test
    fun `elapsed is zero for an app that is not open`() {
        val latest = mapOf("com.scroll" to Transition(resumed = false, timestamp = now - 7 * minute))
        assertEquals(0L, ScreenTime.elapsedMs(latest, "com.scroll", now))
        assertEquals(0L, ScreenTime.elapsedMs(emptyMap(), "com.scroll", now))
    }

    // ---- sittings: the number the row shows, added 2026-08-09 ---------------

    @Test
    fun `a sitting is how long the app has been open, and closing it is the reset`() {
        /*
         * THE REPORTED BUG, as an assertion. The screen-time row used to show
         * the DAY's total against the per-sitting allowance: the numerator only
         * grows, so it passed the allowance before lunch and stayed past it
         * until midnight, and nothing the user could do reset it. Closing the
         * app is the reset, and it only works if the number IS the sitting.
         */
        val now = 1_000_000L
        val open = mapOf("com.app" to ScreenTime.Transition(resumed = true, timestamp = now - 12 * 60_000))
        assertEquals(12 * 60_000L, ScreenTime.sittings(open, setOf("com.app"), now)["com.app"])

        val closed = mapOf("com.app" to ScreenTime.Transition(resumed = false, timestamp = now - 60_000))
        assertEquals(0L, ScreenTime.sittings(closed, setOf("com.app"), now)["com.app"])
    }

    @Test
    fun `every monitored app gets an entry, including one never seen`() {
        // A hole in the map and a zero look the same to a reader — the app is
        // not open — so the caller must not have to decide that again. A missing
        // key would render as an empty row rather than as the reset.
        val sittings = ScreenTime.sittings(emptyMap(), setOf("a", "b"), 1_000L)
        assertEquals(setOf("a", "b"), sittings.keys)
        assertEquals(0L, sittings["a"])
    }

    @Test
    fun `the sitting and the illness agree, because they read the same map`() {
        /*
         * The point of showing the sitting rather than the day: the row's red
         * and the pet's illness are now the same condition, computed from the
         * same transitions. They could not agree before — one was a day total.
         */
        val now = 1_000_000L
        val latest = mapOf("com.app" to ScreenTime.Transition(true, now - 30 * 60_000))
        val thresholds = mapOf("com.app" to 25 * 60_000L)

        val sitting = ScreenTime.sittings(latest, thresholds.keys, now).getValue("com.app")
        val ill = ScreenTime.overusingPackage(latest, thresholds, now) != null

        assertEquals(ill, ScreenTimeDisplay.hasSpentAllowance(sitting, thresholds.getValue("com.app")))
        assertTrue("30 minutes against a 25 minute allowance should be over", ill)
    }

    @Test
    fun `and they still agree once the app is closed`() {
        val now = 1_000_000L
        val latest = mapOf("com.app" to ScreenTime.Transition(false, now - 60_000))
        val thresholds = mapOf("com.app" to 25 * 60_000L)

        val sitting = ScreenTime.sittings(latest, thresholds.keys, now).getValue("com.app")
        val ill = ScreenTime.overusingPackage(latest, thresholds, now) != null

        assertEquals(ill, ScreenTimeDisplay.hasSpentAllowance(sitting, thresholds.getValue("com.app")))
        assertTrue("a closed app cannot be making the pet ill", !ill)
    }

    // ---- latestSitting: the number the row shows -----------------------------

    private fun t(resumed: Boolean, atSec: Long) =
        ScreenTime.Transition(resumed, atSec * 1000L)

    @Test
    fun `THE ROW SHOWS THE MOST RECENT SESSION, not the longest`() {
        /*
         * The fourth and final shape of this reading, and the third fault it
         * fixes. "Longest today" was visible and only moved when you beat your
         * record: measured on the device going 54 -> 59 minutes of Instagram
         * without the bar shifting off 7/10. The most recent session moves every
         * time, which is what "reflecting my use" means.
         */
        val events = listOf(t(true, 0), t(false, 600), t(true, 900), t(false, 1020))
        assertEquals(120_000L, ScreenTime.latestSitting(events, now = 2000_000L))
    }

    @Test
    fun `an open session is live and runs to now`() {
        // While you are in the app this grows — and it is the same number
        // overusingPackage is comparing, so the row and the pet agree.
        assertEquals(500_000L, ScreenTime.latestSitting(listOf(t(true, 100)), now = 600_000L))
    }

    @Test
    fun `CLOSING THE APP RESETS THE ALLOWANCE BUT NOT THE READING`() {
        /*
         * The distinction the whole design turns on. Closing ends the session,
         * so `overusingPackage` reports OK and the pet recovers — the allowance
         * has reset. The row goes on saying how long that session ran, because
         * it is a record of what you just did rather than a live claim.
         */
        val closed = listOf(t(true, 0), t(false, 420))
        val now = 3_000_000L
        assertEquals(420_000L, ScreenTime.latestSitting(closed, now))
        assertEquals(
            null,
            ScreenTime.overusingPackage(
                mapOf("app" to t(false, 420)), mapOf("app" to 60_000L), now,
            ),
        )
    }

    @Test
    fun `AN ACTIVITY HANDOVER IS NOT A NEW SESSION`() {
        /*
         * Android emits PAUSED for the outgoing activity and RESUMED for the
         * incoming one when an app moves between its OWN screens. Paired
         * naively, one ten-minute session becomes several short ones and the row
         * would reset every time you tapped through Instagram.
         *
         * THE GAP HERE IS 300ms, NOT ZERO, AND THAT IS THE TEST. An earlier
         * version paused and resumed at the same instant, so the window was
         * never exercised and setting it to zero still passed.
         */
        val events = listOf(
            ScreenTime.Transition(true, 0),
            ScreenTime.Transition(false, 300_000),
            ScreenTime.Transition(true, 300_300),
            ScreenTime.Transition(false, 600_000),
        )
        assertEquals(600_000L, ScreenTime.latestSitting(events, now = 900_000L))
    }

    @Test
    fun `a real gap does start a new session`() {
        // The counterpart: 30 seconds away is leaving, and the handover window
        // must not swallow it. The reading becomes the SHORT new session.
        val events = listOf(t(true, 0), t(false, 300), t(true, 330), t(false, 400))
        assertEquals(70_000L, ScreenTime.latestSitting(events, now = 900_000L))
    }

    @Test
    fun `an app not opened today reads zero, not an error`() {
        assertEquals(0L, ScreenTime.latestSitting(emptyList(), now = 1000L))
        assertEquals(
            mapOf("a" to 0L, "b" to 0L),
            ScreenTime.latestSittings(emptyMap(), setOf("a", "b"), now = 1000L),
        )
    }

    @Test
    fun `THE BAR IS RED EXACTLY WHEN THIS SESSION IS MAKING THE PET ILL`() {
        /*
         * The property that makes the row honest: while the app is open, the
         * number on it IS the number overusingPackage compares, so full-and-red
         * and the pet being ill are one event rather than two that correlate.
         */
        val allowance = 10 * 60_000L
        val now = 900_000L
        val open = listOf(t(true, 0))                    // 15 minutes and counting
        val latest = mapOf("app" to t(true, 0))

        assertTrue(ScreenTimeDisplay.hasSpentAllowance(ScreenTime.latestSitting(open, now), allowance))
        assertTrue(ScreenTime.overusingPackage(latest, mapOf("app" to allowance), now) != null)
    }
}
