package com.digitalpet.pet

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The *Your pet* page's descriptions, pinned the same way the other two were.
 *
 * Three pages have now drifted the same direction, so the guards are the two
 * things that went wrong each time: **the pet's voice**, and **length**.
 */
class PetDeviceTextTest {

    @Test
    fun `a phone-side state is never described as the pet's condition`() {
        /*
         * THE RULE, CORRECTED. This first read "no description may start with
         * 'Your pet'", which was a proxy and was WRONG — the quiet-hours line
         * legitimately opens that way, because quiet hours govern the pet's own
         * behaviour and the pet is the correct subject.
         *
         * The real mistake is narrower: describing a PHONE-side state in pet
         * terms. "Without this your pet cannot fall ill" was about a permission,
         * and reported the animal's prospects instead of what the toggle does.
         * So the lines checked here are the ones about the phone.
         */
        assertTrue(
            PetDeviceText.SUBTITLE,
            !PetDeviceText.SUBTITLE.contains("pet is", ignoreCase = true),
        )
        assertTrue(
            "the offline hint is an instruction, not a report: ${PetDeviceText.QUIET_HOURS_OFFLINE}",
            PetDeviceText.QUIET_HOURS_OFFLINE.startsWith("Connect"),
        )
    }

    @Test
    fun `quiet hours says the care mechanic pauses, not just the speaker`() {
        // The version this replaced — "No sound or movement between these
        // hours" — was shorter and vague. Quiet hours are not a mute switch:
        // pet_sim.c says the pet "does not decay, call, or die" in the window
        // and ages by WAKING seconds only, so the whole mechanic stops.
        val t = PetDeviceText.QUIET_HOURS
        assertTrue("does not say it stays quiet: $t", t.contains("quiet"))
        assertTrue("does not say the decay pauses: $t", t.contains("hungry"))
        assertTrue("does not say the decay pauses: $t", t.contains("unhappy"))
    }

    // ---- the destructive confirmations --------------------------------------

    @Test
    fun `both dialogs still name what is lost`() {
        // The rule these exist for. "Are you sure?" tells somebody nothing they
        // did not already know.
        val clear = PetDeviceText.clearConversation(3)
        assertTrue(clear, clear.contains("3 messages") && clear.contains("cannot be undone"))
        // And the answer to the question the count raises.
        assertTrue("must say the pet keeps its own screen: $clear", clear.contains("its own screen"))

        val reset = PetDeviceText.startNewPet(0)
        assertTrue(reset, reset.contains("scores") && reset.contains("age") && reset.contains("history"))
        assertTrue(reset.contains("cannot be undone"))
    }

    @Test
    fun `the reset mentions the transcript only when there is one`() {
        // A sentence about deleting zero messages is a worry invented for the
        // occasion — but a transcript vanishing unannounced is exactly what
        // somebody comes looking for afterwards, so it is named when it exists.
        assertTrue(!PetDeviceText.startNewPet(0).contains("phone"))
        assertTrue(PetDeviceText.startNewPet(2).contains("2 messages from this phone"))
    }

    @Test
    fun `real plurals, in the last place the lazy form survived`() {
        // "message(s)" in a destructive dialog looks unfinished, and
        // careMistakesLabel has done this properly since it was written.
        assertTrue(PetDeviceText.clearConversation(1).contains("1 message from"))
        assertTrue(PetDeviceText.clearConversation(2).contains("2 messages from"))
        assertTrue(PetDeviceText.startNewPet(1).contains("1 message from"))
    }

    @Test
    fun `every description stays under the limit`() {
        // Length is the failure mode that keeps returning, and every clause cut
        // from these was TRUE — which is exactly why a habit is not enough and a
        // number is.
        PetDeviceText.all.forEach {
            assertTrue("${it.length} chars, limit ${PetDeviceText.LIMIT}: $it", it.length <= PetDeviceText.LIMIT)
        }
    }

    @Test
    fun `each one still says the thing it exists to say`() {
        // Concise is not the same as empty. These are the facts that would make
        // somebody change their mind about a setting, and cutting them would
        // trade one failure for another.
        assertTrue(PetDeviceText.ANNOUNCE.contains("quiet hours"))
        assertTrue("must say it never names two apps", PetDeviceText.ANNOUNCE.contains("two apps"))
        // CONVERSATION describes the BUTTON underneath it, not the memory
        // model. It said the latter twice — once at length, once briefly — and
        // both times answered a question nobody on a settings page was asking.
        assertTrue("must say what the button does", PetDeviceText.CONVERSATION.contains("Clear chat history"))
        // The warning belongs to the confirmation, where it is true and
        // unavoidable. Attached here it warns somebody for reading.
        assertTrue(
            "the undo warning belongs to the dialog: ${PetDeviceText.CONVERSATION}",
            !PetDeviceText.CONVERSATION.contains("undone", ignoreCase = true) &&
                !PetDeviceText.CONVERSATION.contains("can't be undone", ignoreCase = true),
        )
        assertTrue(PetDeviceText.clearConversation(1).contains("cannot be undone"))
        assertTrue(PetDeviceText.QUIET_HOURS_OFFLINE.contains("Connect the pet"))
    }

    @Test
    fun `quiet hours reads as one sentence with the offline half appended`() {
        // The two are concatenated on screen when the pet is away, so the join
        // has to make a sentence rather than a run-on.
        val joined = PetDeviceText.QUIET_HOURS + " " + PetDeviceText.QUIET_HOURS_OFFLINE
        assertTrue(joined, joined.contains(". "))
        assertTrue(PetDeviceText.QUIET_HOURS.endsWith("."))
    }
}
