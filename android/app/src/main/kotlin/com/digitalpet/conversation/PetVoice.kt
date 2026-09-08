package com.digitalpet.conversation

import com.digitalpet.ble.PetBleRepository
import com.digitalpet.ble.PetProtocol
import com.digitalpet.util.DiagnosticLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * The pet says something when it is fed, played with, or wants attention.
 *
 * **Watches Condition; adds nothing to the wire.** Satiety only rises when the
 * pet is fed and happiness only when it is played with, so a rise IS the event.
 * [PetVoiceLines] holds the derivation and the words; this is the part that
 * needs a coroutine and a speaker.
 *
 * ### The beep stays
 *
 * The pet beeps for attention on its own, without a phone, and that does not
 * change — it is the reliable half, and a pet whose only voice depends on a
 * BLE link is a pet that goes silent when it most needs not to. The spoken line
 * follows the beep when a phone is there to say it: the beep gets you to look
 * up, the sentence tells you why.
 */
@Singleton
class PetVoice @Inject constructor(
    private val petBle: PetBleRepository,
    private val announcer: PetAnnouncer,
    private val personas: PetPersonaStore,
    private val logger: DiagnosticLogger,
) {
    private var previous: PetProtocol.Condition? = null
    private var nth = 0
    private var lastSpokenAt = 0L
    /**
     * The scope the collectors are currently running on, or null before the
     * first start. Identity, NOT a boolean — see [start].
     */
    private var startedOn: CoroutineScope? = null

    /**
     * Start watching. Idempotent per scope.
     *
     * **GUARDED ON THE SCOPE, AND A BOOLEAN HERE IS A BUG.** It used to be
     * `if (started) return`, which is correct exactly once. This class is a
     * process-lifetime @Singleton and the scope belongs to PetForegroundService,
     * which cancels it in onDestroy. So the first time that service was
     * destroyed and recreated, every collector below died with the old scope and
     * the flag then refused to launch them on the new one — the pet stopped
     * reacting to being fed or played with PERMANENTLY, for the rest of the
     * process, while the conversation engine carried on working because
     * onDestroy tells it separately.
     *
     * Measured 2026-08-30: satiety went 3 -> 4 on the pet, PetBleRepository
     * logged the Condition, and this class logged nothing at all.
     *
     * Comparing scopes rather than adding a stop() call is deliberate. A stop()
     * would match how onDestroy already tells the engine, but it only works if
     * every future teardown path remembers to call it; a scope that is not the
     * one we are running on is self-evidently a new lifetime, and cannot be
     * forgotten.
     */
    fun start(scope: CoroutineScope) {
        if (startedOn === scope) return
        val restart = startedOn != null
        startedOn = scope
        /* Says it out loud, because the failure this replaced was SILENCE: a
         * collector that never launched logged nothing, and the absence of a
         * line is the hardest thing to notice in a log. "watching" on every
         * service lifetime is what makes the next occurrence a one-line check. */
        logger.log(TAG, if (restart) "watching (restarted on a new scope)" else "watching")
        /*
         * A NEW LIFETIME STARTS WITH NO HISTORY. `previous` describes a link
         * that has since been closed, and reactTo() treats a rise in a score as
         * "you just fed me". Carrying a stale reading across a reconnect would
         * therefore thank the user for a biscuit handed over before the service
         * restarted. Null is what reactTo() documents as "just connected".
         */
        previous = null
        /*
         * The face suggests a voice. Watched here rather than in the settings
         * screen because the face can change from the PET — somebody pressing
         * its button, or another phone — and the suggestion should follow the
         * pet rather than only the tap that came from this app.
         */
        scope.launch {
            petBle.faceSetId.collect { personas.onFaceSetChanged(it) }
        }

        /*
         * CARE THE PET DID NOT NEED — the one reaction that cannot be derived.
         *
         * A refused feed changes no score, so there is no Condition transition
         * to read. Without this event a full pet is simply unresponsive, which
         * looks like a broken double-tap rather than a pet saying no thank you.
         */
        scope.launch {
            petBle.events.collect { evt ->
                if (evt !is PetProtocol.Event.CareDeclined) return@collect
                /*
                 * ONLY "full". COOLDOWN is pacing, not refusal — the pet will
                 * accept in a moment, and commenting on every too-quick tap is
                 * how a companion becomes a nag. DEAD is not a refusal either;
                 * there is nobody there to refuse, and PetVoiceLines already
                 * keeps a dead pet silent.
                 */
                if (evt.reason != PetProtocol.Declined.FULL) {
                    logger.log(TAG, "declined (${evt.reason}) - not worth saying")
                    return@collect
                }
                val t = System.currentTimeMillis()
                if (t - lastSpokenAt < MIN_GAP_MS) return@collect
                lastSpokenAt = t
                val occasion = if (evt.play) PetVoiceLines.Occasion.FULL_PLAYED
                               else PetVoiceLines.Occasion.FULL_FED
                val line = PetVoiceLines.line(personas.active.value, occasion, nth++)
                logger.log(TAG, "$occasion: $line")
                announcer.say(line)
            }
        }

        scope.launch {
            petBle.condition.filterNotNull().collect { now ->
                val before = previous
                previous = now
                val occasion = PetVoiceLines.reactTo(before, now) ?: return@collect

                /*
                 * A floor, and a short one. This is a REACTION to something you
                 * just did, so it has to feel immediate — but Condition can
                 * arrive twice in quick succession (a score and a battery
                 * reading), and the pet thanking you twice for one biscuit
                 * reads as a bug rather than as enthusiasm.
                 */
                val t = System.currentTimeMillis()
                if (t - lastSpokenAt < MIN_GAP_MS) {
                    logger.log(TAG, "$occasion suppressed - ${t - lastSpokenAt}ms since last")
                    return@collect
                }

                /*
                 * ASKING waits for morning; being FED does not.
                 *
                 * If you are feeding it at 3am you are awake and standing over
                 * it, and a reply is wanted. An unprompted "hey, over here" at
                 * the same hour is the thing quiet hours exist to prevent.
                 */
                if (occasion == PetVoiceLines.Occasion.CALLING && now.quietHours) {
                    logger.log(TAG, "CALLING held - the pet is in its quiet hours")
                    return@collect
                }

                lastSpokenAt = t
                val line = PetVoiceLines.line(personas.active.value, occasion, nth++)
                logger.log(TAG, "$occasion: $line")
                announcer.say(line)
            }
        }
    }

    companion object {
        private const val TAG = "PetVoice"
        /** Long enough to swallow a duplicate Condition, short enough to still
         *  feel like a reaction to the thing you just did. */
        const val MIN_GAP_MS = 2_500L
    }
}
