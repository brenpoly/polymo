package com.digitalpet.audio

/**
 * JNI bridge to the Opus codec native library.
 *
 * This singleton object exposes the native C functions for encoding and
 * decoding audio with the Opus codec. Used to compress TTS output and
 * decompress microphone input for efficient on-device audio processing.
 *
 * All methods are blocking and must be called from a background thread.
 *
 * Lifecycle:
 *  1. [createEncoder] / [createDecoder] — allocate codec state.
 *  2. [encode] / [decode] / [decodePLC]  — process audio frames.
 *  3. [destroyEncoder] / [destroyDecoder] — release codec state.
 */
object OpusNative {

    init {
        System.loadLibrary("digitalpet-native")
    }

    /**
     * Create a new Opus encoder.
     *
     * @param sampleRate  audio sample rate in Hz (e.g. 16000, 24000, 48000).
     * @param channels    number of audio channels (1 = mono, 2 = stereo).
     * @param bitrate     target bitrate in bits per second (e.g. 48000).
     * @return an opaque native pointer to the encoder, or 0 on failure.
     */
    external fun createEncoder(sampleRate: Int, channels: Int, bitrate: Int): Long

    /**
     * Create a new Opus decoder.
     *
     * @param sampleRate audio sample rate in Hz.
     * @param channels   number of audio channels.
     * @return an opaque native pointer to the decoder, or 0 on failure.
     */
    external fun createDecoder(sampleRate: Int, channels: Int): Long

    /**
     * Encode a frame of PCM int16 audio into Opus.
     *
     * @param encoderPtr  encoder handle returned by [createEncoder].
     * @param pcmData     raw PCM audio as 16-bit signed integers.
     * @param frameSize   number of samples per channel in this frame.
     * @return the Opus-encoded byte array, or an empty array on failure.
     */
    external fun encode(encoderPtr: Long, pcmData: ShortArray, frameSize: Int): ByteArray

    /**
     * Decode an Opus packet into PCM int16 audio.
     *
     * @param decoderPtr  decoder handle returned by [createDecoder].
     * @param opusData    the Opus-encoded byte array.
     * @param frameSize   number of samples per channel to decode.
     * @return raw PCM audio as 16-bit signed integers, or an empty array on failure.
     */
    external fun decode(decoderPtr: Long, opusData: ByteArray, frameSize: Int): ShortArray

    /**
     * Perform Opus Packet Loss Concealment (PLC).
     *
     * Generates a "best guess" audio frame to cover a lost packet,
     * maintaining audio continuity.
     *
     * @param decoderPtr  decoder handle returned by [createDecoder].
     * @param frameSize   number of samples per channel to synthesise.
     * @return the concealed PCM audio frame.
     */
    external fun decodePLC(decoderPtr: Long, frameSize: Int): ShortArray

    /**
     * Release an encoder's native resources.
     *
     * @param encoderPtr encoder handle returned by [createEncoder].
     */
    external fun destroyEncoder(encoderPtr: Long)

    /**
     * Release a decoder's native resources.
     *
     * @param decoderPtr decoder handle returned by [createDecoder].
     */
    external fun destroyDecoder(decoderPtr: Long)
}
