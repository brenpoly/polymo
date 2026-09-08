package com.digitalpet.conversation

import android.content.Context
import android.content.SharedPreferences
import com.digitalpet.audio.PetSpeechRepository
import com.digitalpet.ble.PetBleRepository
import com.digitalpet.tts.TtsService
import com.digitalpet.util.DiagnosticLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The pet mentions that something arrived on your phone.
 *
 * **A creature noticing, not a status bar.** The first attempt at this drew a
 * count in the corner of the pet's screen, which worked and made the pet a small
 * smartwatch — a thing that mirrors your phone, which is the opposite of what
 * CLAUDE.md means by "the phone is compute; it is not a participant".
 *
 * Speaking needed no protocol at all: the phone already synthesises and streams
 * audio to the pet's speaker, so this is the mechanism that was already there.
 * The only wire change was the pet reporting that it is asleep, so an
 * announcement can wait for morning.
 *
 * The decision — whether, and what — is [PetAnnouncement], which is pure and
 * tested. This class is the plumbing around it: coalescing a burst, holding the
 * lock, and doing the talking.
 */
@Singleton
class PetAnnouncer @Inject constructor(
    @ApplicationContext context: Context,
    private val petBle: PetBleRepository,
    private val petSpeech: PetSpeechRepository,
    private val ttsService: TtsService,
    private val personas: PetPersonaStore,
    /** Lazy: the engine is heavy and this class is constructed early by the
     *  notification listener, which must not drag a model loader in with it. */
    private val engine: dagger.Lazy<PetConversationEngine>,
    private val logger: DiagnosticLogger,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pet_announce", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    /** Arrivals seen since the last announcement, waiting for the window to close. */
    private val pending = mutableListOf<PetAnnouncement.Item>()
    private var coalesceJob: Job? = null
    private var lastSpokenAt: Long? = null

    /**
     * Off by default, and that is deliberate.
     *
     * A pet that starts talking about your email the moment you grant
     * notification access — which is granted for the conversation engine, not
     * for this — would be a surprise, and surprises that speak out loud in a
     * room are the worst kind. It is opt-in from Settings.
     */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            logger.log(TAG, "announcements ${if (value) "on" else "off"}")
        }

    /**
     * Something arrived. Starts or extends the coalescing window.
     *
     * Called from the notification listener, which fires once per notification —
     * three emails landing together should be one sentence, not three, so
     * nothing is said until the burst stops.
     */
    fun onArrived(appLabel: String, key: String = "") {
        if (!enabled) return
        scope.launch {
            lock.withLock {
                pending.add(PetAnnouncement.Item(appLabel, key))
                coalesceJob?.cancel()
                coalesceJob = scope.launch {
                    delay(PetAnnouncement.COALESCE_MS)
                    announce()
                }
            }
        }
    }

    private suspend fun announce() {
        val items: List<PetAnnouncement.Item>
        val last: Long?
        lock.withLock {
            items = pending.toList()
            pending.clear()
            last = lastSpokenAt
        }
        val condition = petBle.condition.value
        val decision = PetAnnouncement.decide(
            items = items,
            now = System.currentTimeMillis(),
            lastSpokenAt = last,
            // The pet's own quiet hours, reported over Condition — not a second
            // set of hours invented here.
            quiet = condition?.quietHours ?: false,
            dead = condition?.dead ?: false,
            enabled = enabled,
            persona = personas.active.value,
        )
        val line = decision.line
        if (line == null) {
            logger.log(TAG, "not announcing ${items.size}: ${decision.why}")
            return
        }

        lastSpokenAt = System.currentTimeMillis()
        logger.log(TAG, "announcing ${items.size}: $line")
        say(line)
    }

    /**
     * Say one line out of the pet, and put it on the pet's screen.
     *
     * Shared by the notification announcement and by the pet's own reactions,
     * so there is one place that opens an utterance, one that closes it, and
     * one rule about falling back — which is that it does not.
     */
    suspend fun say(line: String) {
        if (!petSpeech.petHasSpeaker()) {
            logger.log(TAG, "not speaking: no pet speaker")
            return
        }
        try {
            /*
             * THREE PLACES, ONE LINE: the pet's screen, its speaker, and the
             * transcript. The chat is the record of the pet talking, and it
             * used to hold only the half that was asked for — so a line you
             * heard from the next room was unrecoverable.
             */
            engine.get().recordSpontaneous(line)
            petBle.sendText(line)
            petSpeech.begin()
            val pcm = ttsService.synthesize(line)
            if (pcm.isNotEmpty()) {
                petSpeech.speak(pcm, ttsService.getSampleRate())
            }
        } catch (e: Exception) {
            logger.log(TAG, "speech failed: ${e.message}")
        } finally {
            petSpeech.end()
        }
    }

    companion object {
        private const val TAG = "PetAnnouncer"
        private const val KEY_ENABLED = "enabled"
    }
}
