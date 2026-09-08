package com.digitalpet.stt

import android.util.Log
import com.digitalpet.util.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level speech-to-text service backed by Whisper.cpp.
 *
 * [SttService] manages the native Whisper context lifecycle and provides a
 * coroutine-friendly [transcribe] function that converts raw 16-bit PCM audio
 * into text. All native operations are serialised through a [Mutex] and
 * dispatched to [Dispatchers.Default].
 *
 * Usage:
 * ```kotlin
 * sttService.init("/path/to/whisper-tiny.bin")
 * val text = sttService.transcribe(pcmInt16Samples)
 * sttService.destroy()
 * ```
 */
@Singleton
class SttService @Inject constructor(
    private val diagnosticLogger: DiagnosticLogger
) {

    companion object {
        private const val TAG = "SttService"

        /** Conversion factor from signed 16-bit integer to [-1, 1] float. */
        private const val INT16_TO_FLOAT = 1.0f / 32768.0f
    }

    /** Opaque native pointer to the Whisper context. Zero when uninitialised. */
    private var contextPtr: Long = 0L

    /** Serialises all access to [contextPtr] and the native library. */
    private val nativeMutex = Mutex()

    /**
     * Initialise the Whisper context by loading the given model.
     *
     * This must be called once before [transcribe]. If a context is already
     * active it will be freed before loading the new model.
     *
     * @param modelPath absolute path to the Whisper GGML model file.
     * @throws IllegalStateException if the native context creation fails.
     */
    fun init(modelPath: String) {
        diagnosticLogger.log(TAG, "init — modelPath=$modelPath")

        // Free existing context synchronously (safe — called from main or setup thread).
        if (contextPtr != 0L) {
            WhisperNative.freeContext(contextPtr)
            contextPtr = 0L
        }

        val ptr = WhisperNative.initContext(modelPath)
        check(ptr != 0L) { "WhisperNative.initContext returned null pointer for $modelPath" }
        contextPtr = ptr

        diagnosticLogger.log(TAG, "init complete — contextPtr=$contextPtr")
    }

    /**
     * Transcribe raw 16-bit PCM audio samples into text.
     *
     * The input is expected to be 16 kHz mono int16 PCM (the format produced
     * by Android's [android.media.AudioRecord] with
     * [android.media.AudioFormat.ENCODING_PCM_16BIT]). Internally the samples
     * are converted to float32 before being passed to the native layer.
     *
     * @param pcmInt16 raw PCM audio as signed 16-bit integers at 16 kHz.
     * @return the transcribed text, or an empty string if transcription fails.
     */
    suspend fun transcribe(pcmInt16: ShortArray): String = withContext(Dispatchers.Default) {
        val startTime = System.nanoTime()
        diagnosticLogger.log(TAG, "transcribe — ${pcmInt16.size} samples (${pcmInt16.size / 16000.0}s)")

        nativeMutex.withLock {
            check(contextPtr != 0L) { "SttService not initialised — call init() first" }

            // Convert int16 → float32 in the range [-1, 1].
            val floatSamples = FloatArray(pcmInt16.size) { i ->
                pcmInt16[i].toFloat() * INT16_TO_FLOAT
            }

            val result = try {
                WhisperNative.transcribe(contextPtr, floatSamples, "en", 4)
            } catch (e: Exception) {
                Log.e(TAG, "Native transcribe failed", e)
                diagnosticLogger.log(TAG, "transcribe error: ${e.message}")
                ""
            }

            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
            diagnosticLogger.log(TAG, "transcribe completed in ${elapsedMs}ms — \"${result.take(80)}\"")

            result
        }
    }

    /**
     * Release the native Whisper context and free associated memory.
     *
     * Safe to call even if [init] was never called.
     */
    fun destroy() {
        diagnosticLogger.log(TAG, "destroy — contextPtr=$contextPtr")

        if (contextPtr != 0L) {
            WhisperNative.freeContext(contextPtr)
            contextPtr = 0L
        }

        diagnosticLogger.log(TAG, "destroy complete")
    }
}
