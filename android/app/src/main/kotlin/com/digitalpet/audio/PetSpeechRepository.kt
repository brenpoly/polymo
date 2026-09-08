package com.digitalpet.audio

import com.digitalpet.ble.PetBleRepository
import com.digitalpet.ble.PetProtocol
import com.digitalpet.util.DiagnosticLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends synthesised speech to the pet's speaker.
 *
 * The mirror image of [PetVoiceRepository]: take Piper's PCM, resample it to
 * the one rate this system uses, Opus-encode it and stream the frames over
 * SpeakDat between SpeakCtl BEGIN and END.
 *
 * A singleton, and serialised by a mutex: the pet has one speaker, and two
 * sentences encoded concurrently would interleave their frames into noise.
 */
@Singleton
class PetSpeechRepository @Inject constructor(
    private val petBle: PetBleRepository,
    private val opus: OpusCodec,
    private val logger: DiagnosticLogger
) {

    private companion object {
        const val TAG = "PetSpeechRepository"
        const val FRAME = PetProtocol.AUDIO_FRAME_SAMPLES
        const val RATE = PetProtocol.AUDIO_SAMPLE_RATE

        /**
         * How far ahead of realtime the phone is allowed to run.
         *
         * BLE moves frames several times faster than the pet plays them, so
         * without this a reply overruns the pet's buffer and frames are dropped
         * — a hole in the middle of a sentence.
         *
         * Two seconds rather than one, because the constraint at the other end
         * is real too: Piper synthesises each sentence barely faster than the
         * pet plays the previous one, so a small lead leaves the pet starving at
         * every sentence boundary while it waits. The lead is the pet's cushion
         * across that gap. It still sits comfortably inside the pet's ~3 s
         * queue — 2 s of lead plus its 300 ms prebuffer is ~115 of 150 frames.
         */
        const val LEAD_MS = 2_000L
    }

    /** One utterance at a time; see the class note. */
    private val lock = Mutex()

    /** Session state. A reply is several sentences but ONE utterance. */
    private var speaking = false

    /**
     * Whether SPEAK_BEGIN has actually gone to the pet yet.
     *
     * It is sent with the first frame, not when the utterance opens — see
     * [begin]. Until then the pet knows nothing about this utterance, so there
     * is nothing to END or ABORT either.
     */
    private var beginSent = false
    private var seq = 0
    private var sentMs = 0L
    private var startedAtMs = 0L

    /** True while the pet has a speaker characteristic to write to. */
    fun petHasSpeaker(): Boolean = petBle.petHasSpeaker()

    /**
     * Open one utterance, spanning the whole reply.
     *
     * A reply is spoken sentence by sentence as the model generates it, but the
     * pet must treat it as a single utterance: opening its codec costs an
     * audible pop, so doing it per sentence would click before every one.
     */
    suspend fun begin() {
        if (!petHasSpeaker()) return
        lock.withLock {
            if (speaking) return
            speaking = true
            beginSent = false
            seq = 0
            sentMs = 0
            // Deliberately NOT started here. begin() fires when generation
            // starts, but the first sentence takes a second or two to
            // synthesise, and the pet cannot play anything until frames arrive.
            // Timing the lead from here made the phone believe it was 1 s ahead
            // while the pet was 3 s behind, which overran its buffer and dropped
            // frames out of the middle of sentences.
            startedAtMs = 0

            // SPEAK_BEGIN is NOT sent here. This runs when generation starts,
            // and the first frame cannot exist until the model has produced a
            // sentence and Piper has synthesised it — 8 s for a chat reply and
            // 14 s after a notification summary, measured. The pet abandons an
            // utterance after 3 s without frames (SPK_STARVE_TIMEOUT_MS, plus a
            // 2 s prebuffer wait), so announcing the utterance this early made
            // it give up before any audio arrived and then silently discard the
            // lot — pet_spk_push drops everything while s_playing is false.
            //
            // Sending it with the first frame instead makes BEGIN mean what the
            // pet assumes it means: audio is arriving now. It also makes the
            // starve timeout meaningful, since it then only ever measures gaps
            // between real frames.
        }
    }

    /**
     * Add one sentence of synthesised speech to the open utterance.
     *
     * @param pcm        PCM from Piper, 16-bit mono.
     * @param sampleRate Piper's rate — 22050 for the current voice, which Opus
     *   does not accept, so it is resampled here rather than assumed.
     */
    suspend fun speak(pcm: ShortArray, sampleRate: Int) {
        if (pcm.isEmpty() || !petHasSpeaker()) return

        lock.withLock {
            if (!speaking) return

            val resampled = Resampler.resample(pcm, sampleRate, RATE)

            // Pad up to a whole number of frames. Opus only encodes complete
            // frames, and dropping the remainder clipped up to 20 ms off the end
            // of every sentence — trailing silence costs nothing, a missing
            // final consonant is audible.
            val padded = if (resampled.size % FRAME == 0) {
                resampled
            } else {
                resampled.copyOf(((resampled.size / FRAME) + 1) * FRAME)
            }

            var bytes = 0
            var frames = 0
            var offset = 0

            // The pet's playback clock starts at its first frame, not at
            // BEGIN, so that is what the lead has to be measured against.
            if (startedAtMs == 0L) startedAtMs = System.currentTimeMillis()

            // Open the utterance on the pet now that there is audio to fill it.
            if (!beginSent) {
                petBle.sendSpeakCtl(PetProtocol.SPEAK_BEGIN)
                beginSent = true
            }

            while (offset + FRAME <= padded.size) {
                val chunk = padded.copyOfRange(offset, offset + FRAME)
                val packet = opus.encodeTts(chunk, FRAME)
                if (packet.isEmpty()) {
                    logger.log(TAG, "encode returned nothing at frame $seq")
                    break
                }
                petBle.sendSpeakFrame(seq, packet)

                seq = (seq + 1) and 0xFF
                bytes += packet.size
                frames++
                offset += FRAME
                sentMs += PetProtocol.AUDIO_FRAME_MS

                // Pace against the pet's playback clock. Without this the phone
                // pushes a whole reply in a fraction of the time it takes to
                // play, overruns the pet's buffer and punches holes in the
                // middle of sentences.
                val elapsed = System.currentTimeMillis() - startedAtMs
                val lead = sentMs - elapsed
                if (lead > LEAD_MS) {
                    delay(lead - LEAD_MS)
                }
            }

            logger.log(
                TAG,
                "queued $frames frames (${frames * PetProtocol.AUDIO_FRAME_MS}ms, " +
                    "${bytes}B) — resampled $sampleRate->$RATE, " +
                    "peak ${Resampler.peak(resampled)}"
            )
        }
    }

    /** No more sentences: the pet plays out what is buffered and stops. */
    suspend fun end() {
        if (!petHasSpeaker()) return
        lock.withLock {
            if (!speaking) return
            speaking = false
            // Nothing was ever opened if no frame was sent — a reply that
            // produced no audio, or one cancelled before Piper finished. END
            // would then be a stray close against whatever comes next.
            if (!beginSent) {
                logger.log(TAG, "utterance ended with no audio - nothing to close")
                return
            }
            beginSent = false
            petBle.sendSpeakCtl(PetProtocol.SPEAK_END)
            logger.log(TAG, "utterance ended after ${sentMs}ms of audio")
        }
    }

    /**
     * Stop the pet mid-utterance, for when the user interrupts.
     *
     * Deliberately not suspending and not taking the lock: the point is to cut
     * in while [speak] is mid-sentence and holding it.
     */
    fun abort() {
        if (!petHasSpeaker()) return
        speaking = false
        // Only if the pet was actually told an utterance had started; otherwise
        // this is a stray control write against a session it never opened.
        if (beginSent) {
            beginSent = false
            petBle.sendSpeakCtl(PetProtocol.SPEAK_ABORT)
        }
    }
}
