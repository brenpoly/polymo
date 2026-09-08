package com.digitalpet.llm

/**
 * JNI bridge to the llama.cpp native library.
 *
 * This singleton object loads the "digitalpet-native" shared library and exposes
 * the native C/C++ functions required for on-device LLM inference. All methods
 * are blocking and should be called from a background thread or coroutine dispatcher.
 *
 * Lifecycle:
 *  1. [initBackend] — one-time global initialisation.
 *  2. [loadModel]   — load a GGUF model file; returns a pointer handle.
 *  3. [createContext] — create an inference context for the loaded model.
 *  4. [generate]     — stream tokens via the [callback].
 *  5. [freeContext] / [freeModel] / [freeBackend] — release resources in reverse order.
 */
object LlamaNative {

    init {
        System.loadLibrary("digitalpet-native")
    }

    // ── Backend lifecycle ────────────────────────────────────────────────

    /**
     * Initialise the llama.cpp backend (ggml, metal/cuda, etc.).
     * Must be called once before any other native function.
     */
    external fun initBackend()

    /**
     * Release all backend resources. Call only during application shutdown.
     */
    external fun freeBackend()

    // ── Model lifecycle ──────────────────────────────────────────────────

    /**
     * Load a GGUF model from the given file [path].
     *
     * @param path absolute path to the `.gguf` model file.
     * @return an opaque native pointer (handle) to the loaded model,
     *         or 0 on failure.
     */
    external fun loadModel(path: String): Long

    /**
     * Retrieve a JSON-encoded string describing the model at [path].
     *
     * The returned string contains fields such as parameter count,
     * quantisation type, and architecture.
     *
     * @param path absolute path to the `.gguf` model file.
     * @return JSON string with model metadata.
     */
    external fun getModelInfo(path: String): String

    /**
     * Release a previously loaded model.
     *
     * @param modelPtr handle returned by [loadModel].
     */
    external fun freeModel(modelPtr: Long)

    // ── Context lifecycle ────────────────────────────────────────────────

    /**
     * Create an inference context for the given model.
     *
     * @param modelPtr handle returned by [loadModel].
     * @param nCtx     context window size (number of tokens).
     * @param nThreads number of threads to use for evaluation.
     * @return an opaque native pointer to the context, or 0 on failure.
     */
    external fun createContext(modelPtr: Long, nCtx: Int, nThreads: Int): Long

    /**
     * Release a previously created context.
     *
     * @param ctxPtr handle returned by [createContext].
     */
    external fun freeContext(ctxPtr: Long)

    // ── Inference ────────────────────────────────────────────────────────

    /**
     * Warm up the model by prefilling the system prompt.
     * This evaluates the system prompt and stores it in the KV cache so that
     * the first real generation request is much faster.
     *
     * @param ctxPtr       context handle.
     * @param modelPtr     model handle.
     * @param systemPrompt the system prompt to prefill.
     */
    external fun warmup(
        ctxPtr: Long,
        modelPtr: Long,
        systemPrompt: String
    )

    /**
     * Generate tokens from the model.
     *
     * Tokens are emitted one-by-one through the [callback]. The function
     * blocks until generation is complete (EOS token or [maxTokens] reached).
     *
     * @param ctxPtr       context handle.
     * @param modelPtr     model handle.
     * @param prompt       the user prompt text.
     * @param systemPrompt the system prompt prepended to the conversation.
     * @param maxTokens    maximum number of tokens to generate.
     * @param temperature  sampling temperature (higher = more creative).
     * @param topP         nucleus sampling probability threshold.
     * @param callback     invoked with each generated token string.
     */
    external fun generate(
        ctxPtr: Long,
        modelPtr: Long,
        prompt: String,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        callback: (String) -> Unit
    )

    // ── Diagnostics ──────────────────────────────────────────────────────

    /**
     * Return performance metrics for the most recent generation.
     *
     * The returned array typically contains:
     *  - `[0]` tokens per second (eval)
     *  - `[1]` prompt processing tokens per second
     *  - `[2]` total time in milliseconds
     *
     * @param ctxPtr context handle.
     * @return float array of performance counters.
     */
    external fun getPerformanceMetrics(ctxPtr: Long): FloatArray
}
