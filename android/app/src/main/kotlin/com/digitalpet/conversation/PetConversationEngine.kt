package com.digitalpet.conversation

import com.digitalpet.audio.AudioPlayer
import com.digitalpet.audio.PetSpeechRepository
import com.digitalpet.audio.PetVoiceRepository
import com.digitalpet.ble.PetBleRepository
import com.digitalpet.ble.PetExpression
import com.digitalpet.ble.PetProtocol
import com.digitalpet.data.AppUsageRepository
import com.digitalpet.data.MessageDao
import com.digitalpet.data.MessageEntity
import com.digitalpet.llm.LlmManager
import com.digitalpet.llm.Message
import com.digitalpet.llm.MessageRole
import com.digitalpet.llm.SystemPromptManager
import com.digitalpet.pet.PetReadiness
import com.digitalpet.text.PetText
import com.digitalpet.tts.TtsService
import com.digitalpet.util.DiagnosticLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns a conversation with the pet, for the lifetime of the process.
 *
 * WHY THIS IS NOT IN THE VIEWMODEL, which is where all of it used to live:
 *
 * The pet has its own microphone, its own talk button and its own speaker, so a
 * conversation does not involve the phone's screen at all — the user taps the
 * pet, speaks at the pet, and the pet answers. But the pipeline that turns their
 * words into a reply ran in `viewModelScope`, which dies with MainActivity.
 *
 * The failure that caused was silent rather than loud. [PetVoiceRepository] is a
 * singleton and kept capturing and transcribing perfectly; the foreground
 * service kept the BLE link up; and then the finished transcript was published
 * to `transcripts`, a SharedFlow with **replay = 0** whose only subscriber was
 * the ViewModel. With no Activity there was no subscriber, so every transcript
 * was discarded the moment it was produced. The pet listened, understood, and
 * said nothing — until the user opened the app, which recreated the ViewModel
 * and made it work again. That is exactly the state the app lands in whenever
 * the process is restarted without the UI being reopened, which START_STICKY
 * does after any crash.
 *
 * So the conversation is owned here, on a scope that ends only with the process,
 * and [com.digitalpet.service.PetForegroundService] injects it so that it exists
 * whenever the service does. The ViewModel now only observes and forwards.
 *
 * Everything with a UI dependency stays in the ViewModel: the phone's own
 * microphone, model management, BLE pairing, the debug drawer.
 */
@Singleton
class PetConversationEngine @Inject constructor(
    @ApplicationContext private val appContext: android.content.Context,
    private val llmManager: LlmManager,
    private val systemPromptManager: SystemPromptManager,
    private val personas: PetPersonaStore,
    private val ttsService: TtsService,
    private val audioPlayer: AudioPlayer,
    private val logger: DiagnosticLogger,
    private val appUsageRepository: AppUsageRepository,
    private val messageDao: MessageDao,
    private val petBle: PetBleRepository,
    private val petVoice: PetVoiceRepository,
    private val petSpeech: PetSpeechRepository,
    /**
     * Injected for its constructor, which restores the LLM, voice and STT models
     * from the last-used paths. Not called from here at all.
     *
     * A conversation cannot happen without those models, and Hilt builds
     * @Singletons lazily, so something has to ask for this one. The ViewModel
     * used to be the only thing that did — which meant that in a process with no
     * UI the engine came up perfectly, subscribed, received every audio frame,
     * and then failed each turn with "SttService not initialised". Measured
     * exactly that way after moving the pipeline here.
     */
    private val models: com.digitalpet.data.ModelRepository
) {

    private companion object {
        const val TAG = "PetConversationEngine"

        /** Chat replies are mirrored to the pet's small screen — keep them tight. */
        const val CHAT_MAX_TOKENS = 128

        /**
         * Summaries are read in the app, so they get more room than chat — but
         * not unlimited: a bigger budget is also more rope for a small model to
         * ramble or loop with.
         */
        const val SUMMARY_MAX_TOKENS = 256

        /**
         * How much of a summary the pet says out loud, in characters, rounded up
         * to a sentence boundary.
         *
         * 200 rather than [PetProtocol.TEXT_MAX]: the constraint that binds is
         * the room, not the wire. Reading every sender and subject aloud is the
         * thing `announce.many` is forbidden from doing — it may count apps but
         * not name them — and a summary asked for out loud should not get round
         * that rule by being asked for politely. Two or three sentences is
         * "you have four, mostly Gmail, one looks like a delivery"; the rest is
         * in the chat for someone actually holding the phone.
         */
        const val SUMMARY_SPOKEN_BUDGET = 200
    }

    /**
     * Process-lifetime, deliberately: a conversation must outlive any screen; see
     * the note on the class.
     *
     * The scope itself is never cancelled, because the subscriptions set up in
     * [init] are how the pet is reached at all and re-creating them has no owner.
     * Stopping is done with [setActive] instead, which cancels the work in flight
     * and refuses new turns while leaving those subscriptions in place, ready for
     * the user to switch the pet back on.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _currentStreamingResponse = MutableStateFlow("")
    val currentStreamingResponse: StateFlow<String> = _currentStreamingResponse.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    /** How many notifications the last count found — drives the Summarise action. */
    private val _pendingNotifications = MutableStateFlow(0)
    val pendingNotifications: StateFlow<Int> = _pendingNotifications.asStateFlow()

    /** What the pet is currently showing, so we never re-send the same string. */
    private var lastPetText: String = ""

    /** Expression currently on the pet's face, so moods are only written on change. */
    private var lastPetMood: PetProtocol.Mood? = null

    /**
     * The app a screen-time nudge is owed for, set by the usage alert and spent
     * when the pet reports that it has fallen ill.
     *
     * Two different things have to line up for a nudge to be right: *which* app
     * (only the phone knows) and *when* the pet is actually sick (only the pet
     * knows, and it decides). This holds the first while waiting for the second.
     * Null means nothing is owed — including after a nudge has been spoken, so
     * one long session produces one nudge however many times the pet is cured
     * and falls ill again inside it.
     */
    private var pendingOveruseApp: String? = null

    private var ttsWorkerJob: Job? = null
    private var generationJob: Job? = null

    /**
     * Whether the pet is switched on.
     *
     * Owning the conversation for the life of the process is what makes the pet
     * work with no screen — and it also means closing the app cannot stop it,
     * because there is no screen to close. Swiping the app away destroys only an
     * Activity, and dismissing the foreground notification does not stop a
     * foreground service either, so the pet kept listening and answering with no
     * visible sign of it and no way to intervene. On a device carrying a live
     * microphone that is not acceptable, whatever it does for uptime.
     *
     * So the always-on part is deliberate but must be revocable. The service owns
     * this flag: it sets it when it starts and clears it when the user stops the
     * pet. Nothing here reconnects on its own, so a cleared flag plus the
     * disconnected link means the pipeline is genuinely idle rather than merely
     * quiet.
     */
    private val _active = MutableStateFlow(true)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun setActive(on: Boolean) {
        if (_active.value == on) return
        _active.value = on
        logger.log(TAG, if (on) "pet active" else "pet stopped by user")

        if (!on) {
            // Stop what is already in flight rather than just refusing the next
            // turn: a reply mid-sentence would otherwise keep playing out of the
            // pet's speaker after the user asked it to stop.
            generationJob?.cancel()
            ttsWorkerJob?.cancel()
            audioPlayer.stop()
            petSpeech.abort()
            _isGenerating.value = false
            _currentStreamingResponse.value = ""
        }
    }

    init {
        // Words the user spoke to the pet, however the recording was started.
        // Treated exactly like typed input, so nothing downstream needs to know
        // where they came from. This is the subscriber whose absence used to make
        // the pet go quiet; it now exists for as long as the process does.
        scope.launch {
            petVoice.transcripts.collect { text ->
                // Gated HERE rather than inside sendMessage, because this is the
                // path the off switch exists for: words captured by the pet's
                // own microphone, with nobody necessarily looking at a screen.
                if (_active.value) sendMessage(text)
                else logger.log(TAG, "ignored transcript: pet stopped")
            }
        }

        // The pet lets its expression decay back to neutral on its own after a
        // few seconds and reports that. Track it, otherwise our cached value
        // still says HAPPY, and the next happy reply would be skipped as
        // "already showing that mood" and leave the face blank-looking.
        scope.launch {
            petBle.events.collect { event ->
                if (event is PetProtocol.Event.MoodChanged) {
                    lastPetMood = event.mood
                }
            }
        }

        // Persisted chat history. Also the generation input, so it has to be
        // maintained whether or not anything is on screen.
        scope.launch {
            messageDao.getAllMessages().map { entities ->
                entities.map { entity ->
                    Message(
                        role = MessageRole.valueOf(entity.role),
                        content = entity.content,
                        timestamp = entity.timestamp
                    )
                }
            }.collect { persisted -> _messages.value = persisted }
        }

        // Screen-time nudges — and note what fires them, because phase 4
        // changed it and the first version had a race.
        //
        // The usage alert says WHICH app and nothing else. It does not start a
        // reply, because it arrives on the same poll that reports the overuse to
        // the pet, and the pet's own "I am sick" comes back over BLE a moment
        // later. Measured: the alert generated a prompt 12 ms after the write
        // and 85 ms BEFORE the pet's condition notification, so the one reply
        // where sickness matters most was the one built by a pet that did not
        // yet know it was ill — its condition clause read "peckish and bored".
        //
        // So the alert only ARMS the nudge, and the pet FALLING ILL is what
        // fires it. That is also what DESIGN.md §1 decision 3 actually asks for:
        // usage event -> simulation changes -> the pet visibly reacts, with the
        // LLM optionally commenting. The comment now comes from a pet that is
        // already sick rather than one about to be.
        //
        // The alternative — assuming locally that the pet must be ill by now —
        // was rejected: it is the phone deciding the pet's state, which is the
        // decision 1 violation this whole architecture exists to avoid. The pet
        // may have reasons not to fall ill, and it owns that.
        scope.launch {
            appUsageRepository.usageAlerts.collect { packageName ->
                pendingOveruseApp = packageName
            }
        }

        scope.launch {
            // Edge, not level: the pet re-reports its condition on every change,
            // and a cure followed by falling ill again a minute later is normal
            // during one long session. Arming from the alert (which is itself
            // once per session) is what keeps that from becoming a reply a
            // minute for as long as someone keeps scrolling.
            var wasSick = false
            petBle.condition.collect { condition ->
                val isSick = condition?.sick == true
                val justFellIll = isSick && !wasSick
                wasSick = isSick

                if (!justFellIll) return@collect
                val packageName = pendingOveruseApp ?: return@collect
                if (!_active.value) return@collect
                if (_isGenerating.value) return@collect
                pendingOveruseApp = null

                val appName = appLabel(packageName)
                val thresholdMs = appUsageRepository.monitoredApps.value[packageName]
                    ?: (5 * 60 * 1000L)
                val thresholdMinutes = thresholdMs / (60 * 1000)

                // Phase 4 changed what this line IS. It used to be the whole
                // feature: the pet noticed and told you off, which is a chat app
                // with an opinion about your habits. Now the pet is genuinely
                // ill by the time this runs and its scores are draining, so the
                // prompt asks it to speak as the one suffering rather than as a
                // supervisor. The pet getting sick is the message; this is the
                // garnish.
                //
                // Nothing here tells it that it is ill. It knows — the condition
                // clause on the system prompt (phase 5) carries "sick" first,
                // and by construction it is now up to date.
                val prompt = "The user has been on $appName for " +
                    "$thresholdMinutes minutes and it is making you unwell. " +
                    "Say something short about how you feel and ask them to stop."

                val history = _messages.value   // snapshot before persisting

                persistMessage(
                    Message(
                        role = MessageRole.USER,
                        content = "[Digital Pet noticed you using $appName for too long]"
                    )
                )

                startGenerationWithPrompt(prompt, history)
            }
        }
    }

    // --- pet mirroring ------------------------------------------------------

    /**
     * Push the reply-so-far to the pet's screen.
     *
     * Called at each sentence boundary rather than once at the end: generation
     * takes many seconds on-device, so waiting for the full reply left the pet
     * blank for the whole time. Showing each finished sentence as it lands makes
     * the pet start "talking" almost immediately. Sentence-granularity keeps
     * this to a couple of BLE writes per reply — per-token would flood the link.
     */
    private fun pushToPet(text: String) {
        // The pet renders neither markdown nor emoji. Markup is dropped; emoji
        // are converted into the pet's expression before being stripped, so the
        // emotion survives as a face instead of becoming an empty box.
        val expression = PetExpression.parse(PetText.stripMarkup(text))
        val trimmed = expression.text
        if (trimmed.isBlank() || trimmed == lastPetText) return

        // Fall back to HAPPY on the first chunk so the pet leaves the "thinking"
        // face even when the reply carries no emoji.
        val mood = expression.mood
            ?: if (lastPetText.isEmpty()) PetProtocol.Mood.HAPPY else null

        if (mood != null && mood != lastPetMood) {
            lastPetMood = mood
            petBle.sendMood(mood)
        }
        lastPetText = trimmed
        petBle.sendText(trimmed)
    }

    // --- public API ---------------------------------------------------------

    /**
     * A turn the USER asked for, from the app — typed, or spoken into the
     * phone's own microphone.
     *
     * **Deliberately not gated on [_active], unlike everything the pet starts.**
     * `active` means "the pet is switched on", and it only becomes true when the
     * foreground service starts, which only happens when a pet connects. Gating
     * this on it meant that with no pet paired — or simply out of range —
     * everything typed into the app was discarded on this line, silently. You
     * could not try an LLM or a Piper voice without hardware present, which is
     * exactly when you most want to.
     *
     * The off switch is not weakened by this. It exists because a device
     * listening through its own microphone must not go on answering with no
     * visible sign of it; a person typing into an open app is neither
     * unattended nor invisible. What stays gated is everything the PET starts:
     * its transcripts, and the screen-time nudges.
     *
     * With no pet connected the reply simply plays through the phone's speaker
     * instead — `petHasSpeaker()` already decides that — which is what makes
     * auditioning voices in the app work at all.
     */
    fun sendMessage(text: String) {
        if (text.isBlank() || _isGenerating.value) return

        /*
         * REFUSE THE TURN RATHER THAN FAILING IT, and say so on the pet.
         *
         * Restoring the models is asynchronous — the LLM alone measured 11.2 s —
         * so there is a real window after every service start where the pet is
         * connected and listening and cannot answer. Without this the user talks
         * to it, llmManager.generate throws "No model loaded" or SttService
         * throws "not initialised", the exception is logged, and the pet sits
         * there looking perfectly well having ignored them.
         *
         * The window matters most in the case with NO PHONE SCREEN IN IT: the
         * service starts itself when a pet reconnects after being out of range,
         * so the readiness banner built for the app is unreachable exactly when
         * this happens. The pet has a screen; it should use it.
         */
        val readiness = models.readiness.value
        if (readiness !is PetReadiness.Ready) {
            PetReadiness.petText(readiness)?.let { pushToPet(it) }
            logger.log(TAG, "turn refused - not ready: $readiness")
            return
        }

        /*
         * "WHAT DO MY NOTIFICATIONS SAY?" IS ANSWERED HERE, not by the model.
         *
         * Every input — the pet's microphone, the phone's, and typing — arrives
         * on this line, so this is the one place that can route all three. It
         * has to be routed rather than answered in the prompt: the model is sent
         * one message with no history and no notification data, so asked about
         * notifications it invents an answer from nothing. That is what "the pet
         * can never summarise them" was.
         *
         * The count path costs no inference at all, which is most of why it is
         * kept: "any notifications?" is a glance, and paying seconds for it
         * would teach people not to ask.
         */
        when (NotificationIntent.of(text)) {
            NotificationIntent.Ask.COUNT -> {
                showNotificationCounts(userText = text.trim())
                return
            }
            NotificationIntent.Ask.SUMMARY -> {
                summarizeNotifications(userText = text.trim())
                return
            }
            NotificationIntent.Ask.NONE -> Unit
        }

        // Snapshot the history BEFORE persisting the new message. persistMessage()
        // is asynchronous (DB insert -> Room Flow -> _messages), so _messages is
        // not yet updated when generation starts. See startGenerationWithPrompt.
        val history = _messages.value

        persistMessage(Message(role = MessageRole.USER, content = text.trim()))

        startGenerationWithPrompt(text.trim(), history)
    }

    /** Resolve a package name to its user-visible label, falling back to the package. */
    private fun appLabel(packageName: String): String =
        try {
            val pm = appContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }

    /**
     * Throw the transcript away.
     *
     * **The DAO has had `clearAll()` since it was written and nothing has ever
     * called it** — the third complete-but-unreachable thing found this week,
     * after the notification summary and the two `showNotificationCounts` paths.
     * A `@Query("DELETE FROM messages")` that no caller reaches is a feature
     * with the wiring left off.
     *
     * ### What this is NOT
     *
     * It is not "clear the pet's memory", because there is nothing to clear:
     * `DEFAULT_HISTORY_MESSAGES` is 0, so no history is sent to the model at all
     * and it starts every turn knowing only the sentence in front of it. That is
     * the main latency lever, and it is why `PET_RULES` has to tell the pet not
     * to invent callbacks. The transcript is a record for the OWNER, not context
     * for the model.
     *
     * It also does not clear the pet's own screen. The pet holds its last
     * message indefinitely by design, and that message is on a different device
     * — wiping a phone record should not reach across the wire and blank the
     * thing on the shelf.
     *
     * ### Why it refuses mid-generation
     *
     * A reply already streaming would land in the emptied transcript a moment
     * later and look like the clear had failed. Refusing is honest and the wait
     * is a couple of seconds; cancelling the generation would be a second,
     * unasked-for effect hiding inside a button about history.
     *
     * `lastPetText` is reset because it is a DEDUPE GUARD, not a record:
     * `pushToPet` drops a message identical to the last one it sent, so leaving
     * it set would make the pet silently swallow the next line if it happened to
     * repeat — which after clearing is exactly when it looks broken.
     */
    fun clearConversation(force: Boolean = false) {
        /*
         * `force` is the reset path, and it is the one case where refusing
         * would be worse than clearing. Starting a new pet has already been
         * confirmed through a dialog that says the conversation goes; leaving
         * the old pet's transcript behind because a reply happened to be in
         * flight would be the button quietly not doing what it said.
         *
         * The residue is one message: a generation already streaming will
         * persist its reply after this runs. The pet has to be dead for reset
         * to be offered at all, so this needs somebody to be mid-conversation
         * with a corpse, and one stray line in a fresh transcript is a smaller
         * wrong than a reset that half-happened.
         */
        if (!force && _isGenerating.value) {
            logger.log(TAG, "clear refused - a reply is still generating")
            return
        }
        scope.launch {
            messageDao.clearAll()
            _currentStreamingResponse.value = ""
            lastPetText = ""
            logger.log(TAG, "conversation cleared")
        }
    }

    /**
     * Show how many notifications are waiting, broken down by app.
     *
     * Deliberately does **not** involve the model: the counts come straight from
     * the notification list, so this is instant and short enough to fit the
     * pet's screen. This is the glanceable answer to "anything waiting?" — the
     * prose summary is a separate, explicit action ([summarizeNotifications]),
     * because it is slow and most of the time the count is all you want.
     */
    fun showNotificationCounts(userText: String? = null) {
        val notifications =
            com.digitalpet.service.PetNotificationListener.getActiveNotificationsFiltered()
        _pendingNotifications.value = notifications.size

        // The user's ACTUAL words when they asked, so the transcript reads as
        // the conversation that happened. The canned line is for the case where
        // nothing was said — there is no such caller today, and leaving it means
        // the transcript never shows a question nobody asked.
        persistMessage(Message(
            role = MessageRole.USER, content = userText ?: "Any notifications?"
        ))

        if (notifications.isEmpty()) {
            val none = "No new notifications."
            persistMessage(Message(role = MessageRole.ASSISTANT, content = none))
            lastPetText = ""
            pushToPet(none)
            return
        }

        val counts = notifications
            .groupingBy { appLabel(it.packageName) }
            .eachCount()
            .entries
            .sortedByDescending { it.value }

        val total = notifications.size
        val breakdown = counts.joinToString(", ") { (app, n) -> "$n $app" }
        val countsLine = "$total notification${if (total == 1) "" else "s"}: $breakdown"

        persistMessage(Message(role = MessageRole.ASSISTANT, content = countsLine))
        lastPetText = ""
        pushToPet(countsLine)
    }

    /**
     * Ask the model to describe what the notifications actually say.
     *
     * Separate from [showNotificationCounts] because it costs seconds of
     * inference and produces far more text than the pet's screen can show, so it
     * is phone-only (`mirrorToPet = false`) and only run when explicitly asked.
     */
    fun summarizeNotifications(userText: String? = null) {
        if (_isGenerating.value) return

        val notifications =
            com.digitalpet.service.PetNotificationListener.getActiveNotificationsFiltered()
        _pendingNotifications.value = notifications.size

        val asked = userText ?: "What do my notifications say?"

        if (notifications.isEmpty()) {
            persistMessage(Message(role = MessageRole.USER, content = asked))
            val none = "No new notifications."
            persistMessage(Message(role = MessageRole.ASSISTANT, content = none))
            // The pet answered a question it was asked out loud, so it has to
            // SAY so. Silence here is the "listens and never answers" failure
            // this project has fixed twice; an empty inbox is still an answer.
            lastPetText = ""
            pushToPet(none)
            return
        }

        persistMessage(Message(role = MessageRole.USER, content = asked))

        val promptBuilder = StringBuilder()
        promptBuilder.append("Please provide a brief, conversational summary of the following notifications:\n\n")

        for (sbn in notifications) {
            val extras = sbn.notification.extras
            val title = extras.getString(android.app.Notification.EXTRA_TITLE) ?: ""
            val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""

            promptBuilder.append("App: ${appLabel(sbn.packageName)}\n")
            if (title.isNotBlank()) promptBuilder.append("Title: $title\n")
            if (text.isNotBlank()) promptBuilder.append("Message: $text\n")
            promptBuilder.append("---\n")
        }

        val history = _messages.value   // snapshot before persisting

        // Summaries need room to actually list the notifications — the chat
        // prompt's ~150-character cap made the model editorialise instead.
        // mirrorToPet is TRUE now, with a budget. It was false on the grounds
        // that a summary is too long for the pet's screen — which is true of the
        // whole summary and was the right call when the only way to ask was a
        // chip on the phone. You can ask out loud now, and a pet that is asked a
        // question to its face and answers on a screen you are not holding has
        // not answered. It says the opening sentences and the prose goes to the
        // chat; see spokenBudget.
        // condition = null: this persona is a summariser, told explicitly to add
        // no commentary and to cover only the notifications it is given. A pet
        // that mentions being hungry halfway down a list of messages is the
        // editorialising that prompt exists to stop — and the two personas are
        // separate KV-cache prefixes anyway, so injecting a clause that moves
        // between summaries would cost a re-prefill for nothing.
        startGenerationWithPrompt(
            promptBuilder.toString(),
            history,
            systemPrompt = SystemPromptManager.NOTIFICATION_SYSTEM_PROMPT,
            maxTokens = SUMMARY_MAX_TOKENS,
            mirrorToPet = true,
            spokenBudget = SUMMARY_SPOKEN_BUDGET,
            condition = null
        )
    }

    // --- generation ---------------------------------------------------------

    /**
     * @param conversationHistory messages preceding [promptText], snapshotted by
     *   the caller *before* it persisted the new message. Do not read
     *   [_messages] here: persistMessage() only reaches it asynchronously via
     *   Room's Flow, so at this point it usually still lacks the new message —
     *   the previous `dropLast(1)` therefore trimmed the last *assistant* turn
     *   instead, quietly dropping the pet's own reply from its context.
     */
    private fun startGenerationWithPrompt(
        promptText: String,
        conversationHistory: List<Message>,
        /*
         * The pet's CHOSEN voice, not the built-in one.
         *
         * A persona is both halves — this prompt and the canned reactions in
         * PetVoiceLines — so the model and the pet's own remarks cannot end up
         * as two different characters. The summariser below deliberately keeps
         * its own prompt: it is a different job, and it is told to add no
         * commentary at all.
         */
        systemPrompt: String = personas.active.value.systemPrompt,
        maxTokens: Int = CHAT_MAX_TOKENS,
        /** False for output that is too long for the pet's screen to be useful. */
        mirrorToPet: Boolean = true,
        /*
         * HOW MUCH OF THE REPLY THE PET SAYS AND SHOWS, in characters, rounded
         * up to a sentence boundary. The transcript always gets the whole thing.
         *
         * A summary is longer than the pet's six lines AND longer than anyone
         * wants read out in a room — the same objection that stops an
         * announcement naming more than one app. But generating a short version
         * separately would be a SECOND inference, 1.4-3.4 s after the first, to
         * say something the model has already said. So the budget is applied to
         * the stream instead: the pet speaks and shows the leading sentences,
         * generation carries on, and the full prose lands in the chat.
         */
        spokenBudget: Int = Int.MAX_VALUE,
        /**
         * How the pet says it is, so the reply comes *from* its condition —
         * DESIGN.md §1 decision 4. Read here rather than at the call sites so
         * every turn gets the freshest value; `petBle.condition` is a
         * StateFlow the pet notifies whenever a score moves.
         *
         * Null (no link yet, or a pre-v4 pet) means "not known", and
         * [SystemPromptManager.conditionClause] then says nothing rather than
         * claiming the pet is fine.
         */
        condition: PetProtocol.Condition? = petBle.condition.value
    ) {
        // NOT gated on _active: see sendMessage. Everything the pet initiates is
        // gated at its own call site instead, which is where the distinction
        // between "the pet is on" and "the user is asking" actually lives.
        _isGenerating.value = true
        _currentStreamingResponse.value = ""

        // Tell the pet the phone is working. No-op when disconnected.
        //
        // This is a STATUS, not an expression — v4 separated them. It used to be
        // sent as Mood.SLEEPY, which the pet could not distinguish from a reply
        // whose text contained a sleepy emoji, and which its own expression decay
        // would then clear while the model was still generating. Status persists
        // until changed, so the pet stays "thinking" for as long as it is true.
        if (mirrorToPet) {
            lastPetText = ""
            petBle.sendStatus(PetProtocol.Status.THINKING)
        }

        // Kept to cancel below, not discarded: its cleanup has to finish before
        // the next session opens. See the join in the worker.
        val previousTts = ttsWorkerJob
        audioPlayer.stop()
        petSpeech.abort()

        val ttsChannel = Channel<String>(Channel.UNLIMITED)

        if (ttsService.isReady) {
            ttsWorkerJob = scope.launch {
                // Wait for the previous reply's worker to finish unwinding.
                // Cancelling it only *requests* a stop; its finally block —
                // which closes the pet's utterance — runs afterwards and on
                // another thread. Without this join that stale end() lands
                // just after the new begin() and closes the session we are
                // about to fill, so the pet receives BEGIN, END, and no audio,
                // and the reply is silently never spoken.
                previousTts?.cancelAndJoin()

                try {
                    // One utterance for the whole reply, not one per sentence:
                    // the pet's codec pops when it opens, so per-sentence
                    // sessions would click before every sentence.
                    petSpeech.begin()
                    for (sentence in ttsChannel) {
                        // Piper voices "*"/"#" literally and stumbles on emoji,
                        // so strip both before synthesis.
                        val speakable = PetExpression.parse(PetText.stripMarkup(sentence)).text
                        if (speakable.isNotBlank()) {
                            val pcm = ttsService.synthesize(speakable)
                            if (pcm.isNotEmpty()) {
                                // The voice comes out of the pet when there is
                                // one, and the phone otherwise. Not both: they
                                // would be a few tens of milliseconds apart and
                                // sound like an echo.
                                if (petSpeech.petHasSpeaker()) {
                                    petSpeech.speak(pcm, ttsService.getSampleRate())
                                } else {
                                    audioPlayer.playAudioChunk(pcm, ttsService.getSampleRate())
                                }
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Normal cancellation
                } catch (e: Exception) {
                    logger.log(TAG, "TTS playback error: ${e.message}")
                } finally {
                    // Closes the utterance so the pet plays out its buffer.
                    // withContext(NonCancellable) because the common way out of
                    // this loop is cancellation, and the pet would otherwise be
                    // left holding an open session it never finishes.
                    withContext(NonCancellable) {
                        petSpeech.end()
                    }
                }
            }
        }

        generationJob = scope.launch {
            try {
                val prompt = systemPromptManager.buildPrompt(
                    userMessage = promptText,
                    conversationHistory = conversationHistory,
                    systemPrompt = systemPrompt,
                    condition = condition
                )

                val responseBuilder = StringBuilder()
                // How much of responseBuilder has already gone to the voice.
                // Deliberately an index into that builder rather than a second
                // buffer: responseBuilder is the one truncated when a turn
                // marker appears, and a separate buffer silently kept the part
                // that was trimmed. "<|user|>" arrives as several tokens, so
                // "<|user" sat in it unmatched and was spoken after the reply —
                // the pet appearing to say "user" at the end of every sentence.
                var dispatched = 0
                val antiPrompts = PetText.CHAT_MARKERS

                // Backstop against degenerate repetition. The sampler now applies
                // a repeat penalty, which is the real fix, but a small model can
                // still get stuck; without this the user just watches an endless
                // message grow. Two identical consecutive sentences = stop.
                var previousSentence = ""
                var degenerate = false
                // What the pet has been given so far. Not the same as the reply
                // once a spokenBudget applies, and it is what the final push
                // uses — otherwise the last line would hand over the whole
                // summary the streaming had deliberately been holding back.
                var voiced = ""

                llmManager.generate(
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens
                ).takeWhile { token ->
                    responseBuilder.append(token)

                    val currentResp = responseBuilder.toString()
                    var hitAntiPrompt = false

                    for (ap in antiPrompts) {
                        val idx = currentResp.indexOf(ap, ignoreCase = true)
                        if (idx >= 0) {
                            responseBuilder.setLength(idx)
                            hitAntiPrompt = true
                            break
                        }
                    }

                    if (!hitAntiPrompt) {
                        val pending = responseBuilder.substring(
                            dispatched.coerceAtMost(responseBuilder.length)
                        )

                        val match = Regex("([.?!]+(?:\\s+|\\n+)|\\n+)").find(pending)
                        if (match != null) {
                            val boundaryIndex = match.range.last + 1
                            val completeSentence = pending.substring(0, boundaryIndex).trim()
                            if (completeSentence.isNotBlank()) {
                                if (completeSentence.equals(previousSentence, ignoreCase = true)) {
                                    // Looping: drop the repeat and stop generating.
                                    responseBuilder.setLength(
                                        (responseBuilder.length - completeSentence.length)
                                            .coerceAtLeast(0)
                                    )
                                    logger.log(TAG, "stopped: repeated sentence")
                                    degenerate = true
                                } else {
                                    previousSentence = completeSentence
                                    /*
                                     * Under budget BEFORE this sentence, so the
                                     * pet always gets at least one and stops at
                                     * the first boundary past the limit. Testing
                                     * the total after would let a budget smaller
                                     * than the opening sentence silence the pet
                                     * completely, which is the one outcome worse
                                     * than saying too much.
                                     */
                                    if (voiced.length < spokenBudget) {
                                        ttsChannel.trySend(completeSentence)
                                        voiced = responseBuilder.toString().trim()
                                        // Same boundary drives the pet's screen,
                                        // so the reply appears sentence by
                                        // sentence.
                                        if (mirrorToPet) pushToPet(voiced)
                                    }
                                }
                            }
                            dispatched += boundaryIndex
                        }
                    }

                    _currentStreamingResponse.value = responseBuilder.toString()

                    !hitAntiPrompt && !degenerate
                }.collect()

                // Whatever is left after the last sentence boundary. Taken from
                // responseBuilder so anything a turn marker trimmed is gone, and
                // stripped of a partial marker in case generation stopped on the
                // token budget mid-"<|user|>".
                val tail = PetText.dropTrailingPartialMarker(
                    responseBuilder.substring(
                        dispatched.coerceAtMost(responseBuilder.length)
                    ).trim()
                )
                if (tail.isNotBlank() && voiced.length < spokenBudget) {
                    ttsChannel.trySend(tail)
                    voiced = responseBuilder.toString().trim()
                }
                ttsChannel.close()

                val reply = PetText.dropTrailingPartialMarker(responseBuilder.toString().trim())
                persistMessage(Message(role = MessageRole.ASSISTANT, content = reply))
                _currentStreamingResponse.value = ""

                // Final state. Usually a no-op because streaming already showed
                // this text; it catches a trailing fragment with no sentence
                // terminator, and replies too short to hit a boundary at all.
                // `voiced`, not `reply`: with a budget in force the pet has
                // deliberately been given less, and this line existed to catch a
                // trailing fragment — not to undo that.
                if (mirrorToPet) pushToPet(if (voiced.isBlank()) reply else voiced)

            } catch (e: Exception) {
                logger.log(TAG, "Generation error: ${e.message}")
                ttsChannel.close()
            } finally {
                _isGenerating.value = false
                // Whatever happened — finished, cancelled, threw — the phone has
                // stopped thinking, and the pet must be told or it stays wearing
                // that face indefinitely. Status persists by design, which is
                // exactly why it has to be cleared explicitly.
                if (mirrorToPet) petBle.sendStatus(PetProtocol.Status.IDLE)
            }
        }
    }

    /**
     * Record something the pet said WITHOUT being asked — a notification
     * announcement, or a reaction to being fed.
     *
     * **Everything the pet says belongs in the transcript.** These lines are
     * spoken aloud and shown on the pet's own screen, and until now they went
     * nowhere else: somebody who heard the pet from another room, or missed it
     * entirely, had no way to find out what it had said. The chat is the record
     * of the pet talking, and it was only recording half of it.
     *
     * Stored as ASSISTANT because that is what it is — the pet speaking. It
     * costs nothing at prompt time: history is capped at zero messages, so
     * these never enter the model's context and cannot become the invented
     * callbacks that PET_RULES exists to prevent.
     */
    fun recordSpontaneous(text: String) {
        if (text.isBlank()) return
        persistMessage(Message(MessageRole.ASSISTANT, text))
    }

    private fun persistMessage(message: Message) {
        scope.launch {
            messageDao.insertMessage(
                MessageEntity(
                    role = message.role.name,
                    content = message.content,
                    timestamp = message.timestamp
                )
            )
        }
    }
}
