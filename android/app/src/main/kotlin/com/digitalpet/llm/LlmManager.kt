package com.digitalpet.llm

import android.content.Context
import android.os.Environment
import android.util.Log
import com.digitalpet.util.DiagnosticLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central manager for on-device LLM inference.
 *
 * [LlmManager] owns the lifecycle of a single loaded GGUF model and its
 * inference context. It exposes a reactive [modelState] flow so the UI can
 * observe loading progress and errors, and provides a streaming [generate]
 * function that bridges the JNI token callback into a Kotlin [Flow].
 *
 * Thread-safety is guaranteed by a [Mutex] that serialises all native
 * pointer mutations (load / unload / hot-swap).
 *
 * Usage:
 * ```kotlin
 * llmManager.loadModel("/sdcard/Download/model.gguf")
 * llmManager.generate("Hello!").collect { token -> print(token) }
 * ```
 */
@Singleton
class LlmManager @Inject constructor(
    private val personas: com.digitalpet.conversation.PetPersonaStore,
    @ApplicationContext private val appContext: Context,
    private val diagnosticLogger: DiagnosticLogger
) {

    companion object {
        private const val TAG = "LlmManager"
        private const val GGUF_EXTENSION = ".gguf"
    }

    // ── Model state machine ──────────────────────────────────────────────

    /**
     * Sealed interface representing the current state of the LLM model lifecycle.
     */
    sealed interface ModelState {
        /** No model is loaded. */
        data object Unloaded : ModelState

        /** A model is currently being loaded. */
        data class Loading(val progress: Float) : ModelState

        /** A model is loaded and ready for inference. */
        data class Ready(val info: ModelInfo) : ModelState

        /** An error occurred during model loading or inference. */
        data class Error(val message: String) : ModelState
    }

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Unloaded)

    /** Observable state of the currently loaded model. */
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    // ── Native pointers (guarded by [nativeMutex]) ───────────────────────

    private var modelPtr: Long = 0L
    private var contextPtr: Long = 0L
    private val nativeMutex = Mutex()

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Load a GGUF model from disk and create an inference context.
     *
     * This suspending function runs the heavy native work on [Dispatchers.Default]
     * and updates [modelState] throughout the process. If a model is already
     * loaded it will be unloaded first.
     *
     * @param path     absolute path to the `.gguf` model file.
     * @param nCtx     context window size in tokens (default 2048).
     * @param nThreads number of CPU threads for inference (default 4).
     * @throws IllegalStateException if the native model or context allocation fails.
     */
    suspend fun loadModel(
        path: String,
        nCtx: Int = 2048,
        nThreads: Int = 4
    ) = withContext(Dispatchers.Default) {
        val startTime = System.nanoTime()
        diagnosticLogger.log(TAG, "loadModel started — path=$path, nCtx=$nCtx, nThreads=$nThreads")

        nativeMutex.withLock {
            try {
                // Unload any existing model first.
                releaseNativeResourcesLocked()

                _modelState.value = ModelState.Loading(progress = 0.0f)

                // Initialise the backend (idempotent in llama.cpp).
                LlamaNative.initBackend()
                _modelState.value = ModelState.Loading(progress = 0.1f)

                // Load model.
                val mPtr = LlamaNative.loadModel(path)
                if (mPtr == 0L) {
                    val msg = "Native loadModel returned null pointer for $path"
                    diagnosticLogger.log(TAG, msg)
                    _modelState.value = ModelState.Error(msg)
                    return@withContext
                }
                modelPtr = mPtr
                _modelState.value = ModelState.Loading(progress = 0.5f)

                // Create inference context.
                val cPtr = LlamaNative.createContext(modelPtr, nCtx, nThreads)
                if (cPtr == 0L) {
                    LlamaNative.freeModel(modelPtr)
                    modelPtr = 0L
                    val msg = "Native createContext failed (nCtx=$nCtx, nThreads=$nThreads)"
                    diagnosticLogger.log(TAG, msg)
                    _modelState.value = ModelState.Error(msg)
                    return@withContext
                }
                contextPtr = cPtr
                _modelState.value = ModelState.Loading(progress = 0.9f)

                /*
                 * Warm the cache with the prompt WE ACTUALLY SEND.
                 *
                 * This primed DEFAULT_PET_SYSTEM_PROMPT while the engine had
                 * moved to the chosen persona's, so the two diverged nine
                 * tokens in — inside the first sentence. llama.cpp then threw
                 * away 135 cached tokens and re-prefilled 203 on EVERY message,
                 * which measured at 23 seconds a turn.
                 *
                 * The whole point of a warmup is that the prefix matches. One
                 * that primes a different string is worse than none: it costs
                 * the load-time prefill and buys nothing.
                 */
                try {
                    LlamaNative.warmup(
                        contextPtr,
                        modelPtr,
                        personas.active.value.systemPrompt,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Warmup failed", e)
                }
                _modelState.value = ModelState.Loading(progress = 0.95f)

                // Parse model info.
                val info = parseModelInfo(path)
                _modelState.value = ModelState.Ready(info)

                val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
                diagnosticLogger.log(TAG, "loadModel completed in ${elapsedMs}ms — ${info.fileName}")
            } catch (e: Exception) {
                Log.e(TAG, "loadModel failed", e)
                diagnosticLogger.log(TAG, "loadModel error: ${e.message}")
                releaseNativeResourcesLocked()
                _modelState.value = ModelState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Stream generated tokens from the loaded model.
     *
     * Each emitted [String] is a single token (or token fragment) produced
     * by the native inference engine. The flow completes when the model
     * emits an end-of-sequence token or [maxTokens] is reached.
     *
     * @param prompt       the user-facing prompt text.
     * @param systemPrompt the system instruction prepended to the conversation.
     * @param maxTokens    maximum tokens to generate (default 512).
     * @param temperature  sampling temperature (default 0.7).
     * @param topP         nucleus-sampling probability mass (default 0.9).
     * @return a cold [Flow] of token strings.
     * @throws IllegalStateException if no model is currently loaded.
     */
    fun generate(
        prompt: String,
        systemPrompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topP: Float = 0.9f
    ): Flow<String> = callbackFlow {
        val startTime = System.nanoTime()
        diagnosticLogger.log(TAG, "generate started — maxTokens=$maxTokens, temp=$temperature")

        val currentModelPtr: Long
        val currentCtxPtr: Long

        // Snapshot pointers under the mutex; generation itself runs without
        // holding the lock so that hot-swap can proceed concurrently.
        nativeMutex.withLock {
            check(modelPtr != 0L && contextPtr != 0L) {
                "No model loaded — call loadModel() first"
            }
            currentModelPtr = modelPtr
            currentCtxPtr = contextPtr
        }

        try {
            LlamaNative.generate(
                ctxPtr = currentCtxPtr,
                modelPtr = currentModelPtr,
                prompt = prompt,
                systemPrompt = systemPrompt,
                maxTokens = maxTokens,
                temperature = temperature,
                topP = topP
            ) { token ->
                val result = trySend(token)
                if (result.isClosed) {
                    throw kotlinx.coroutines.CancellationException("Flow closed or cancelled")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "generate error", e)
            diagnosticLogger.log(TAG, "generate error: ${e.message}")
            close(e)
        }

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        diagnosticLogger.log(TAG, "generate completed in ${elapsedMs}ms")

        // Log performance metrics if available.
        try {
            val metrics = LlamaNative.getPerformanceMetrics(currentCtxPtr)
            if (metrics.isNotEmpty()) {
                diagnosticLogger.log(
                    TAG,
                    "perf — eval=${metrics[0]} t/s, prompt=${metrics.getOrElse(1) { 0f }} t/s, " +
                        "total=${metrics.getOrElse(2) { 0f }}ms"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not retrieve performance metrics", e)
        }

        close()

        awaitClose {
            diagnosticLogger.log(TAG, "generate flow closed")
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Replace the currently loaded model with a new one without fully
     * tearing down the backend.
     *
     * This is faster than a full [unloadModel] + [loadModel] cycle when
     * switching between models of the same architecture.
     *
     * @param newPath absolute path to the new `.gguf` model file.
     */
    suspend fun hotSwapModel(newPath: String) {
        val startTime = System.nanoTime()
        diagnosticLogger.log(TAG, "hotSwapModel started — newPath=$newPath")

        nativeMutex.withLock {
            releaseNativeResourcesLocked()
        }

        // Reload with default parameters.
        loadModel(newPath)

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        diagnosticLogger.log(TAG, "hotSwapModel completed in ${elapsedMs}ms")
    }

    /**
     * Unload the current model and free all native resources.
     *
     * [modelState] transitions to [ModelState.Unloaded] upon completion.
     */
    suspend fun unloadModel() {
        val startTime = System.nanoTime()
        diagnosticLogger.log(TAG, "unloadModel started")

        nativeMutex.withLock {
            releaseNativeResourcesLocked()
            _modelState.value = ModelState.Unloaded
        }

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        diagnosticLogger.log(TAG, "unloadModel completed in ${elapsedMs}ms")
    }

    /**
     * Scan the device for available GGUF model files.
     *
     * Searches the public Downloads directory and the app's external files
     * directory for files ending in `.gguf`.
     *
     * @param context Android context used to resolve external file paths.
     * @return a list of [ModelInfo] descriptors for every discovered model.
     */
    fun getAvailableModels(context: Context): List<ModelInfo> {
        diagnosticLogger.log(TAG, "getAvailableModels — scanning device storage")

        val models = mutableListOf<ModelInfo>()
        val searchDirs = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            context.getExternalFilesDir(null)
        )

        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            dir.listFiles { file -> file.extension.equals("gguf", ignoreCase = true) }
                ?.forEach { file ->
                    try {
                        models.add(parseModelInfo(file.absolutePath))
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping unreadable model: ${file.name}", e)
                    }
                }
        }

        diagnosticLogger.log(TAG, "getAvailableModels found ${models.size} model(s)")
        return models
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * Release native context and model pointers.
     * **Must be called while holding [nativeMutex].**
     */
    private fun releaseNativeResourcesLocked() {
        if (contextPtr != 0L) {
            LlamaNative.freeContext(contextPtr)
            contextPtr = 0L
        }
        if (modelPtr != 0L) {
            LlamaNative.freeModel(modelPtr)
            modelPtr = 0L
        }
    }

    /**
     * Build a [ModelInfo] by querying native metadata and file properties.
     */
    private fun parseModelInfo(path: String): ModelInfo {
        val file = File(path)
        return try {
            val jsonStr = LlamaNative.getModelInfo(path)
            val json = JSONObject(jsonStr)
            ModelInfo(
                path = path,
                fileName = file.name,
                /*
                 * "param_count" — the key the JNI actually emits. This read
                 * "parameter_count" and optString returned its default silently,
                 * so EVERY model has reported an unknown parameter count since
                 * this was written, and nothing anywhere said so. It only became
                 * visible when a screen started printing the field.
                 */
                parameterCount = json.optString("param_count", "unknown"),
                quantization = json.optString("quantization", "unknown"),
                architecture = json.optString("architecture", "unknown"),
                fileSizeBytes = file.length()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse model info for ${file.name}, using defaults", e)
            // Attempt to infer from filename.
            val name = file.nameWithoutExtension.lowercase()
            ModelInfo(
                path = path,
                fileName = file.name,
                parameterCount = inferParameterCount(name),
                quantization = inferQuantization(name),
                architecture = "unknown",
                fileSizeBytes = file.length()
            )
        }
    }

    /**
     * Best-effort extraction of parameter count from a model filename.
     * E.g. "tinyllama-1.1b-q4_k_m" → "1.1B"
     */
    private fun inferParameterCount(name: String): String {
        val match = Regex("""(\d+\.?\d*)\s*[bB]""").find(name)
        return match?.groupValues?.get(1)?.let { "${it}B" } ?: "unknown"
    }

    /**
     * Best-effort extraction of quantisation label from a model filename.
     * E.g. "tinyllama-1.1b-q4_k_m" → "Q4_K_M"
     */
    private fun inferQuantization(name: String): String {
        val match = Regex("""(q\d+[_a-z0-9]*)""", RegexOption.IGNORE_CASE).find(name)
        return match?.value?.uppercase() ?: "unknown"
    }
}
