package com.digitalpet.conversation

import com.digitalpet.ble.PetProtocol
import com.digitalpet.conversation.PetVoiceLines.Occasion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pet reacting to its own life — fed, played with, calling out.
 *
 * Derived from Condition transitions rather than from a new wire message, so
 * these tests are about the DERIVATION: what counts as an event, what does not,
 * and what happens on the reading where there is nothing to compare against.
 */
class PetVoiceLinesTest {

    private fun cond(
        satiety: Int = 2,
        happiness: Int = 2,
        calling: Boolean = false,
        dead: Boolean = false,
    ) = PetProtocol.Condition(
        satiety = satiety,
        happiness = happiness,
        calling = calling,
        sick = false,
        dead = dead,
        careMistakes = 0,
    )

    @Test
    fun `a rise in satiety is being fed`() {
        assertEquals(Occasion.FED, PetVoiceLines.reactTo(cond(satiety = 1), cond(satiety = 2)))
    }

    @Test
    fun `a rise in happiness is being played with`() {
        assertEquals(
            Occasion.PLAYED,
            PetVoiceLines.reactTo(cond(happiness = 1), cond(happiness = 2)),
        )
    }

    @Test
    fun `THE FIRST READING CLAIMS NO ACTION`() {
        /*
         * null means we have just connected and have nothing to compare with.
         * A score looks like it rose from nothing, so reacting would greet
         * every reconnection with "that's better" — claiming to have just been
         * fed when it had not been.
         */
        assertNull(PetVoiceLines.reactTo(null, cond(satiety = 4, happiness = 4)))
    }

    @Test
    fun `BUT IT STILL REPORTS A CALL IN PROGRESS`() {
        /*
         * Calling is a STATE, not an event. A pet that has been asking for an
         * hour is still asking, and reconnecting to it in silence is the case
         * where speaking matters most — so the first reading is allowed to
         * report this and nothing else.
         *
         * Found while checking why calling seemed never to fire. It was not the
         * cause, and it would have swallowed the first call after every
         * reconnect: a much harder fault to notice than one that never fires.
         */
        assertEquals(
            Occasion.CALLING,
            PetVoiceLines.reactTo(null, cond(satiety = 0, calling = true)),
        )
        assertNull(PetVoiceLines.reactTo(null, cond(calling = false)))
    }

    @Test
    fun `ONLY THE EDGE OF CALLING, not every reading while it calls`() {
        /*
         * `calling` stays true until somebody answers it, and Condition is
         * notified on every change to anything. Reacting to the LEVEL would
         * make the pet repeat itself on each battery reading — unbearable
         * within a minute, and the sort of thing that only shows up on
         * hardware.
         */
        assertEquals(
            Occasion.CALLING,
            PetVoiceLines.reactTo(cond(calling = false), cond(calling = true)),
        )
        assertNull(PetVoiceLines.reactTo(cond(calling = true), cond(calling = true)))
    }

    @Test
    fun `A DEAD PET SAYS NOTHING, whatever else changed`() {
        // The one state that cannot be undone. Cheerful noises would undo it.
        assertNull(
            PetVoiceLines.reactTo(cond(satiety = 1), cond(satiety = 2, dead = true)),
        )
        assertNull(
            PetVoiceLines.reactTo(cond(calling = false), cond(calling = true, dead = true)),
        )
    }

    @Test
    fun `DOZING IS NOT QUIET HOURS, and the pet still calls out`() {
        /*
         * The bug that reached the user, expressed where it can be caught. The
         * pet dozes after FIVE MINUTES of inactivity, all day long; quiet hours
         * are 21:00-09:00. The firmware reported the former in the field the
         * phone read as the latter, so a hungry pet beeped and never spoke.
         *
         * Condition carries only `quietHours` now — there is deliberately no
         * `asleep` on the wire, because nothing on the phone should ever act on
         * a five-minute idle timer. The absence is the fix; this test exists so
         * that adding one back is a decision rather than an accident.
         */
        val fields = PetProtocol.Condition::class.java.declaredFields.map { it.name }
        assertTrue("Condition still has quietHours", fields.contains("quietHours"))
        assertFalse(
            "Condition regained an `asleep` field — the phone must not see dozing",
            fields.any { it.equals("asleep", ignoreCase = true) },
        )
    }

    @Test
    fun `a score going DOWN is not an occasion`() {
        // Decay is not something to remark on — the face already shows it, and
        // a pet narrating its own decline every few minutes is a different and
        // much sadder product.
        assertNull(PetVoiceLines.reactTo(cond(satiety = 3), cond(satiety = 2)))
    }

    @Test
    fun `YOUR ACTION OUTRANKS THE PET'S ASKING`() {
        // If a single reading shows both — you fed it just as it started
        // calling — the thing you just did is the more interesting one, and
        // "hey, over here" in reply to being fed reads as ingratitude.
        assertEquals(
            Occasion.FED,
            PetVoiceLines.reactTo(
                cond(satiety = 1, calling = false),
                cond(satiety = 2, calling = true),
            ),
        )
    }

    @Test
    fun `THE PET DOES NOT SAY THE SAME THING TWICE RUNNING`() {
        // One "thank you" makes a doorbell rather than a creature. Rotation is
        // a counter rather than a random precisely so this can be asserted —
        // and it is checked against every persona, since the words moved into
        // design-system/personas/ and a new one could ship a repeat.
        PetPersonas.all.forEach { persona ->
            Occasion.entries.forEach { occasion ->
                val said = (0..3).map { PetVoiceLines.line(persona, occasion, it) }
                assertNotEquals(
                    "${persona.id}/$occasion says the same thing twice running",
                    PetVoiceLines.line(persona, occasion, 0),
                    PetVoiceLines.line(persona, occasion, 1),
                )
                assertTrue(said.all { it.isNotBlank() })
            }
        }
    }

    @Test
    fun `rotation survives a negative counter`() {
        // floorMod, not %. A counter that ever went negative would crash on an
        // index rather than wrap, and it would do it in the field.
        Occasion.entries.forEach {
            assertTrue(PetVoiceLines.line(PetPersonas.default, it, -1).isNotBlank())
        }
    }
}
