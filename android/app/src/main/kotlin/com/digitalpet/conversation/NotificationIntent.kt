package com.digitalpet.conversation

/**
 * Is the user asking about their notifications, and do they want the count or
 * the contents?
 *
 * **Why this exists.** `showNotificationCounts()` and `summarizeNotifications()`
 * were both complete, both correct, and both unreachable — their only caller was
 * a suggestion chip that could not appear, because the count it was gated on was
 * written *inside* the two functions the chip called. Asking in words did not
 * reach them either: every input converges on `sendMessage`, which goes straight
 * to the pet persona with no notification data in the prompt at all, so the model
 * answered a question about notifications having never seen one.
 *
 * So the routing is the feature. This is the part of it that can be tested.
 *
 * ### Keyword matching, and why that is the right amount of cleverness
 *
 * The obvious alternative is to let the model decide — a tool call, or a
 * classifier turn. Both are wrong here for the same reason: `DEFAULT_HISTORY_MESSAGES`
 * is 0 and a turn costs 1.4–3.4 s on this phone, so asking the model what the
 * user meant would double the latency of every message to route a small
 * fraction of them.
 *
 * The cost of being wrong is also asymmetric, which is what makes a keyword
 * list acceptable. A false negative is the status quo — the pet answers
 * conversationally, which is what it does today. A false positive reads out a
 * summary nobody asked for, so the trigger requires the word **notification**
 * (or "notif"): "anything for me?" and "what's new?" deliberately do not match.
 * Narrow and predictable beats broad and surprising for something that speaks
 * aloud in a room.
 */
object NotificationIntent {

    enum class Ask {
        /** How many are waiting. Instant, no model, fits the pet's screen. */
        COUNT,

        /** What they actually say. Costs inference and produces real text. */
        SUMMARY,

        /** Not about notifications. Goes to the pet as an ordinary message. */
        NONE,
    }

    /**
     * The subject has to be present or nothing matches. Stemmed rather than
     * listed so "notification", "notifications" and the spoken-transcript
     * "notif" all hit — Whisper's `tiny.en` mangles long words, and this is a
     * phrase people say out loud.
     */
    private val SUBJECT = listOf("notification", "notifs", "notif ")

    /**
     * Content markers. Checked FIRST, because they are the more specific ask:
     * "do I have any notifications and what do they say" wants the summary, and
     * "any" would otherwise win it for the count.
     */
    private val WANTS_CONTENT = listOf(
        "summar",          // summarise, summarize, summary
        "what do they say", "what do my", "what does it say", "what did they say",
        "read", "tell me about", "tell me what", "go through",
        "what are they about", "run through", "catch me up",
    )

    /** Quantity or existence. */
    private val WANTS_COUNT = listOf(
        "any ", "any?", "anything", "how many", "got ", "have i got",
        "do i have", "are there", "is there", "what are they", "check my",
        "waiting",
    )

    fun of(text: String): Ask {
        val t = text.lowercase().trim()
        if (SUBJECT.none { t.contains(it) }) return Ask.NONE
        if (WANTS_CONTENT.any { t.contains(it) }) return Ask.SUMMARY
        if (WANTS_COUNT.any { t.contains(it) }) return Ask.COUNT
        /*
         * The subject with no verb around it — "notifications?", "my
         * notifications" — is the cheap answer, not the expensive one. Someone
         * who wanted them read out asks for that; someone who says the word on
         * its own is glancing.
         */
        return Ask.COUNT
    }
}
