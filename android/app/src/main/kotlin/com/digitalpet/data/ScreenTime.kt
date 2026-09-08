package com.digitalpet.data

/**
 * Deciding whether the user is *currently* over a screen-time threshold.
 *
 * Extracted from `PetForegroundService` for the usual reason in this project:
 * it is the only part of screen-time monitoring that is a decision rather than
 * a system call, and it is the part that can be wrong in ways nothing shouts
 * about. `UsageStatsManager` cannot be run in a unit test; this can.
 *
 * The extraction earns its keep twice over now that phase 4 exists. The same
 * answer drives two different things — the one-shot LLM nudge, and the level
 * written to the pet every minute — and if those two ever disagreed the pet
 * would be ill while the phone believed it had said nothing, or the reverse.
 * One function, one answer.
 */
object ScreenTime {

    /**
     * The most recent foreground transition seen for one package.
     *
     * @param resumed   true if the app came to the foreground, false if it left.
     *                  Android distinguishes PAUSED from STOPPED; nothing here
     *                  needs to, so the caller collapses both.
     * @param timestamp when it happened, in `System.currentTimeMillis()` terms.
     */
    data class Transition(val resumed: Boolean, val timestamp: Long)

    /**
     * Which monitored app the user is over their threshold on right now, or
     * null if none.
     *
     * @param latest      the LAST transition seen per package. Anything earlier
     *                    is irrelevant: what matters is whether the app is in
     *                    the foreground now and how long it has been.
     * @param thresholds  per-package allowance in milliseconds.
     * @param now         current wall-clock time.
     */
    fun overusingPackage(
        latest: Map<String, Transition>,
        thresholds: Map<String, Long>,
        now: Long
    ): String? =
        latest.asSequence()
            // An app whose last transition was a pause or a stop is not on
            // screen, however long it was open before that. This is the whole
            // reason the *last* event is what gets collected: a session that
            // ended is not a session.
            .filter { (pkg, t) -> t.resumed && thresholds.containsKey(pkg) }
            .filter { (pkg, t) -> now - t.timestamp >= thresholds.getValue(pkg) }
            // Only one app is actually in the foreground, but two can look
            // resumed at once — the older one's pause event can fall outside
            // the lookback window and simply not be there to see. The most
            // recent resume is the one the user is really in.
            .maxByOrNull { (_, t) -> t.timestamp }
            ?.key

    /**
     * How long the user has been in [packageName], or 0 if it is not resumed.
     * Only used to phrase the nudge, so a missing package is not an error.
     */
    fun elapsedMs(latest: Map<String, Transition>, packageName: String, now: Long): Long {
        val t = latest[packageName] ?: return 0L
        return if (t.resumed) (now - t.timestamp).coerceAtLeast(0L) else 0L
    }

    /**
     * [elapsedMs] for every monitored package — **the number the screen-time
     * page shows**, as of 2026-08-09.
     *
     * Before then that page showed the day's total against the per-sitting
     * allowance, which is two different quantities either side of a divide: the
     * day only ever grows, so the bar filled within one sitting's worth of use
     * and stayed red until midnight with nothing able to reset it. And red meant
     * *the allowance is gone* while the pet — governed by the sitting — was
     * frequently perfectly well, which is a screen asserting a state the
     * simulation does not have.
     *
     * **Every package gets an entry, including a zero.** A package absent from
     * [latest] and a package that is closed are the same thing to a reader — it
     * is not open — and returning a map with holes in it would make the caller
     * decide that again, differently. Zero here means closed, which is exactly
     * the reset the screen needs to be able to show.
     */
    fun sittings(
        latest: Map<String, Transition>,
        packages: Set<String>,
        now: Long,
    ): Map<String, Long> = packages.associateWith { elapsedMs(latest, it, now) }

    /**
     * A pause that is really an activity handover, not the user leaving.
     *
     * Android emits `ACTIVITY_PAUSED` for the outgoing activity and
     * `ACTIVITY_RESUMED` for the incoming one when an app moves between its own
     * screens, so a naive pairing chops one session into several. A gap this
     * short is that handover; a person putting the phone down and picking it up
     * again inside a second is not a case worth modelling.
     */
    private const val HANDOVER_MS = 1_000L

    /**
     * Today's sessions in one app, in order, as durations.
     *
     * A session runs from a resume to the pause that ends it, and an unclosed
     * one runs to [now] — so the session you are *in* is the last element and it
     * grows while you stay there.
     */
    fun sessions(transitions: List<Transition>, now: Long): List<Long> {
        val out = mutableListOf<Long>()
        var openedAt: Long? = null
        var closedAt: Long? = null
        for (t in transitions.sortedBy { it.timestamp }) {
            if (t.resumed) {
                if (openedAt == null || (closedAt != null && t.timestamp - closedAt!! > HANDOVER_MS)) {
                    if (openedAt != null && closedAt != null) out += closedAt!! - openedAt!!
                    openedAt = t.timestamp
                }
                closedAt = null
            } else {
                if (openedAt != null) closedAt = t.timestamp
            }
        }
        openedAt?.let { out += (closedAt ?: now) - it }
        return out.map { it.coerceAtLeast(0L) }
    }

    /**
     * **The most recent session** — what the screen-time row shows.
     *
     * This is the quantity the allowance governs, shown as it stands: live and
     * growing while the app is open, and holding the value it ended on once the
     * app is closed. Close the app and the *allowance* resets — the pet stops
     * being made ill, `overusingPackage` sees a pause and reports OK — while the
     * row goes on saying how long that session ran. The reset is the mechanic;
     * the reading is the record of what you just did.
     *
     * ### It took four goes and each failure was a different shape
     *
     * 1. **The day's total.** Only ever grows, so the bar was full and red every
     *    afternoon with nothing able to empty it.
     * 2. **The live sitting.** The right quantity and unshowable: you cannot be
     *    in the tracked app and looking at this page at once, so it read zero
     *    every time. Measured — 75 s in Instagram left the row at `0 / 10 min`.
     * 3. **The longest session today.** Visible, and it only moves when you beat
     *    your record — measured going 54→59 minutes of use without the bar
     *    shifting off `7 / 10`, which is what "not reflecting my use" meant.
     * 4. **This.** Moves with every session, survives closing the app, and is
     *    the same number the pet is judged by while you are in there.
     *
     * **The pattern is worth more than the answer**: each attempt was right
     * about the fault before it, and only one of the four was visible without a
     * phone in someone's hand.
     */
    fun latestSitting(transitions: List<Transition>, now: Long): Long =
        sessions(transitions, now).lastOrNull() ?: 0L

    /** [latestSitting] for every monitored package, zero for one not opened today. */
    fun latestSittings(
        transitions: Map<String, List<Transition>>,
        packages: Set<String>,
        now: Long,
    ): Map<String, Long> =
        packages.associateWith { latestSitting(transitions[it].orEmpty(), now) }
}
