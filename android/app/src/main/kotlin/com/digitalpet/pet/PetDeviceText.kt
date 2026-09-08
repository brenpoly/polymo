package com.digitalpet.pet

/**
 * What the *Your pet* page says about each control.
 *
 * **The third page to be rewritten out of the pet's voice**, after Screen time
 * and Permissions, and the tell is the same each time: a line that opens *"Your
 * pet …"* is describing the animal's experience where the reader wanted to know
 * what a control does. "Your pet sleeps, stops animating and stays quiet between
 * these hours" is a nice sentence about a pet and a poor label for a time range.
 *
 * **Length is the other half, and it is the half that keeps coming back.** Every
 * clause that got cut here was true — the conversation blurb explained *why* the
 * pet has no memory, which is genuinely interesting and is not what somebody
 * reading a settings page is there for. See [LIMIT].
 */
object PetDeviceText {

    /**
     * The longest any of these may be.
     *
     * A number rather than a habit, for the reason the screen-time copy needed
     * one: every sentence anyone wants to add is true, so "is this too long" has
     * no natural stopping point without one.
     */
    const val LIMIT = 90

    /** The settings index row. The page itself has no subtitle — see the others. */
    const val SUBTITLE = "Pair your PolyMO and check the connection"

    /** Was: "Your pet says when something arrives. It waits out its quiet hours, and never names more than one app." */
    const val ANNOUNCE = "Reads out new arrivals. Silent in quiet hours, and never names two apps."

    /**
     * **Naming the pet is right here, and my blanket rule against it was wrong.**
     *
     * The first pass replaced "Your pet sleeps, stops animating and stays quiet
     * between these hours" with "No sound or movement between these hours." —
     * shorter, and vague: it described a speaker going silent and left out the
     * half that matters. Quiet hours are not a mute switch. `pet_sim.c`: *"The
     * pet does not decay, call, or die between 21:00 and 09:00"*, and it ages by
     * **waking seconds only** — so the window pauses the care mechanic itself.
     *
     * The distinction the earlier rule was reaching for is real but narrower
     * than "never open with the pet": describing a PHONE-side state in pet terms
     * is the mistake ("Without this your pet cannot fall ill" is about a
     * permission). This setting governs the pet's own behaviour, so the pet is
     * the correct subject. Owner's wording, and it says more in the same space.
     */
    const val QUIET_HOURS =
        "Your pet will stay quiet during these hours. It also won't get hungry or unhappy."

    /** Appended when the pet is not connected, because the pet owns these and stores them itself. */
    const val QUIET_HOURS_OFFLINE = "Connect the pet to change them."

    /**
     * Was three clauses explaining that no history is sent and every reply starts
     * from the sentence in front of it. True, and an answer to "why" when the
     * question on a settings page is "what".
     */
    /**
     * **Describes the button, not the memory model.**
     *
     * This has now been wrong twice in the same direction. First it explained
     * *why* the pet keeps no history — no history is sent, every reply starts
     * from the sentence in front of it — which is true and is an answer to a
     * question nobody on a settings page is asking. Then it was shortened to
     * "The pet has no memory of past messages. This transcript is yours, not
     * its.", which is half the length and still describes the SYSTEM where the
     * reader wanted to know what the button underneath does.
     *
     * **And it does not say "this can't be undone", deliberately.** The
     * confirmation says that, at the moment it is true and unavoidable. A
     * warning attached to a description is a warning attached to reading rather
     * than to acting.
     */
    const val CONVERSATION = "Clear chat history with your pet."

    /*
     * ---- THE TWO DESTRUCTIVE CONFIRMATIONS --------------------------------
     *
     * **These keep their job and lose their length.** The recorded rule is that
     * a confirmation NAMES WHAT IS LOST — "Are you sure?" tells somebody nothing
     * they did not already know — and that survives untouched. What went is the
     * pet-state framing around it: "Your pet has 3 care mistake(s)" opened a
     * destructive dialog by reporting the pet's record, which is neither the
     * question being asked nor the thing at risk.
     *
     * **Real plurals, not "message(s)".** `careMistakesLabel` has done this
     * properly since it was written; these two were the last place the lazy
     * form survived, and a dialog is a poor place to look unfinished.
     */

    /** What clearing the transcript costs. */
    fun clearConversation(messages: Int): String =
        "Deletes ${plural(messages, "message")} from this phone. " +
            "The pet keeps whatever is on its own screen. This cannot be undone."

    /**
     * What a reset costs.
     *
     * The conversation clause appears only when there IS one — a sentence about
     * deleting zero messages is a worry invented for the occasion.
     */
    fun startNewPet(messages: Int): String =
        "Clears its scores, its age and its history" +
            (if (messages > 0) ", and deletes ${plural(messages, "message")} from this phone" else "") +
            ". This cannot be undone."

    private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    /** Every line this object owns, for the tests that cap them. */
    val all = listOf(SUBTITLE, ANNOUNCE, QUIET_HOURS, QUIET_HOURS_OFFLINE, CONVERSATION)
}
