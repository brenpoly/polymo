package com.digitalpet.conversation

import com.digitalpet.pet.PetFaceSets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pet's voice, generated from `design-system/personas/`.
 *
 * A persona is BOTH halves — the language model's instructions and the lines the
 * model is too slow to say. Before this the two were separate hardcoded places,
 * so rewriting the system prompt left the pet chatting as somebody new and still
 * reacting in the old register. These check that they cannot come apart again.
 */
class PetPersonasTest {

    @Test
    fun `every persona has both halves`() {
        PetPersonas.all.forEach { p ->
            assertTrue("${p.id} has no system prompt", p.systemPrompt.isNotBlank())
            PetVoiceLines.Occasion.entries.forEach { occ ->
                assertTrue("${p.id} has fewer than two $occ lines", p.lines(occ).size >= 2)
            }
        }
    }

    @Test
    fun `EVERY PERSONA CARRIES EVERY RULE THE PET NEEDS`() {
        /*
         * THE BUG THIS EXISTS FOR, and it reached the user: the first version of
         * the generator built a prompt out of the personality sentences alone
         * and silently dropped every behavioural rule the old hardcoded prompt
         * had accumulated. Replies got longer, slower, and started referring to
         * conversations that had never happened.
         *
         * Each phrase below was added to fix a specific observed failure:
         *
         *   150 characters   the pet's screen fits about six short lines
         *   refer back       no history is sent, so without this the model
         *                    invents callbacks to jokes and notifications
         *   break character  it is a pet, not an assistant
         *
         * They are the PET's rules, not a character's, which is why they are
         * appended to every persona and why a persona restating one is refused.
         */
        val required = listOf(
            "150 characters",
            "Respond only to what the owner just said",
            "Do not refer back",
            "wellbeing",
            "Never break character",
        )
        PetPersonas.all.forEach { persona ->
            required.forEach {
                assertTrue(
                    "${persona.id} lost the rule: \"$it\"",
                    persona.systemPrompt.contains(it),
                )
            }
        }
    }

    @Test
    fun `A PERSONA IS FLAVOUR, and the rules come after it`() {
        // Order matters for the KV cache — the comment in SystemPromptManager
        // is explicit that the condition clause goes AFTER the persona so the
        // shared prefix stays cacheable. The pet rules are part of that prefix,
        // so they belong with it rather than in front of the character.
        PetPersonas.all.forEach {
            assertTrue(
                "${it.id} puts the rules before the character",
                it.systemPrompt.indexOf("You are") < it.systemPrompt.indexOf("tiny screen"),
            )
        }
    }

    @Test
    fun `NO PERSONA NAMES AN APP WHEN SEVERAL ARE WAITING`() {
        // The pet speaks aloud in a room. Naming every app reads your life out
        // to whoever is present, so the count is the same information minus the
        // part that is nobody else's business. The generator refuses it too.
        PetPersonas.all.forEach {
            assertFalse("${it.id} names an app in its multi-app line",
                it.announceMany.contains("{app}"))
            assertTrue("${it.id} cannot say how many", it.announceMany.contains("{count}"))
        }
    }

    @Test
    fun `NOTHING ANY PERSONA SAYS IS A QUESTION`() {
        // A question invites an answer, and answering means holding the talk
        // button — a pet that asks things it cannot hear the reply to teaches
        // you to ignore it.
        PetPersonas.all.forEach { p ->
            PetVoiceLines.Occasion.entries.forEach { occ ->
                p.lines(occ).forEach {
                    assertFalse("${p.id}/$occ asks \"$it\"", it.trimEnd().endsWith("?"))
                }
            }
        }
    }

    @Test
    fun `EVERY FACE SET SUGGESTS A VOICE THAT EXISTS`() {
        // A dangling id would fall back to the default and look exactly like the
        // pairing not working. The generator refuses it; this checks what
        // actually compiled.
        PetFaceSets.all.forEach { set ->
            set.personaId?.let {
                assertNotNull("${set.id} suggests '$it', which does not exist",
                    PetPersonas.byId(it))
            }
        }
    }

    @Test
    fun `EVERY PERSONA CAN DECLINE CARE IT DOES NOT NEED`() {
        /*
         * A full pet that says nothing looks like a broken double-tap. These
         * are the only lines the phone cannot derive — a refused feed changes
         * no score, so there is no Condition transition to notice — which
         * makes them the easiest to forget when adding a persona.
         */
        listOf(PetVoiceLines.Occasion.FULL_FED, PetVoiceLines.Occasion.FULL_PLAYED)
            .forEach { occ ->
                PetPersonas.all.forEach { p ->
                    assertTrue("${p.id} cannot decline $occ", p.lines(occ).size >= 2)
                }
            }
    }

    @Test
    fun `DECLINING IS NOT THE SAME AS ACCEPTING`() {
        // "Mmm, thank you." in reply to a feed that did NOT happen would be the
        // pet lying about its own state — and the two lists are adjacent in the
        // JSON, so a copy-paste is the likely way it happens.
        PetPersonas.all.forEach { p ->
            assertTrue(
                "${p.id} thanks you for a feed it refused",
                p.lines(PetVoiceLines.Occasion.FED)
                    .none { it in p.lines(PetVoiceLines.Occasion.FULL_FED) },
            )
            assertTrue(
                "${p.id} celebrates a play it refused",
                p.lines(PetVoiceLines.Occasion.PLAYED)
                    .none { it in p.lines(PetVoiceLines.Occasion.FULL_PLAYED) },
            )
        }
    }

    @Test
    fun `EVERY PERSONALITY IS COMPLETE`() {
        /*
         * Face and voice are ONE choice now, so a face set without a persona is
         * a personality you can select that only half-applies — the pet would
         * change how it looks and keep speaking as whoever it was.
         *
         * This is why the pairing is required rather than optional: the type
         * still allows null, because a face is a design artefact that could
         * exist before its voice does, and this is what stops one shipping.
         */
        PetFaceSets.all.forEach { set ->
            assertNotNull("${set.id} has no voice — half a personality",
                set.personaId)
            assertNotNull("${set.id} names a voice that does not exist",
                PetPersonas.byId(set.personaId))
        }
    }

    @Test
    fun `the little computer personality speaks in its own register`() {
        // The pairing that motivated all of this.
        assertEquals("bmo", PetFaceSets.byId("bmo")?.personaId)
        assertTrue(
            PetPersonas.byId("bmo")!!.lines(PetVoiceLines.Occasion.FED)
                .any { it.contains("friend", ignoreCase = true) }
        )
    }

    @Test
    fun `an unknown persona is null, not a crash`() {
        assertNull(PetPersonas.byId("no-such-voice"))
        assertNull(PetPersonas.byId(null))
        assertEquals("classic", PetPersonas.default.id)
    }
}
