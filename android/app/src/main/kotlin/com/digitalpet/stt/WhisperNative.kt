package com.digitalpet.stt

/**
 * JNI bridge to the Whisper.cpp native library.
 *
 * This singleton object exposes the native C/C++ functions required for
 * on-device speech-to-text inference using OpenAI's Whisper model.
 * All methods are blocking and must be called from a background thread.
 *
 * Lifecycle:
 *  1. [initContext] — load a Whisper GGML model and create an inference context.
 *  2. [transcribe]  — run inference on raw PCM float samples.
 *  3. [freeContext] — release all native resources.
 */
object WhisperNative {

    init {
        System.loadLibrary("digitalpet-native")
    }

    /**
     * Load a Whisper model and create an inference context.
     *
     * @param modelPath absolute path to the Whisper GGML model file.
     * @return an opaque native pointer to the context, or 0 on failure.
     */
    external fun initContext(modelPath: String): Long

    /**
     * Run speech-to-text inference on 16 kHz mono float PCM samples.
     *
     * @param ctxPtr  context handle returned by [initContext].
     * @param samples PCM audio data as 32-bit floating-point values in the range [-1, 1].
     * @param language ISO 639-1 language code (e.g. "en"), or empty for auto-detect.
     * @param nThreads Number of CPU threads for inference.
     * @return the transcribed text, or an empty string if transcription failed.
     */
    external fun transcribe(ctxPtr: Long, samples: FloatArray, language: String, nThreads: Int): String

    /**
     * Release a previously created Whisper context and its associated model.
     *
     * @param ctxPtr context handle returned by [initContext].
     */
    external fun freeContext(ctxPtr: Long)
}
