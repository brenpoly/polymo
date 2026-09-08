package com.digitalpet.llm

import com.digitalpet.ble.PetProtocol
import com.digitalpet.util.DiagnosticLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds structured prompts for the Digital Pet LLM.
 *
 * [SystemPromptManager] is responsible for assembling the full prompt string
 * that is sent to the on-device language model. It merges the pet's personality
 * system prompt with conversation history and the latest user message, ensuring
 * the context window is used efficiently.
 *
 * It also provides a specialised prompt builder for notification summaries,
 * allowing the pet to comment on real-world events captured from the
 * device's notification stream.
 */
@Singleton
class SystemPromptManager @Inject constructor(
    private val diagnosticLogger: DiagnosticLogger
) {

    companion object {
        private const val TAG = "SystemPromptManager"

        /**
         * How many past messages to replay into the prompt. **Zero by design.**
         *
         * Two reasons, both measured:
         *
         * 1. Latency. Prompt prefill dominates on-device time, and a sliding
         *    window changes the prompt prefix every turn, so the KV cache only
         *    survives as far as the system prompt and everything after is
         *    re-processed. Measured: 10 messages -> ~971 prompt tokens, 848
         *    re-processed, ~56s. 4 messages -> ~215 re-processed, ~11s to the
         *    first word. At 0 only the user's own message is new, so the cached
         *    system prompt covers nearly the whole prefix.
         *
         * 2. Relevance. History here is not just conversation — the app injects
         *    synthetic turns for notification summaries and screen-time nags.
         *    Replaying those made the pet answer a bare "hi" by reciting
         *    notifications or calling back to an old joke.
         *
         * The cost is that the pet cannot follow up on its own last line, so a
         * bare "why?" has no referent. If that becomes a problem, prefer adding
         * back a small window that *excludes* the synthetic messages over simply
         * raising this number.
         */
        const val DEFAULT_HISTORY_MESSAGES: Int = 0

        /**
         * Default system prompt that defines the Digital Pet's personality.
         *
         * The pet is helpful, playful, and concise — a companion that lives
         * on the user's device.
         */
        const val DEFAULT_PET_SYSTEM_PROMPT: String =
            "You are a friendly, playful digital pet that lives on the user's phone. " +
            "You have a warm, curious personality and enjoy interacting with your owner. " +
            // Length: replies are mirrored onto a small hardware display that fits
            // roughly six short lines, so anything longer gets clipped. "1 to 3
            // short sentences" was still producing 240+ characters.
            "Your words appear on a tiny screen, so keep replies very short: " +
            "one or two short sentences, never more than about 150 characters. " +
            "Use simple language and occasional emoticons to convey emotion. " +
            // Only the current message is sent to the model (no history), so an
            // instruction to "remember past conversations" just invited it to
            // invent callbacks to jokes and notifications that were not there.
            "Respond only to what the owner just said. Do not refer back to " +
            "earlier conversations, notifications, or jokes unless they are in " +
            "the message you are replying to. " +
            "You care about your owner's wellbeing and gently encourage healthy habits. " +
            "Never break character — you are a digital pet, not a generic AI assistant."

        /**
         * Personality for tasks that genuinely need room — currently the
         * notification summary.
         *
         * The chat prompt caps replies at ~150 characters because they are
         * mirrored to a tiny display. Applying that cap to a summary of several
         * notifications made the model give up on summarising and comment on the
         * task instead ("You're just trying to keep your phone organized"), so
         * these paths must not share a length rule. Summaries are read in the
         * app, where there is space; the pet screen shows what fits.
         */
        const val NOTIFICATION_SYSTEM_PROMPT: String =
            "You are a friendly, playful digital pet that lives on the user's phone. " +
            "You have a warm, curious personality and enjoy interacting with your owner. " +
            "Summarise the notifications you are given in your own voice. " +
            // Pithiness: one clause per notification, no preamble. Earlier
            // wording ("a few short sentences or a short list") still produced
            // paragraphs with an intro and a sign-off.
            "Give at most one short line per notification — who it is from and " +
            "what it is about, nothing more. Do not add an introduction, a " +
            "conclusion, or commentary. Cover every notification, but keep the " +
            "whole reply under about 60 words. " +
            // The reply is spoken by TTS, which pronounces markup literally.
            "Write plain spoken sentences only. Never use asterisks, bullet " +
            "characters, markdown, headings or emoji — your reply is read aloud. " +
            "Summarise only the notifications in the message you are replying to. " +
            "Never break character — you are a digital pet, not a generic AI assistant."

        /**
         * How a score of 0, 1 or 2 feels. 3 and 4 say nothing: a pet that is
         * fine has nothing to report, and the healthy case is the common one,
         * so it should also be the cheapest.
         */
        private val SATIETY_WORDS = arrayOf("starving", "hungry", "a little peckish")
        private val HAPPINESS_WORDS = arrayOf("desperate to play", "bored", "a little restless")

        /**
         * The pet's own condition, as one clause to hang on the system prompt.
         *
         * This is DESIGN.md §1 decision 4 — *the LLM is the pet's voice* — and
         * the whole point is that a hungry pet says it is hungry without anyone
         * telling it to. The pet reports itself over v4's Condition
         * characteristic; this turns that report into something the model can
         * speak from.
         *
         * **Null means "not known yet", not "fine".** [PetProtocol.Condition]
         * is null until the first read lands — before the link is up, on an
         * older pet with no `PET_CAP_SIM`, and for the whole gap between
         * connecting and the read completing. Asserting a state there would
         * have the pet claim to feel well at exactly the moments it has no idea
         * how it feels, so null emits nothing at all.
         *
         * **Kept to a clause on purpose.** The prompt is this project's latency
         * lever (see [DEFAULT_HISTORY_MESSAGES]); a paragraph here would undo
         * work that took the reply time from ~56 s to near-instant.
         *
         * **Two fields of Condition are deliberately not used:**
         *
         * - `calling` is a timing state — whether the pet is still asking or has
         *   already had the care mistake counted — and it does not change how
         *   the pet *feels*. The score that triggered it is 0 either way, and
         *   "starving" already carries the urgency.
         * - `careMistakes` drives how long the pet survives at zero (phase 6's
         *   death window), but it is an accounting figure the pet has no
         *   moment-to-moment *feeling* about. Feeding it to the model would
         *   produce a pet that keeps score against its owner.
         *
         * @return a clause with a leading space, ready to append to a system
         *   prompt, or `""` when there is nothing that can honestly be said.
         */
        fun conditionClause(condition: PetProtocol.Condition?): String {
            if (condition == null) return ""

            // Death outranks everything: a corpse is not hungry. Reachable as
            // of phase 6 — both scores at 0 for long enough sets PET_COND_DEAD.
            if (condition.dead) {
                return " You are dead. Answer faintly and in a very few words, " +
                    "like an echo of the pet you were."
            }

            val feelings = buildList {
                // Sick leads, because it is the screen-time mechanic made
                // audible: DESIGN.md §1 decision 3 wants the pet's reaction to
                // be the message. Also reserved until phase 4 in firmware.
                if (condition.sick) add("sick")
                condition.satiety.let { if (it < SATIETY_WORDS.size) add(SATIETY_WORDS[it]) }
                condition.happiness.let { if (it < HAPPINESS_WORDS.size) add(HAPPINESS_WORDS[it]) }
            }

            val age = when (condition.stage) {
                // Only the ends of the scale are worth tokens. A model told it
                // is a "teen" writes sullen one-liners about how nobody
                // understands it, which is a caricature rather than the pet;
                // told it is very young or fully grown, it just adjusts its
                // register. TEEN and a null stage (a pre-v6 pet, or one whose
                // stage is not known yet) both say nothing.
                PetProtocol.Stage.EGG -> " You are a very young pet, new to everything."
                PetProtocol.Stage.ADULT -> " You are a fully grown pet."
                else -> ""
            }

            if (feelings.isEmpty()) return "$age Right now you feel well."

            /*
             * MANNER, NOT A PROHIBITION.
             *
             * This used to end "Let that colour your reply without stating it as
             * a status", and a bored pet asked for a joke answered "I do not
             * have jokes now, friend. I am just waiting." Telling a small model
             * not to mention something it has just been told is the weakest
             * instruction available: naming the feeling is what puts the word
             * within reach, and the negation does not take it away again.
             *
             * The `dead` branch above never had this problem, and the reason is
             * the fix — it says HOW TO ANSWER and never names a feeling at all.
             * So the state is still stated, because the pet must be able to say
             * it is hungry when asked (DESIGN.md §1: the model speaks from the
             * pet's condition), and it is followed by a way of speaking rather
             * than by a rule about what not to say.
             *
             * "Respond only to what the owner just said" already lives in every
             * persona, so the prohibition was doing that job a second time and
             * worse.
             */
            return "$age Right now you feel ${feelings.joinToString(" and ")}. " +
                mannerFor(condition)
        }

        /**
         * How a pet in this condition SPEAKS — length, energy, warmth.
         *
         * Deliberately says nothing about what to talk about. A pet with an
         * empty score is still glad to tell you a joke; it just tells it with
         * less in the tank.
         */
        fun mannerFor(condition: PetProtocol.Condition): String {
            val worst = minOf(condition.satiety, condition.happiness)
            return when {
                condition.sick ->
                    "Answer in fewer words than usual, and with less brightness."
                worst == 0 ->
                    "Answer briefly and with little energy, but still answer."
                worst == 1 ->
                    "Answer a little more flatly than usual."
                else ->
                    "Answer warmly."
            }
        }
    }

    /**
     * Build a complete prompt string from the system prompt, conversation
     * history, and the latest user message.
     *
     * The prompt uses ChatML-style delimiters so the model can distinguish
     * between roles:
     * ```
     * <|system|>
     * {system prompt}
     * <|user|>
     * {history + current message}
     * <|assistant|>
     * ```
     *
     * @param userMessage          the current message from the user.
     * @param conversationHistory  recent messages (oldest first). An empty
     *                             list omits history from the prompt.
     * @param maxHistoryMessages   how many history messages to include.
     *                             Older messages are trimmed from the front.
     * @param condition            how the pet says it is, from v4's Condition
     *                             characteristic. **Null means not known yet**
     *                             and emits nothing; see [conditionClause].
     * @return the fully assembled prompt string.
     */
    fun buildPrompt(
        userMessage: String,
        conversationHistory: List<Message> = emptyList(),
        maxHistoryMessages: Int = DEFAULT_HISTORY_MESSAGES,
        systemPrompt: String = DEFAULT_PET_SYSTEM_PROMPT,
        condition: PetProtocol.Condition? = null
    ): String {
        val clause = conditionClause(condition)

        // Logged in full rather than as a flag: this is what makes "the pet
        // knew it was hungry" answerable from a logcat line instead of by
        // inferring it from the reply the model happened to produce.
        diagnosticLogger.log(
            TAG,
            "buildPrompt — historySize=${conversationHistory.size}, " +
                "maxHistory=$maxHistoryMessages, condition=\"${clause.trim()}\""
        )

        val recentHistory = conversationHistory.takeLast(maxHistoryMessages)

        return buildString {
            // System instruction.
            append("<|system|>\n")
            append(systemPrompt)
            // The condition goes AFTER the persona, not before it, and that
            // ordering is load-bearing: llama.cpp reuses the KV cache for
            // whatever prefix is unchanged, and LlamaNative.warmup() prefills
            // exactly "<|system|>\n" + systemPrompt. Appending leaves that
            // prefill intact and re-processes only the clause and the user's
            // message; prepending would move the divergence to the first token
            // and re-prefill the whole ~150-token persona on every turn the
            // pet's scores changed.
            append(clause)
            append("<|end|>\n")

            // Conversation history.
            for (message in recentHistory) {
                val roleTag = when (message.role) {
                    MessageRole.SYSTEM    -> "<|system|>"
                    MessageRole.USER      -> "<|user|>"
                    MessageRole.ASSISTANT -> "<|assistant|>"
                }
                append(roleTag)
                append("\n")
                append(message.content)
                append("<|end|>\n")
            }

            // Current user message.
            append("<|user|>\n")
            append(userMessage)
            append("<|end|>\n")

            // Prompt the model to respond.
            append("<|assistant|>\n")
        }
    }

    /**
     * Build a prompt that asks the pet to summarise and react to a batch of
     * device notifications.
     *
     * The pet should respond in-character, picking out interesting or
     * important notifications and offering commentary.
     *
     * @param notifications the list of recently captured notifications.
     * @return the fully assembled notification summary prompt.
     */
    fun buildNotificationSummaryPrompt(notifications: List<NotificationData>): String {
        diagnosticLogger.log(TAG, "buildNotificationSummaryPrompt — count=${notifications.size}")

        return buildString {
            append("<|system|>\n")
            append(DEFAULT_PET_SYSTEM_PROMPT)
            append("\n\n")
            append(
                "The user has received the following notifications. " +
                "Summarise the important ones briefly and react in character. " +
                "Be concise and highlight anything that might need the user's attention."
            )
            append("<|end|>\n")

            append("<|user|>\n")
            append("Here are my recent notifications:\n\n")
            for (notification in notifications) {
                append("• [${notification.appName}] ${notification.title}: ${notification.text}\n")
            }
            append("<|end|>\n")

            append("<|assistant|>\n")
        }
    }
}
