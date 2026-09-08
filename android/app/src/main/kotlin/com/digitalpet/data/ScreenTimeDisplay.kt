package com.digitalpet.data

/**
 * How screen time is *shown*, kept apart from [ScreenTime], which decides what
 * it means.
 *
 * That split is the same one the sibling file exists for. [ScreenTime] answers
 * "is the user over their allowance right now", which is the question the pet's
 * health hangs on; this answers "what does the settings screen put on the row",
 * which can be wrong without anything breaking — and is therefore exactly the
 * kind of thing worth pinning with tests.
 *
 * ### The one thing to understand before changing any of it
 *
 * **The allowance is per sitting, not per day.** `ScreenTime.overusingPackage`
 * compares `now - lastResume` against it, so it is a limit on how long you may
 * stay in an app in one go, and closing the app is what lets the pet recover. A
 * daily budget would read more naturally on this screen and would break that: a
 * cumulative total never falls, so the pet could not get better until midnight.
 *
 * ### The row shows THIS SESSION, and it took four goes to get there
 *
 * The Claude Design screen 2e draws "37 m / 25 m — used / allowed".
 *
 * 1. **The first build refused the pairing** and stated the day alone, on the
 *    grounds that dividing a day by a sitting claims a comparison the system
 *    never makes. Correct, and unreadable: "34 m today" beside a bar tells you
 *    nothing about whether 34 is a lot.
 * 2. **The second paired them anyway** and put the difference in the column
 *    heading — "today / allowance per sitting". It read well in the mock, where
 *    the example was `2 / 5`.
 * 3. **It failed in use, and was reported as the tracking being broken**
 *    (2026-08-09). With a real day and a 25-minute allowance the numerator
 *    passes the denominator before lunch, so the bar was full and red every
 *    afternoon **with no way to reset it** — because nothing about a day total
 *    is resettable. Worse, red is supposed to mean *the allowance is gone*,
 *    which is what makes the pet ill; the pet is governed by the sitting, so the
 *    row sat red while the pet was well.
 *
 * 4. **The live sitting was right about the quantity and unshowable.** Steps 1–3
 *    argued about which number; step 3 finally picked the one the allowance
 *    governs, and it read **zero every time anyone looked** — because looking at
 *    this page means you are not in the tracked app. Measured on the device: 75
 *    seconds in Instagram moved the day from 51 to 53 minutes and left the row
 *    at `0 / 10 min` before and after. Reported, again correctly, as the bar
 *    never changing.
 *
 * 5. **The longest session today** was step 4's answer and lasted an hour. It
 *    is visible, but it only moves when you beat your record — measured on the
 *    device going 54 → 59 minutes of use with the bar stuck on `7 / 10`.
 *
 * **So the number on the row is THIS SESSION**: live and growing while the app
 * is open, holding the value it ended on once you close it. It moves every time
 * you use the app, which is what the other three could not do, and while the app
 * is open it is *the same number* `overusingPackage` compares — so the bar going
 * red and the pet falling ill are one event. See `ScreenTime.latestSitting`.
 *
 * **Closing the app resets the allowance, not the reading.** The pet recovers;
 * the row goes on saying how long that session ran. Those are different jobs.
 *
 * The day total did not go away — it is stated as a **fact**, in its own words,
 * never as a numerator. That is what step 1 was right about, and it costs
 * nothing once it is not pretending to be half of a fraction.
 *
 * **The pattern across all four is worth more than any of them:** each revision
 * was right about the thing the last one got wrong and wrong about something
 * new, and only two of the four faults were visible without holding a phone.
 */
object ScreenTimeDisplay {

    /*
     * ---- HOW THE MECHANIC IS EXPLAINED ------------------------------------
     *
     * **This page used to describe the PET and not the rule.** Every line was in
     * the voice the app uses for a pet's condition — "Without this your pet
     * cannot fall ill" — which says what state the pet is in without ever saying
     * what the user does or what it costs them. Somebody arriving could not
     * learn the mechanic from the screen that configures it.
     *
     * **Three sentences, and the owner wrote them.** The first attempt at fixing
     * this explained the rule correctly and at length, including a fourth
     * sentence about the drained scores not returning. That is true —
     * `pet_sim.c` keeps the damage and drops only the condition — and it was
     * still cut, because a rule nobody finishes reading teaches nothing. The
     * fourth fact is not lost: a pet visibly short on happiness is the same
     * information, delivered by the thing itself.
     *
     * **"Per sitting" is deliberately not here either**, and that is safe rather
     * than sloppy: the allowance stepper says *"Allowance per sitting"* at the
     * moment somebody sets one, which is where the distinction is load-bearing.
     * Repeating it in the intro was a third of the paragraph spent on a word
     * that arrives again ten seconds later.
     */

    /** The page's subtitle, and the settings index row's. One definition. */
    const val SUBTITLE = "Choose which apps count, and how long is too long"

    /** The rule. Three sentences — see [SENTENCE_LIMIT]. */
    const val HOW_IT_WORKS =
        "Set screen time limits for apps. Go over and your pet falls ill. " +
        "Close the app and it gets better."

    /**
     * What the rule may not exceed.
     *
     * **Pinned because this copy has now been too long twice.** Length is the
     * failure mode here rather than inaccuracy: every sentence anyone wanted to
     * add was true, and the paragraph still stopped being read. A number is the
     * only form of "concise" a test can hold.
     */
    const val SENTENCE_LIMIT = 3

    /** Usage access missing: the state in which none of this does anything. */
    const val NO_ACCESS = "Without usage access nothing here has any effect."

    /** No apps chosen yet: says what to do, not what the pet cannot do. */
    const val NOTHING_TRACKED = "No apps tracked yet. Add one to start."


    /**
     * How much one press moves the allowance.
     *
     * **Was 5, and was the same constant as the floor** — one value doing two
     * jobs because "the smallest allowance worth having" and "the step" happened
     * to be the same number. They are separate now: at a step of 1 they would be
     * equal by accident rather than by intent, and the next person to change one
     * would silently change the other.
     */
    const val STEP_MINUTES = 1L

    /**
     * The floor. Zero would mean an allowance that is spent the instant an app
     * opens, so the pet would be permanently ill and the setting would have no
     * off position short of untracking the app — which is what the pencil's
     * "Stop tracking" is for.
     */
    const val MIN_MINUTES = 1L

    /** Above this an "allowance for one sitting" has stopped meaning anything. */
    const val MAX_MINUTES = 120L

    /**
     * What a newly tracked app gets.
     *
     * **Was 25, from the design's add sheet.** Five is a deliberate move away
     * from that: 25 minutes in one sitting is a long time to hold a phone before
     * a pet minds, and an allowance nobody crosses is a mechanic nobody meets.
     * The design system still draws 25 and has not been regenerated; that
     * disagreement is recorded in DESIGN.md §7.3, where it is the narrow
     * exception §7.2 allows — the code decides what a number MEANS, and this is
     * how long a sitting may run before the pet minds, not a visual token.
     */
    const val DEFAULT_MINUTES = 5L

    private const val MINUTE_MS = 60_000L

    val DEFAULT_MS = DEFAULT_MINUTES * MINUTE_MS

    /**
     * A duration as the row states it: whole minutes, hours once there are any.
     *
     * Rounds DOWN, deliberately. This sits next to an allowance and the user is
     * asking "have I gone past it" — rounding 24.6 minutes up to 25 would answer
     * yes when the truth is no, and a screen-time app that overstates you is one
     * you stop believing.
     */
    fun duration(ms: Long): String {
        val totalMinutes = (ms.coerceAtLeast(0L)) / MINUTE_MS
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "$hours h $minutes m" else "$minutes m"
    }

    /** An allowance, which is always minutes because [MAX_MINUTES] caps it. */
    fun allowance(ms: Long): String = "${ms.coerceAtLeast(0L) / MINUTE_MS} min"

    /**
     * The numbers: `"12 / 25 min"`, or `"40 s / 25 min"` inside the first
     * minute — **this session against the allowance**, both sides measuring the
     * same thing.
     *
     * The denominator is the allowance, which is what makes a fraction readable
     * at a glance. Unlike the three readings this replaced, the two sides are
     * now the same quantity, so the fraction is a fraction rather than two facts
     * sharing a slash. See the note at the top of this file for all four.
     *
     * **Zero is shown rather than hidden**, for an app not opened today. An
     * empty row would leave someone wondering whether it had reset or simply
     * stopped counting.
     *
     * [sessionReading] is what the row actually renders; this is the numbers
     * without the label.
     */
    fun sitting(sittingMs: Long, allowanceMs: Long): String {
        val ms = sittingMs.coerceAtLeast(0L)
        val allowance = allowanceMs.coerceAtLeast(0L) / MINUTE_MS
        /*
         * SECONDS BELOW A MINUTE, and it is not cosmetic.
         *
         * [duration] rounds down so the app never overstates you, which is right
         * — and applied to a live session it meant anything under sixty seconds
         * read `0`. A glance at Instagram showed nothing happening, which is the
         * same "it isn't working" this row has already been reported for three
         * times. The number is live now, so the first minute is exactly when
         * somebody is watching it.
         *
         * The unit is said twice here where it is said once above a minute. That
         * is the cost of the two scales meeting, and it beats the alternatives:
         * `0.7 / 10 min` implies a precision the allowance does not have, and
         * `<1 / 10 min` says the one thing the reader already knows.
         */
        return if (ms < MINUTE_MS) "${ms / 1000} s / $allowance min"
               else "${ms / MINUTE_MS} / $allowance min"
    }

    /**
     * The label the reading carries: **"Last session:"**.
     *
     * A constant rather than a literal at the call site so that
     * `StringSyncTest` can hold it against `design-system/strings.txt` — copy is
     * the design system's, and this row has changed what it means four times.
     * Saying which session it is, on the row itself, is what stops the fifth
     * reader having to infer it from a column heading.
     */
    const val SESSION_LABEL = "Last session:"

    /** The row's whole reading: `"Last session: 40 s / 10 min"`. */
    fun sessionReading(sittingMs: Long, allowanceMs: Long): String =
        "$SESSION_LABEL ${sitting(sittingMs, allowanceMs)}"

    /**
     * The day's total, as a fact and in its own words: `"1 h 12 m today"`.
     *
     * **Never a numerator.** It carries the word "today" so that it cannot be
     * read as belonging to the allowance beside it, which is the whole failure
     * this replaced — see the note at the top of this file.
     */
    fun today(ms: Long): String = "${duration(ms)} today"

    /**
     * How much of the bar to fill: **this session** against the allowance,
     * capped at full.
     *
     * **One denominator, and that is the whole change.** This used to scale to
     * whichever of the two was larger, so that a bar past the allowance could be
     * split into an amber part and a red one. It was clever and it was unreadable
     * — going further over made the amber portion *shrink*, which looks like
     * less progress rather than more. Reported as "difficult to interpret", and
     * that was right.
     *
     * Now length means one thing only: how far through the allowance you are.
     * Full means at or past it, and how far past is the fraction's job, not the
     * bar's. The colour says which side of the line you are on.
     *
     * **`usedMs` is a SESSION, not the day**, and specifically the most recent
     * one. Feeding it the day made the bar full and red every afternoon with
     * nothing able to empty it; feeding it the live sitting made it read zero
     * whenever anyone looked. See the note at the top of this file for all four
     * attempts.
     */
    fun barFraction(usedMs: Long, allowanceMs: Long): Float {
        val used = usedMs.coerceAtLeast(0L)
        val allowance = allowanceMs.coerceAtLeast(0L)
        // A zero allowance cannot be divided by and cannot be met: any use at
        // all is already past it.
        if (allowance <= 0L) return if (used > 0L) 1f else 0f
        // Only an upper bound. A lower one reads as prudent and is dead: `used`
        // is already clamped at zero above and `allowance` is positive here, so
        // the quotient cannot be negative — mutation showed removing it changed
        // nothing, which is the definition of a guard that is not guarding.
        return (used.toFloat() / allowance).coerceAtMost(1f)
    }

    /**
     * True once the whole allowance is spent — which is exactly when the bar is
     * full, because that is how this is defined.
     *
     * **Derived from [barFraction] rather than compared separately**, so "the
     * bar is red" and "the bar is full" cannot drift apart. Two independent
     * comparisons would agree today and disagree the first time either side is
     * touched, and the disagreement would be a bar that is full and not red.
     *
     * **At the allowance counts, not just past it**, and that is a correction
     * rather than a rounding choice: `ScreenTime.overusingPackage` tests
     * `now - lastResume >= threshold`, so the pet falls ill *at* the limit. A
     * display that waited for strictly-greater would call the pet healthy at the
     * moment it started being made ill. The previous `isOver` did exactly that,
     * and its name — true only when genuinely over — is why this one is called
     * something else.
     */
    fun hasSpentAllowance(usedMs: Long, allowanceMs: Long): Boolean =
        barFraction(usedMs, allowanceMs) >= 1f

    /**
     * The stepper, one [STEP_MINUTES] at a time, clamped to [MIN_MINUTES]..[MAX_MINUTES].
     *
     * **Snaps to the grid on the way**, which at a step of 1 means only that a
     * part-minute is dropped rather than carried: a stored 7 min 30 s steps up
     * to 8 and down to 6, not to 8:30 and 6:30. It mattered more at a step of 5,
     * when an off-grid 7 had to land on 10 or 5 rather than 12 or 2 — values
     * from an older build, or from editing prefs by hand, still exist and still
     * come back onto the grid here.
     *
     * The clamp is unconditional on purpose: an out-of-range value is pulled
     * back by EITHER button, so pressing the one that would make it worse does
     * not preserve it.
     */
    fun step(ms: Long, up: Boolean): Long {
        val minutes = ms.coerceAtLeast(0L) / MINUTE_MS
        val stepped = if (up) {
            (minutes / STEP_MINUTES) * STEP_MINUTES + STEP_MINUTES
        } else {
            // Ceiling division, so an off-grid value comes down to the grid
            // rather than through it.
            ((minutes + STEP_MINUTES - 1) / STEP_MINUTES) * STEP_MINUTES - STEP_MINUTES
        }
        return stepped.coerceIn(MIN_MINUTES, MAX_MINUTES) * MINUTE_MS
    }
}
