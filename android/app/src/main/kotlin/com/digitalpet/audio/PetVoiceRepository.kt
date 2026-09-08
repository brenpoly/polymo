package com.digitalpet.audio

import com.digitalpet.ble.PetBleRepository
import com.digitalpet.ble.PetProtocol
import com.digitalpet.stt.SttService
import com.digitalpet.util.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speech captured on the *pet* rather than the phone.
 *
 * The pet is the source of truth for whether it is recording, and this follows
 * it. A session begins when the pet reports it started listening and ends when
 * it reports it stopped — whoever caused that. Pressing the pet's own talk
 * button and pressing the phone's produce exactly the same sequence here, so
 * there is one code path rather than two that must be kept in agreement.
 *
 * That matters because the pet can now start a conversation on its own. Anything
 * that only reacted to what the *phone* had asked for would miss every utterance
 * the user began by touching the pet — which is the normal way to use it.
 *
 * Transcripts are pushed to [transcripts] rather than returned, since the caller
 * no longer decides when an utterance ends.
 */
@Singleton
class PetVoiceRepository @Inject constructor(
    private val petBle: PetBleRepository,
    private val opus: OpusCodec,
    private val stt: SttService,
    private val logger: DiagnosticLogger
) {

    private companion object {
        const val TAG = "PetVoiceRepository"

        /** Samples per Opus frame — must match what the firmware encodes. */
        const val FRAME = PetProtocol.AUDIO_FRAME_SAMPLES

        /**
         * Cap on frames synthesised for one gap. A real drop is one or two
         * notifications; a huge gap means something else went wrong, and
         * inventing seconds of audio would just feed Whisper noise.
         */
        const val MAX_CONCEAL = 10
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    /**
     * Text the user spoke to the pet.
     *
     * Buffered so a transcript is not lost if the collector is momentarily busy;
     * dropping one would silently discard something the user said.
     */
    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val transcripts: SharedFlow<String> = _transcripts.asSharedFlow()

    /** Decoded audio for the session in progress. Guarded by [lock]. */
    private val pcm = ArrayList<Short>()
    private val lock = Any()

    private var received = 0
    private var concealed = 0
    private var lastSeq = FrameSequence.NO_PREVIOUS

    init {
        // The pet announces both edges, so this is the whole state machine.
        scope.launch {
            petBle.events.collect { event ->
                if (event is PetProtocol.Event.AudioState) {
                    if (event.listening) begin() else finish(event.frames)
                }
            }
        }

        // Frames are only kept while a session is open. The pet sends STARTED
        // before its first frame, so nothing is missed by gating on it.
        scope.launch {
            petBle.audioFrames.collect { frame ->
                if (_isListening.value) decodeInto(frame)
            }
        }
    }

    /** True when the pet can actually record — a v1 pet cannot. */
    fun petHasMic(): Boolean = petBle.info.value?.capabilities?.mic == true

    /**
     * Ask the pet to start recording.
     *
     * Only sends the request; the session opens when the pet confirms. The pet's
     * own talk button skips this entirely and the result is identical.
     */
    fun start() {
        if (_isListening.value) return
        petBle.setPetListening(true)
    }

    /** Ask the pet to stop. The transcript arrives on [transcripts]. */
    fun stop() {
        if (!_isListening.value) return
        petBle.setPetListening(false)
    }

    private fun begin() {
        synchronized(lock) {
            pcm.clear()
            received = 0
            concealed = 0
            lastSeq = FrameSequence.NO_PREVIOUS
        }
        _isListening.value = true
        logger.log(TAG, "pet started listening")
    }

    private fun finish(framesSentByPet: Int) {
        if (!_isListening.value) return
        _isListening.value = false

        val samples: ShortArray
        val got: Int
        val filled: Int
        synchronized(lock) {
            samples = ShortArray(pcm.size) { pcm[it] }
            got = received
            filled = concealed
            pcm.clear()
        }

        val seconds = samples.size.toFloat() / PetProtocol.AUDIO_SAMPLE_RATE
        logger.log(
            TAG,
            "utterance: ${"%.1f".format(seconds)}s, $got frames received, " +
                "$framesSentByPet sent by pet" +
                if (filled > 0) ", $filled concealed" else ""
        )

        if (samples.isEmpty()) {
            logger.log(TAG, "no audio captured")
            return
        }

        // Off the event collector: transcription takes seconds, and blocking
        // here would stall every later notification from the pet.
        scope.launch {
            _isTranscribing.value = true
            try {
                val raw = stt.transcribe(samples).trim()
                // Whisper reports silence as "[BLANK_AUDIO]", not as "" — see
                // PetText.stripNonSpeech. Emitting that drives a full reply to
                // nothing at all.
                val text = com.digitalpet.text.PetText.stripNonSpeech(raw)
                if (text.isNotEmpty()) {
                    _transcripts.emit(text)
                } else {
                    logger.log(TAG, "transcribed to nothing (raw \"$raw\")")
                }
            } catch (e: Exception) {
                logger.log(TAG, "transcription failed: ${e.message}")
            } finally {
                _isTranscribing.value = false
            }
        }
    }

    /**
     * Decode one frame, first concealing any that went missing before it.
     *
     * The pet advances its sequence number even for frames it failed to send,
     * so a gap here means real audio is absent — splicing the remaining frames
     * together would silently shorten the utterance and change what was said.
     */
    private fun decodeInto(frame: PetProtocol.AudioFrame) {
        when (val gap = FrameSequence.gap(lastSeq, frame.seq, MAX_CONCEAL)) {
            is FrameSequence.Gap.None -> Unit

            is FrameSequence.Gap.Conceal -> repeat(gap.frames) {
                val plc = opus.decodeMicPLC(FRAME)
                synchronized(lock) {
                    plc.forEach { pcm.add(it) }
                    concealed++
                }
            }

            is FrameSequence.Gap.TooLarge ->
                logger.log(TAG, "gap of ${gap.frames} frames at seq=${frame.seq}, not concealing")

            is FrameSequence.Gap.NotNewer -> {
                logger.log(TAG, "ignoring out-of-order frame seq=${frame.seq} after $lastSeq")
                return
            }
        }
        lastSeq = frame.seq

        val decoded = opus.decodeMic(frame.packet, FRAME)
        if (decoded.isEmpty()) {
            logger.log(TAG, "decode failed for seq=${frame.seq} (${frame.packet.size}B)")
            return
        }
        synchronized(lock) {
            decoded.forEach { pcm.add(it) }
            received++
        }
    }
}
