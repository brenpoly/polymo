package com.digitalpet.conversation

/**
 * What the pet says when something arrives on your phone, and whether it says
 * anything at all.
 *
 * ### Why the pet speaks instead of showing a number
 *
 * The first version of this drew a count in the corner of the pet's screen. It
 * worked, and it made the pet a small smartwatch — a thing that mirrors your
 * phone, which is the opposite of what CLAUDE.md means by "the phone is compute;
 * it is not a participant". A pet that *tells you* something arrived is a
 * creature noticing; a badge is a status bar.
 *
 * It also needed no protocol at all. The phone already synthesises speech and
 * streams it to the pet's speaker, so the pet talking is the mechanism that was
 * already there.
 *
 * ### Everything here is pure
 *
 * No Android types, no clock of its own, no BLE. The caller passes the time and
 * the state; this decides. That is what makes the policy testable, and the
 * policy is the part that decides whether this feature is delightful or the
 * reason someone unplugs the pet.
 */
object PetAnnouncement {

    /**
     * One notification: the app it came from, and the KEY that identifies it.
     *
     * The key is why this is a data class rather than a string. Android fires
     * `onNotificationPosted` again every time a notification is UPDATED, with
     * the same key — an app touching its own notification is not a second
     * arrival, and counting it as one is how the pet came to announce two
     * emails when the phone was showing one.
     */
    data class Item(val appLabel: String, val key: String = "")

    /**
     * Wait this long after the first arrival before speaking.
     *
     * Three emails landing together should be one sentence, not three. The
     * window is short enough to still feel like a reaction and long enough to
     * catch a burst — a sync finishing typically delivers within a second or so.
     */
    const val COALESCE_MS = 4_000L

    /**
     * The floor between two announcements.
     *
     * Without it a busy morning is a pet that talks over itself continuously,
     * and the first thing anyone would do is turn the feature off — so this is
     * what keeps it on.
     */
    const val MIN_GAP_MS = 45_000L

    /** Why nothing was said. Worth naming: "it was silent" has several causes
     *  and they need different fixes. */
    enum class Silence { SPOKE, TOO_SOON, QUIET, DEAD, DISABLED, NOTHING_WAITING }

    data class Decision(val line: String?, val why: Silence) {
        val spoke: Boolean get() = why == Silence.SPOKE
    }

    /**
     * Should the pet speak, and what should it say?
     *
     * [lastSpokenAt] is null when it has not spoken yet. [asleep] is the pet's
     * own quiet hours, reported over Condition — the pet owns when it sleeps and
     * this respects that rather than inventing a second set of hours.
     */
    fun decide(
        items: List<Item>,
        now: Long,
        lastSpokenAt: Long?,
        quiet: Boolean,
        dead: Boolean,
        enabled: Boolean,
        persona: PetPersona = PetPersonas.default,
    ): Decision {
        if (!enabled) return Decision(null, Silence.DISABLED)
        if (items.isEmpty()) return Decision(null, Silence.NOTHING_WAITING)
        // A dead pet does not talk. It is the one state that cannot be undone,
        // and having it cheerfully read out your email would undo it.
        if (dead) return Decision(null, Silence.DEAD)
        if (quiet) return Decision(null, Silence.QUIET)
        if (lastSpokenAt != null && now - lastSpokenAt < MIN_GAP_MS) {
            return Decision(null, Silence.TOO_SOON)
        }
        return Decision(line(items, persona), Silence.SPOKE)
    }

    /**
     * The sentence itself.
     *
     * Names the app only when there is ONE app to name. "Three new
     * notifications from Gmail, Slack and Hinge" is a sentence that reads your
     * life out to a room; "three new notifications" is the same information
     * minus the part that is nobody else's business. The single case is the
     * common one and the one where the app name is actually useful.
     */
    fun line(items: List<Item>, persona: PetPersona = PetPersonas.default): String {
        // ONE PER KEY. A notification updated three times is one notification,
        // and the phone is showing one — an unkeyed item (key "") is counted
        // individually, since we have nothing to tell it apart by.
        val unique = items.filter { it.key.isNotEmpty() }.distinctBy { it.key } +
            items.filter { it.key.isEmpty() }
        val apps = unique.map { it.appLabel }.filter { it.isNotBlank() }.distinct()
        val n = unique.size
        // The templates are the persona's; the counting and the privacy rule
        // are not. A persona chooses the words, not whether several apps get
        // named — the generator refuses one whose `many` line names an app.
        return when {
            n == 1 && apps.size == 1 ->
                persona.announceOne.replace("{app}", apps[0])
            apps.size == 1 ->
                persona.announceManyFromOne
                    .replace("{count}", spell(n)).replace("{app}", apps[0])
            else ->
                persona.announceMany.replace("{count}", spell(n))
        }
    }

    /**
     * Small numbers as words, because this is going through a speech
     * synthesiser rather than onto a screen. Piper reads "3" acceptably and
     * "three" reliably, and the pet has a voice rather than a display.
     */
    private fun spell(n: Int): String = when (n) {
        2 -> "two"; 3 -> "three"; 4 -> "four"; 5 -> "five"
        6 -> "six"; 7 -> "seven"; 8 -> "eight"; 9 -> "nine"
        else -> if (n > 9) "$n" else "$n"
    }
}
