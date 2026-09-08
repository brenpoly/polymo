package com.digitalpet.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digitalpet.util.DiagnosticLogger
import com.digitalpet.audio.AudioPlayer
import com.digitalpet.audio.AudioRecorder
import com.digitalpet.stt.SttService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import com.digitalpet.pet.PetReadiness
import javax.inject.Inject

/**
 * The screen's view of the pet. Not the conversation itself.
 *
 * A conversation with the pet runs entirely between the pet's own microphone,
 * speaker and screen, so it must not depend on this class existing —
 * [PetConversationEngine] owns it on a process-lifetime scope, and this
 * ViewModel observes and forwards. See that class for what went wrong when the
 * pipeline lived here.
 *
 * What legitimately belongs here is anything that only means something with a UI
 * present: the phone's own microphone (a fallback for when the pet is not
 * around), model management for the debug panels, BLE pairing, the debug drawer.
 */
@HiltViewModel
class PetChatViewModel @Inject constructor(
    private val sttService: SttService,
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val logger: DiagnosticLogger,
    private val petBle: com.digitalpet.ble.PetBleRepository,
    private val announcer: com.digitalpet.conversation.PetAnnouncer,
    private val personas: com.digitalpet.conversation.PetPersonaStore,
    private val models: com.digitalpet.data.ModelRepository,
    private val petVoice: com.digitalpet.audio.PetVoiceRepository,
    private val petSpeech: com.digitalpet.audio.PetSpeechRepository,
    private val conversation: com.digitalpet.conversation.PetConversationEngine
) : ViewModel() {

    // --- the conversation, owned elsewhere and merely surfaced here ---
    val messages = conversation.messages
    val currentStreamingResponse = conversation.currentStreamingResponse
    val isGenerating = conversation.isGenerating

    /*
     * NO NOTIFICATION PASSTHROUGHS HERE ANY MORE, and their absence is the fix.
     *
     * `showNotificationCounts`, `summarizeNotifications` and the
     * `pendingNotifications` count they were gated on were surfaced for a
     * suggestion chip that could never appear — the count was only ever written
     * INSIDE the two functions the chip called, so it stayed 0 and the chip
     * stayed hidden. Both features were complete and neither was reachable.
     *
     * They are reached by ASKING now, in words, from any of the three inputs
     * that converge on sendMessage. Leaving thin wrappers here would leave a
     * second, unused way in — which is how this got lost the first time.
     */
    fun sendMessage(text: String) = conversation.sendMessage(text)

    /** Throw the transcript away. See PetConversationEngine.clearConversation. */
    fun clearConversation() = conversation.clearConversation()

    // --- physical pet (BLE). Pairing is user-driven; see PetDevicePanel. ---
    val petConnection = petBle.state

    /** Read live, never cached: the user leaves the app to switch it on. */
    fun isBluetoothOn(): Boolean = petBle.isBluetoothOn()

    val petDiscovered = petBle.discovered
    val petPairedAddress = petBle.pairedAddress
    val petInfo = petBle.info
    val petStatus = petBle.status

    fun petStartScan() = petBle.startScan()
    fun petStopScan() = petBle.stopScan()
    fun petConnect(address: String) = petBle.connect(address)
    /** The one door out of "switched off" — see PetBleRepository.reconnectByUser. */
    fun petReconnect() = petBle.reconnectByUser()

    /** True while the user has switched the pet off (DESIGN.md §5.3/§5.4). */
    val petSwitchedOff = petBle.switchedOff
    fun petDisconnect() = petBle.disconnect()
    fun petForget() = petBle.forget()

    /** How the pet says it is — scores, sick, dead, life stage (v4/v6). */
    val petCondition = petBle.condition

    /**
     * v9. Which face set the pet says it is wearing, and how to ask for another.
     *
     * Null until the pet has said — the surfaces fall back to the default for
     * drawing, but the value stays null so a settings screen can show what is
     * actually known rather than asserting a choice nobody made.
     */
    val petFaceSetId = petBle.faceSetId

    val petFaceSetSupported = petBle.faceSetSupported

    fun selectFaceSet(id: String) = petBle.selectFaceSet(id)

    /** v10. Whether the pet says something when a notification arrives. */
    /** The pet's voice — the LLM's instructions and its canned lines, together. */
    val petPersona = personas.active
    fun selectPersona(id: String) = personas.select(id)

    /** v10. The window the PET holds, or null until it has said. */
    val petQuietHours = petBle.quietHours
    fun setQuietHours(from: Int, to: Int) = petBle.setQuietHours(from, to)

    fun announcementsEnabled(): Boolean = announcer.enabled
    fun setAnnouncements(on: Boolean) { announcer.enabled = on }

    /**
     * Start a new pet. **Destructive and irreversible**, and the pet refuses it
     * unless it is actually dead — so the UI must confirm before calling this,
     * and does (see PetDevicePanel).
     */
    /**
     * Start a new pet, and take the old one's conversation with it.
     *
     * The transcript is the record of a PARTICULAR pet talking — its unprompted
     * lines are in it as well as its replies — so carrying it into a successor
     * leaves the new pet with a history that includes its predecessor's death.
     * `resetPet()` only ever sent the wire command; the chat survived every
     * reset until 2026-08-12.
     *
     * Forced, because the reset dialog has already said this happens. See
     * PetConversationEngine.clearConversation.
     */
    fun petReset() {
        petBle.resetPet()
        conversation.clearConversation(force = true)
    }


    // Which models are installed and active is owned by ModelRepository — it
    // outlives this screen. Re-exposed here so the debug panels keep a single
    // ViewModel to talk to.
    val modelState = models.llmState
    val isTtsReady = models.isTtsReady
    val ttsModelName = models.ttsModelName
    val sttModelName = models.sttModelName

    /**
     * Why the Whisper model would not load, verbatim from the native side.
     *
     * Exposed because the Ears card could not see it, and so reported a
     * REJECTED model as loaded: `loadSttModel` sets the name in its catch as
     * well as its success path, and the card's badge keyed off the name alone.
     * `PetReadiness` has always read this and called it Failed, so the two
     * surfaces disagreed — and the models screen, the one you go to in order to
     * fix it, was the one that lied.
     */
    val sttError = models.sttError

    /**
     * Whether the pet can answer at all — DESIGN.md §2.1's Axis C.
     *
     * Combined here rather than in the screen because it is a fact about the
     * app, not about a layout, and because the failure it describes is silent:
     * with any one of the three missing the pet listens, understands and says
     * nothing. Derived state, so `WhileSubscribed` would be wrong — a screen
     * that resubscribes must get the answer immediately, not after the next
     * model event.
     */
    val readiness: StateFlow<PetReadiness> = models.readiness

    // Recording can now begin at the pet's talk button, with the phone never
    // asked. The indicators have to follow whichever side started it, or the
    // app sits there looking idle while the pet is plainly listening.
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> =
        combine(_isRecording, petVoice.isListening) { phone, pet -> phone || pet }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> =
        combine(_isTranscribing, petVoice.isTranscribing) { phone, pet -> phone || pet }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // --- model management. All of it lives in ModelRepository; these are the
    // bindings the settings panels call. `onComplete` lets a panel re-read its
    // list once the file operation has actually landed. ---

    fun getAvailableModels() = models.availableLlmModels()

    fun loadModel(path: String) {
        viewModelScope.launch { models.loadLlm(path) }
    }

    fun importModelFromUri(uri: android.net.Uri, onComplete: () -> Unit) {
        viewModelScope.launch {
            models.importLlm(uri)
            onComplete()
        }
    }

    fun deleteModel(path: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            models.deleteLlm(path)
            onComplete()
        }
    }

    fun getAvailableTtsModels() = models.availableTtsVoices()

    fun loadTtsModel(modelPath: String, configPath: String) {
        viewModelScope.launch { models.loadTtsVoice(modelPath, configPath) }
    }

    /**
     * A voice is picked as `.onnx` + `.onnx.json`; **either half alone is valid
     * input** and the result says which half is still missing, so the screen
     * can name it instead of reopening the file picker in silence.
     */
    fun importTtsModel(
        uris: List<android.net.Uri>,
        onComplete: (com.digitalpet.data.VoiceImport.Result) -> Unit,
    ) {
        viewModelScope.launch { onComplete(models.importTtsVoice(uris)) }
    }

    fun deleteTtsModel(modelPath: String, configPath: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            models.deleteTtsVoice(modelPath, configPath)
            onComplete()
        }
    }

    fun getAvailableSttModels() = models.availableSttModels()

    fun loadSttModel(path: String) {
        viewModelScope.launch { models.loadSttModel(path) }
    }

    fun importSttModel(uri: android.net.Uri, onComplete: () -> Unit) {
        viewModelScope.launch {
            models.importSttModel(uri)
            onComplete()
        }
    }

    fun deleteSttModel(path: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            models.deleteSttModel(path)
            onComplete()
        }
    }

    /**
     * Start capturing speech — from the pet if one is connected and has a mic,
     * otherwise from the phone.
     *
     * Routing here rather than in the UI keeps one mic button: talking *to* the
     * pet is the point of the hardware, and the phone is the fallback for when
     * it is not around. Either way Whisper does the transcription, so the STT
     * model is required for both.
     */
    fun startRecording() {
        if (sttModelName.value == null) {
            logger.log("PetChatViewModel", "Cannot record: no STT model loaded")
            return
        }

        // Whichever speaker is talking has to stop first. On the phone the pet's
        // mic picks up Piper at around -30 dBFS; on the pet itself the coupling
        // is far tighter and the firmware would refuse to listen at all.
        audioPlayer.stop()
        petSpeech.abort()

        if (petVoice.petHasMic()) {
            // Not setting _isRecording: isListening follows the pet's own
            // report, so the indicator means "the pet is recording" rather than
            // "we asked it to".
            petVoice.start()
            return
        }

        viewModelScope.launch {
            _isRecording.value = true
            audioRecorder.startRecording()
        }
    }

    fun stopRecording() {
        if (petVoice.isListening.value) {
            // The transcript arrives on petVoice.transcripts; see init.
            petVoice.stop()
            return
        }

        if (!_isRecording.value) return
        _isRecording.value = false

        viewModelScope.launch {
            val audioData = audioRecorder.stopRecording()
            if (audioData.isNotEmpty()) {
                _isTranscribing.value = true
                try {
                    val text = sttService.transcribe(audioData).trim()
                    if (text.isNotBlank()) {
                        sendMessage(text)
                    }
                } catch (e: Exception) {
                    logger.log("PetChatViewModel", "STT transcription failed: ${e.message}")
                } finally {
                    _isTranscribing.value = false
                }
            }
        }
    }

    /**
     * Nothing is torn down here, and that is the point.
     *
     * This used to call `audioPlayer.release()`. That was safe while the reply
     * pipeline lived in this class and died with it, but PetConversationEngine
     * now speaks replies for as long as the process lives — and AudioPlayer is a
     * @Singleton it holds. Releasing it when a screen goes away would silently
     * mute the pet's voice on the phone-speaker path from then on.
     *
     * It is the same trap as the `ttsService.destroy()` that used to be here:
     * tearing down a process-lifetime singleton on UI teardown, leaving something
     * that reports itself ready over a dead resource. AudioPlayer rebuilds its
     * AudioTrack on the next chunk, so there is nothing to reclaim by hand.
     */
    override fun onCleared() {
        super.onCleared()
    }
}
