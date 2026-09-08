package com.digitalpet.audio

import android.util.Log
import com.digitalpet.util.DiagnosticLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level Opus codec wrapper for the Digital Pet audio pipeline.
 *
 * [OpusCodec] provides pre-configured encoder/decoder pairs for the two
 * audio streams used by the application:
 *
 *  - **Microphone decoder** — 16 kHz mono, for decoding incoming Opus
 *    packets from the mic recording pipeline.
 *  - **TTS encoder** — 16 kHz mono at 24 kbps, for compressing Piper TTS
 *    output before sending it to the pet's speaker.
 *
 * Native encoder/decoder handles are created lazily and released via [destroy].
 */
@Singleton
class OpusCodec @Inject constructor(
    private val diagnosticLogger: DiagnosticLogger
) {

    companion object {
        private const val TAG = "OpusCodec"

        // Microphone stream parameters
        private const val MIC_SAMPLE_RATE = 16_000
        private const val MIC_CHANNELS = 1

        // TTS stream parameters.
        //
        // These were 24 kHz / 48 kbps while nothing used them, which would have
        // been wrong twice over: Piper synthesises at 22050 Hz, so the encoder
        // would have played back ~9% fast and pitch-shifted, and the pet decodes
        // at 16 kHz. Callers resample to this rate (see Resampler) so the whole
        // system has exactly one audio format.
        private const val TTS_SAMPLE_RATE = 16_000
        private const val TTS_CHANNELS = 1
        private const val TTS_BITRATE = 24_000
    }

    /** Native encoder handle for TTS output. Zero when not allocated. */
    private var ttsEncoderPtr: Long = 0L

    /** Native decoder handle for microphone input. Zero when not allocated. */
    private var micDecoderPtr: Long = 0L

    /**
     * Create (or return the existing) Opus decoder configured for 16 kHz
     * mono microphone audio.
     *
     * @return the native decoder pointer.
     * @throws IllegalStateException if the native decoder could not be created.
     */
    fun createMicDecoder(): Long {
        if (micDecoderPtr != 0L) return micDecoderPtr

        diagnosticLogger.log(TAG, "createMicDecoder — ${MIC_SAMPLE_RATE}Hz, ${MIC_CHANNELS}ch")
        val ptr = OpusNative.createDecoder(MIC_SAMPLE_RATE, MIC_CHANNELS)
        check(ptr != 0L) { "OpusNative.createDecoder failed for mic (${MIC_SAMPLE_RATE}Hz)" }
        micDecoderPtr = ptr
        diagnosticLogger.log(TAG, "createMicDecoder complete — ptr=$ptr")
        return ptr
    }

    /**
     * Create (or return the existing) Opus encoder configured for 16 kHz
     * mono TTS output at 24 kbps — matching what the pet decodes.
     *
     * @return the native encoder pointer.
     * @throws IllegalStateException if the native encoder could not be created.
     */
    fun createTtsEncoder(): Long {
        if (ttsEncoderPtr != 0L) return ttsEncoderPtr

        diagnosticLogger.log(TAG, "createTtsEncoder — ${TTS_SAMPLE_RATE}Hz, ${TTS_CHANNELS}ch, ${TTS_BITRATE}bps")
        val ptr = OpusNative.createEncoder(TTS_SAMPLE_RATE, TTS_CHANNELS, TTS_BITRATE)
        check(ptr != 0L) { "OpusNative.createEncoder failed for TTS (${TTS_SAMPLE_RATE}Hz)" }
        ttsEncoderPtr = ptr
        diagnosticLogger.log(TAG, "createTtsEncoder complete — ptr=$ptr")
        return ptr
    }

    /**
     * Encode a frame of TTS PCM audio into Opus.
     *
     * Convenience wrapper that ensures the encoder is created before use.
     *
     * @param pcmData   raw PCM audio as 16-bit signed integers.
     * @param frameSize number of samples per channel in this frame.
     * @return the Opus-encoded byte array.
     */
    fun encodeTts(pcmData: ShortArray, frameSize: Int): ByteArray {
        val encoder = createTtsEncoder()
        return OpusNative.encode(encoder, pcmData, frameSize)
    }

    /**
     * Decode an Opus packet from the microphone stream into PCM audio.
     *
     * Convenience wrapper that ensures the decoder is created before use.
     *
     * @param opusData  the Opus-encoded byte array.
     * @param frameSize number of samples per channel to decode.
     * @return raw PCM audio as 16-bit signed integers.
     */
    fun decodeMic(opusData: ByteArray, frameSize: Int): ShortArray {
        val decoder = createMicDecoder()
        return OpusNative.decode(decoder, opusData, frameSize)
    }

    /**
     * Perform Packet Loss Concealment on the microphone decoder.
     *
     * @param frameSize number of samples per channel to synthesise.
     * @return the concealed PCM audio frame.
     */
    fun decodeMicPLC(frameSize: Int): ShortArray {
        val decoder = createMicDecoder()
        return OpusNative.decodePLC(decoder, frameSize)
    }

    /**
     * Release all native encoder and decoder resources.
     *
     * Safe to call even if no codecs have been created.
     */
    fun destroy() {
        diagnosticLogger.log(TAG, "destroy — encoder=$ttsEncoderPtr, decoder=$micDecoderPtr")

        if (ttsEncoderPtr != 0L) {
            try {
                OpusNative.destroyEncoder(ttsEncoderPtr)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to destroy TTS encoder", e)
            }
            ttsEncoderPtr = 0L
        }

        if (micDecoderPtr != 0L) {
            try {
                OpusNative.destroyDecoder(micDecoderPtr)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to destroy mic decoder", e)
            }
            micDecoderPtr = 0L
        }

        diagnosticLogger.log(TAG, "destroy complete")
    }
}
