package com.digitalpet.conversation

import com.digitalpet.conversation.NotificationIntent.Ask
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The routing that makes "summarise my notifications" reach the summariser.
 *
 * **The false-positive cases matter more than the positive ones**, and that is
 * why there are more of them below. A missed match is the old behaviour — the
 * pet answers conversationally — but a wrong match makes it read your
 * notifications out loud in whatever room you are in. So the interesting
 * assertions are the ones about what must NOT trigger.
 */
class NotificationIntentTest {

    private fun assertAsk(expected: Ask, vararg phrases: String) {
        phrases.forEach {
            assertEquals("\"$it\"", expected, NotificationIntent.of(it))
        }
    }

    @Test
    fun `asking what they say wants the contents`() {
        assertAsk(
            Ask.SUMMARY,
            "summarise my notifications",
            "summarize my notifications",
            "can you give me a summary of my notifications?",
            "what do my notifications say?",
            "read me my notifications",
            "read out my notifications",
            "tell me about my notifications",
            "go through my notifications",
            "run through my notifications please",
            "catch me up on my notifications",
        )
    }

    @Test
    fun `asking how many wants the count`() {
        assertAsk(
            Ask.COUNT,
            "any notifications?",
            "do i have any notifications",
            "how many notifications do i have",
            "are there any notifications waiting",
            "have i got notifications",
            "check my notifications",
            "anything in my notifications?",
        )
    }

    @Test
    fun `the bare subject is a glance, not a reading`() {
        // Cheap over expensive when the ask is ambiguous: someone who wants them
        // read out says so, and the count costs no inference.
        assertAsk(Ask.COUNT, "notifications", "my notifications", "notifications?")
    }

    @Test
    fun `content wins over count when both are present`() {
        // "do i have any" would take the count branch on its own; the content
        // marker is the more specific ask and is checked first.
        assertAsk(
            Ask.SUMMARY,
            "do i have any notifications, and what do they say?",
            "any notifications? read them to me",
        )
    }

    @Test
    fun `nothing without the subject word, however much it sounds like one`() {
        /*
         * THE WHOLE SAFETY MARGIN IS HERE. Every one of these is a plausible
         * thing to say to a pet, and every one of them would be answered by
         * reading private content aloud if the trigger were widened to intent
         * words alone. "What's new?" is the one that would be most tempting to
         * add and is exactly the phrase a person uses to make small talk.
         */
        assertAsk(
            Ask.NONE,
            "what's new?",
            "anything for me?",
            "how many treats have you had",
            "tell me about your day",
            "read me a story",
            "summarise the plot of that film",
            "what do you say when you are hungry",
            "any good jokes?",
            "check my messages",
            "how are you doing today?",
        )
    }

    @Test
    fun `case and punctuation do not matter`() {
        assertAsk(Ask.SUMMARY, "SUMMARISE MY NOTIFICATIONS", "  Summarise my Notifications!  ")
        assertAsk(Ask.COUNT, "ANY NOTIFICATIONS?")
    }

    @Test
    fun `a mangled transcript still routes`() {
        // Whisper is `ggml-tiny.en` and long words are where it fails — "pet"
        // has come back as "Pops" and "cats" as "past". The stem is what makes
        // this survive that, and it is worth a test because the pet's own
        // microphone is the input this feature is FOR.
        assertAsk(Ask.COUNT, "any notifs?", "do i have any notifications")
        assertAsk(Ask.SUMMARY, "read my notifs to me")
    }
}
