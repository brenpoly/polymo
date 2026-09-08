package com.digitalpet.llm

import com.digitalpet.ble.PetProtocol
import com.digitalpet.util.DiagnosticLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse

/**
 * Prompt assembly, which is where a bug is hardest to see.
 *
 * A malformed prompt does not throw — the model simply answers slightly wrong,
 * and that reads as "the model is a bit dim" rather than as a defect. This has
 * already happened twice here: a `systemPrompt` parameter that was accepted and
 * then ignored because the body appended a constant instead, and a history
 * window that silently dropped the pet's own last reply.
 */
class SystemPromptManagerTest {

    private val manager = SystemPromptManager(DiagnosticLogger())

    private fun user(text: String) = Message(MessageRole.USER, text)
    private fun assistant(text: String) = Message(MessageRole.ASSISTANT, text)

    // ---- the regression ----------------------------------------------------

    @Test
    fun `the systemPrompt argument is actually used`() {
        // The exact bug that shipped: buildPrompt took a systemPrompt parameter
        // and appended DEFAULT_PET_SYSTEM_PROMPT regardless, so notification
        // summaries were generated with the chat persona and its ~150-character
        // limit. Nothing failed; the summaries were just wrong.
        val prompt = manager.buildPrompt(
            userMessage = "hi",
            systemPrompt = "YOU ARE A TEAPOT"
        )

        assertTrue("custom system prompt was ignored", prompt.contains("YOU ARE A TEAPOT"))
        assertTrue(
            "the default persona leaked in alongside it",
            !prompt.contains(SystemPromptManager.DEFAULT_PET_SYSTEM_PROMPT)
        )
    }

    @Test
    fun `the notification prompt is a distinct persona`() {
        // Two audiences, two budgets: the pet's 6-line screen and the phone's
        // chat. A single persona broke one or the other.
        assertTrue(
            SystemPromptManager.NOTIFICATION_SYSTEM_PROMPT !=
                SystemPromptManager.DEFAULT_PET_SYSTEM_PROMPT
        )
    }

    // ---- structure ---------------------------------------------------------

    @Test
    fun `sections appear in the order the model expects`() {
        val prompt = manager.buildPrompt(
            userMessage = "MARKER_USER",
            conversationHistory = listOf(user("MARKER_HISTORY")),
            maxHistoryMessages = 4
        )

        val system = prompt.indexOf("<|system|>")
        val history = prompt.indexOf("MARKER_HISTORY")
        val current = prompt.indexOf("MARKER_USER")
        val cue = prompt.lastIndexOf("<|assistant|>")

        assertTrue("system must come first", system in 0 until history)
        assertTrue("history must precede the new message", history < current)
        assertTrue("the assistant cue must come last", current < cue)
    }

    @Test
    fun `the prompt ends by handing over to the assistant`() {
        // Without the trailing cue the model continues the user's turn instead
        // of replying to it.
        assertTrue(manager.buildPrompt("hi").trimEnd().endsWith("<|assistant|>"))
    }

    @Test
    fun `each role gets its own tag`() {
        val prompt = manager.buildPrompt(
            userMessage = "now",
            conversationHistory = listOf(user("asked"), assistant("answered")),
            maxHistoryMessages = 10
        )

        assertTrue(prompt.contains("<|user|>\nasked"))
        assertTrue(prompt.contains("<|assistant|>\nanswered"))
    }

    // ---- the history window ------------------------------------------------

    @Test
    fun `history is limited to the most recent messages`() {
        val history = (1..10).map { user("msg$it") }
        val prompt = manager.buildPrompt("now", history, maxHistoryMessages = 3)

        // Keeping the *oldest* three would feed the model the start of a
        // conversation it has already moved past.
        assertTrue(prompt.contains("msg8"))
        assertTrue(prompt.contains("msg10"))
        assertTrue("kept an old message", !prompt.contains("msg7"))
    }

    @Test
    fun `zero history includes no history at all`() {
        val prompt = manager.buildPrompt(
            userMessage = "now",
            conversationHistory = listOf(user("old"), assistant("older")),
            maxHistoryMessages = 0
        )

        assertTrue(!prompt.contains("old"))
        assertTrue(prompt.contains("now"))
    }

    @Test
    fun `asking for more history than exists is not an error`() {
        val prompt = manager.buildPrompt("now", listOf(user("only")), maxHistoryMessages = 50)
        assertTrue(prompt.contains("only"))
    }

    @Test
    fun `empty history still produces a usable prompt`() {
        val prompt = manager.buildPrompt("hello")
        assertTrue(prompt.contains("hello"))
        assertTrue(prompt.contains("<|system|>"))
    }

    // ---- the latency lever -------------------------------------------------

    @Test
    fun `the default history window is zero`() {
        // This is THE latency lever: 10 messages cost ~56 s per reply, 0 is
        // near-instant, because a shifting prompt prefix invalidates the KV
        // cache and everything after the system prompt is re-processed.
        //
        // It is also why the pet stopped reciting old notifications at a bare
        // "hi" — the history contains synthetic turns that poison short
        // prompts. Raising this should be a deliberate act, not a drive-by.
        assertEquals(0, SystemPromptManager.DEFAULT_HISTORY_MESSAGES)
    }

    // ---- the pet's condition -----------------------------------------------

    private fun condition(
        satiety: Int = 4,
        happiness: Int = 4,
        calling: Boolean = false,
        sick: Boolean = false,
        dead: Boolean = false,
        careMistakes: Int = 0,
        stage: PetProtocol.Stage? = null
    ) = PetProtocol.Condition(satiety, happiness, calling, sick, dead, careMistakes, stage)

    private fun clause(c: PetProtocol.Condition?) = SystemPromptManager.conditionClause(c)

    @Test
    fun `an unknown condition asserts nothing`() {
        // The one that matters. condition is null until the first Condition read
        // lands — before the link is up, and on any pet without PET_CAP_SIM —
        // and null means "not known yet", NOT "the pet is fine". A pet that
        // claims to feel well at the moment it has no idea how it feels is the
        // failure this test exists to prevent.
        assertEquals("", clause(null))

        val prompt = manager.buildPrompt("hi", condition = null)
        assertTrue("asserted a state it was never told", !prompt.contains("Right now you feel"))
    }

    @Test
    fun `a hungry pet is told it is hungry`() {
        assertTrue(clause(condition(satiety = 1)).contains("hungry"))
        assertTrue(clause(condition(satiety = 0)).contains("starving"))
    }

    @Test
    fun `a bored pet is told it is bored`() {
        assertTrue(clause(condition(happiness = 1)).contains("bored"))
        assertTrue(clause(condition(happiness = 0)).contains("desperate to play"))
    }

    @Test
    fun `a full and happy pet is not told it is hungry or bored`() {
        // Scores 3 and 4 are "fine" and must contribute no feeling at all —
        // otherwise every reply from a healthy pet carries a complaint.
        for (score in 3..PetProtocol.Condition.MAX_SCORE) {
            val text = clause(condition(satiety = score, happiness = score))
            assertEquals("score $score should read as well", " Right now you feel well.", text)
        }
    }

    @Test
    fun `both scores low mentions both`() {
        val text = clause(condition(satiety = 1, happiness = 1))
        assertTrue(text.contains("hungry"))
        assertTrue(text.contains("bored"))
    }

    @Test
    fun `sick leads the list`() {
        // Screen time is the mechanic the whole product is built on
        // (DESIGN.md §1 decision 3), so when the pet is sick that is the first
        // thing it feels, not a footnote after hunger.
        val text = clause(condition(satiety = 1, sick = true))
        assertTrue(text.indexOf("sick") in 0 until text.indexOf("hungry"))
    }

    @Test
    fun `death outranks every other feeling`() {
        // A corpse is not hungry. Unreachable today — the firmware reserves
        // PET_COND_DEAD until phase 6 — but Condition.parse can produce it.
        val text = clause(condition(satiety = 0, happiness = 0, sick = true, dead = true))
        assertTrue(text.contains("dead"))
        assertTrue("a dead pet complained about lunch", !text.contains("starving"))
        assertTrue(!text.contains("sick"))
    }

    @Test
    fun `the care mistake count never reaches the model`() {
        // Deliberately excluded: it is the phase 6 evolution input, an
        // accounting figure the pet has no moment-to-moment feeling about.
        // Putting it in the prompt produces a pet that keeps score against its
        // owner. 37 is chosen to be unmistakable in the output.
        //
        // Both branches, and the first version of this test had only the second:
        // a healthy pet returns before the clause is ever assembled, so it
        // exercised none of the code it claimed to cover. Mutation testing is
        // what caught that — see CLAUDE.md.
        for (c in listOf(
            condition(satiety = 0, happiness = 0, careMistakes = 37),  // the clause path
            condition(careMistakes = 37)                               // the healthy path
        )) {
            val prompt = manager.buildPrompt("hi", condition = c)
            assertTrue("care mistakes reached the model: $prompt", !prompt.contains("37"))
        }
    }

    @Test
    fun `the clause goes after the persona, not before it`() {
        // Load-bearing for latency, not for style. LlamaNative.warmup() prefills
        // "<|system|>\n" + systemPrompt, and llama.cpp reuses the KV cache for
        // whatever prefix is unchanged. Appending diverges at the clause;
        // prepending would diverge at the first token and re-prefill the whole
        // persona on every turn the pet's scores moved.
        val prompt = manager.buildPrompt(
            userMessage = "hi",
            systemPrompt = "PERSONA_MARKER",
            condition = condition(satiety = 0)
        )

        val persona = prompt.indexOf("PERSONA_MARKER")
        val feeling = prompt.indexOf("starving")
        assertTrue("clause must follow the persona", persona in 0 until feeling)
    }

    @Test
    fun `the clause is inside the system block, not the user turn`() {
        // It is an instruction about who the pet is, so it belongs to the
        // system role. In the user turn the model answers it as if the owner
        // had said it.
        val prompt = manager.buildPrompt("MARKER_USER", condition = condition(satiety = 0))

        val feeling = prompt.indexOf("starving")
        val systemEnd = prompt.indexOf("<|end|>")
        assertTrue("clause escaped the system block", feeling in 0 until systemEnd)
    }

    // ---- life stage (v6) ---------------------------------------------------

    @Test
    fun `an unknown stage says nothing about age`() {
        // A pre-v6 pet sends no stage byte. Saying "you are a very young pet"
        // on the strength of a field that was never sent is the same mistake as
        // treating a null condition as "fine".
        val text = clause(condition(stage = null))
        assertTrue("invented an age: $text", !text.contains("young"))
        assertTrue(!text.contains("grown"))
    }

    @Test
    fun `the ends of the scale are described and the middle is not`() {
        assertTrue(clause(condition(stage = PetProtocol.Stage.EGG)).contains("very young"))
        assertTrue(clause(condition(stage = PetProtocol.Stage.ADULT)).contains("fully grown"))

        // CHILD and TEEN deliberately say nothing. A model told it is a "teen"
        // writes sullen one-liners about how nobody understands it, which is a
        // caricature rather than this pet — and every stage that says nothing is
        // also a stage that costs no tokens.
        for (stage in listOf(PetProtocol.Stage.CHILD, PetProtocol.Stage.TEEN)) {
            val text = clause(condition(stage = stage))
            assertEquals("$stage should not be described", " Right now you feel well.", text)
        }
    }

    @Test
    fun `age and feelings appear together`() {
        val text = clause(condition(satiety = 0, stage = PetProtocol.Stage.EGG))
        assertTrue(text.contains("very young"))
        assertTrue(text.contains("starving"))
    }

    @Test
    fun `a dead pet is not described as young or grown`() {
        // Death short-circuits before the stage is consulted, and should: an age
        // is a fact about a life that is still going on.
        val text = clause(condition(satiety = 0, dead = true, stage = PetProtocol.Stage.EGG))
        assertTrue(text.contains("dead"))
        assertTrue("a corpse was described as a toddler", !text.contains("very young"))
    }

    @Test
    fun `the clause stays a clause`() {
        // The prompt is the latency lever. This is a budget, not a style note:
        // every character here is re-prefilled on each turn, because it sits
        // after the cached persona.
        val worst = clause(condition(
            satiety = 0, happiness = 0, sick = true, stage = PetProtocol.Stage.EGG))
        assertTrue("condition clause has grown into a paragraph: $worst", worst.length < 200)
    }

    // ---- the condition is a MANNER, not a prohibition ----------------------

    @Test
    fun `NO CONDITION CLAUSE TELLS THE MODEL WHAT NOT TO SAY`() {
        /*
         * This is the bug, and it reached the user: the clause used to end
         * "Let that colour your reply without stating it as a status", and a
         * bored pet asked for a joke replied "I do not have jokes now, friend.
         * I am just waiting."
         *
         * Naming a feeling is what puts the word within reach; a negation does
         * not take it back. The `dead` branch never had the problem because it
         * only ever said HOW to answer, which is now what all of them do.
         */
        val states = listOf(
            condition(satiety = 0, happiness = 0),
            condition(satiety = 1, happiness = 3),
            condition(satiety = 4, happiness = 4),
            condition(satiety = 2, happiness = 2, sick = true),
            condition(satiety = 0, happiness = 0, dead = true),
        )
        states.forEach { c ->
            val clause = SystemPromptManager.conditionClause(c)
            listOf("without stating", "do not mention", "don't mention", "never say")
                .forEach {
                    assertFalse("clause tells the model what NOT to say: $clause",
                        clause.contains(it, ignoreCase = true))
                }
        }
    }

    @Test
    fun `EVERY LIVING CONDITION SAYS HOW TO ANSWER`() {
        // A state with no manner is a state that only names a feeling, which is
        // the shape that leaked. "Answer" is the word every branch uses.
        listOf(
            condition(satiety = 0, happiness = 0),
            condition(satiety = 1, happiness = 4),
            condition(satiety = 2, happiness = 2, sick = true),
        ).forEach {
            assertTrue(
                "no manner in: ${SystemPromptManager.conditionClause(it)}",
                SystemPromptManager.conditionClause(it).contains("Answer"),
            )
        }
    }

    @Test
    fun `THE PET CAN STILL SAY HOW IT IS`() {
        /*
         * The manner must not have removed the state. DESIGN.md §1 wants the
         * model to speak FROM the pet's condition — asked "are you hungry?" it
         * has to know. Suppressing the feeling entirely would have fixed the
         * leak by breaking the feature.
         */
        val clause = SystemPromptManager.conditionClause(condition(satiety = 0, happiness = 4))
        assertTrue("the pet no longer knows it is hungry: $clause",
            clause.contains("feel"))
    }

    @Test
    fun `a worse score sets a quieter manner`() {
        // The ordering that makes this a scale rather than three unrelated
        // strings: emptier means fewer words.
        val ok = SystemPromptManager.mannerFor(condition(satiety = 4, happiness = 4))
        val flat = SystemPromptManager.mannerFor(condition(satiety = 1, happiness = 4))
        val empty = SystemPromptManager.mannerFor(condition(satiety = 0, happiness = 4))
        assertNotEquals(ok, flat)
        assertNotEquals(flat, empty)
        assertTrue("an empty pet is not told to keep answering", empty.contains("still answer"))
    }
}
