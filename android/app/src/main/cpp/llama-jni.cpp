/**
 * llama-jni.cpp — JNI bridge for llama.cpp inference engine
 *
 * Provides model loading, context creation, chat-formatted text generation
 * with streaming token callbacks, performance metrics, and resource cleanup.
 *
 * JNI class: com.digitalpet.llm.LlamaNative
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>
#include <unordered_map>
#include <mutex>
#include <thread>
#include <algorithm>

#include "llama.h"
#include "ggml.h"

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Repetition-penalty sampling parameters. 1.1 over a 64-token window is the
// usual starting point: strong enough to stop a small model looping a phrase,
// mild enough not to distort ordinary prose.
#define PENALTY_LAST_N   64     // tokens of history considered
#define PENALTY_REPEAT   1.1f   // 1.0 would disable
#define PENALTY_FREQ     0.0f   // disabled
#define PENALTY_PRESENT  0.0f   // disabled

// =============================================================================
// Global State for KV Cache tracking
// =============================================================================
static std::mutex g_session_mutex;
static std::unordered_map<llama_context*, std::vector<llama_token>> g_session_tokens;

// =============================================================================
// JNI helpers
// =============================================================================

static std::string jstring_to_string(JNIEnv *env, jstring jstr) {
    if (!jstr) return "";
    const char *raw = env->GetStringUTFChars(jstr, nullptr);
    if (!raw) return "";
    std::string result(raw);
    env->ReleaseStringUTFChars(jstr, raw);
    return result;
}

/**
 * Checks if a string ends with an incomplete multi-byte UTF-8 character.
 * Returns true if the string is complete (safe to pass to JNI), false if it's cut off.
 */
static bool is_complete_utf8(const std::string& s) {
    if (s.empty()) return true;
    
    // Look backwards for the start of the last UTF-8 character (max 4 bytes)
    for (int i = static_cast<int>(s.length()) - 1; i >= std::max(0, static_cast<int>(s.length()) - 4); i--) {
        unsigned char c = s[i];
        if ((c & 0xC0) != 0x80) { // Found the start byte
            int expected_len = 1;
            if ((c & 0xE0) == 0xC0) expected_len = 2;
            else if ((c & 0xF0) == 0xE0) expected_len = 3;
            else if ((c & 0xF8) == 0xF0) expected_len = 4;
            
            return (s.length() - i) >= expected_len;
        }
    }
    // If we only found continuation bytes, it's invalid anyway, but we return true to let JNI crash/handle it
    return true;
}

/**
 * Decode a batch of tokens in smaller chunks to avoid saturating the GPU
 * and blocking the Android UI RenderThread.
 */
static int decode_in_chunks(llama_context *ctx, llama_batch &full_batch, int chunk_size = 16) {
    int total_tokens = full_batch.n_tokens;
    int processed = 0;

    while (processed < total_tokens) {
        int current_chunk_size = std::min(chunk_size, total_tokens - processed);
        llama_batch chunk = llama_batch_init(current_chunk_size, 0, 1);

        for (int i = 0; i < current_chunk_size; i++) {
            int src_idx = processed + i;
            chunk.token[i] = full_batch.token[src_idx];
            chunk.pos[i] = full_batch.pos[src_idx];
            chunk.n_seq_id[i] = full_batch.n_seq_id[src_idx];
            chunk.seq_id[i][0] = full_batch.seq_id[src_idx][0];
            chunk.logits[i] = full_batch.logits[src_idx];
        }
        chunk.n_tokens = current_chunk_size;

        if (llama_decode(ctx, chunk) != 0) {
            llama_batch_free(chunk);
            return 1;
        }
        llama_batch_free(chunk);
        processed += current_chunk_size;

        // Yield execution to allow Android SurfaceFlinger to submit GPU commands
        // This prevents the Vulkan LLM workload from causing UI stuttering.
        std::this_thread::yield();
    }
    return 0;
}


// =============================================================================
// JNI exports
// =============================================================================

extern "C" {

/**
 * Initialize the llama backend. Must be called once before any model operations.
 */
JNIEXPORT void JNICALL
Java_com_digitalpet_llm_LlamaNative_initBackend(JNIEnv * /*env*/, jobject /*thiz*/) {
    LOGI("Initializing llama backend");
    llama_backend_init();
    LOGI("llama backend initialized");
}

/**
 * Load a GGUF model from the given file path.
 *
 * @param path Absolute path to the .gguf model file.
 * @return Model pointer as jlong, or 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_digitalpet_llm_LlamaNative_loadModel(JNIEnv *env, jobject /*thiz*/, jstring path) {
    std::string model_path = jstring_to_string(env, path);
    if (model_path.empty()) {
        LOGE("loadModel: model path is null or empty");
        return 0;
    }

    LOGI("Loading model from: %s", model_path.c_str());

    llama_model_params params = llama_model_default_params();
    params.use_mmap = true;
    params.n_gpu_layers = 99; // Offload layers to GPU if available

    llama_model *model = llama_model_load_from_file(model_path.c_str(), params);
    if (!model) {
        LOGE("Failed to load model from: %s", model_path.c_str());
        return 0;
    }

    LOGI("Model loaded successfully: %p", (void *)model);
    return reinterpret_cast<jlong>(model);
}

/**
 * Read GGUF metadata and return a JSON string containing model information.
 *
 * @param path Absolute path to the .gguf model file.
 * @return JSON string with keys: param_count, quantization, architecture.
 */
JNIEXPORT jstring JNICALL
Java_com_digitalpet_llm_LlamaNative_getModelInfo(JNIEnv *env, jobject /*thiz*/, jstring path) {
    std::string model_path = jstring_to_string(env, path);
    if (model_path.empty()) {
        LOGE("getModelInfo: model path is null or empty");
        return env->NewStringUTF("{}");
    }

    LOGI("Reading GGUF metadata from: %s", model_path.c_str());

    struct gguf_init_params gguf_params = {
        /*.no_alloc =*/ true,
        /*.ctx      =*/ nullptr,
    };

    struct gguf_context *gguf_ctx = gguf_init_from_file(model_path.c_str(), gguf_params);
    if (!gguf_ctx) {
        LOGE("Failed to read GGUF metadata from: %s", model_path.c_str());
        return env->NewStringUTF("{}");
    }

    std::string param_count = "unknown";
    std::string quantization = "unknown";
    std::string architecture = "unknown";

    const int n_kv = gguf_get_n_kv(gguf_ctx);
    for (int i = 0; i < n_kv; i++) {
        const char *key = gguf_get_key(gguf_ctx, i);
        if (!key) continue;

        std::string key_str(key);

        if (key_str == "general.architecture") {
            if (gguf_get_kv_type(gguf_ctx, i) == GGUF_TYPE_STRING) {
                architecture = gguf_get_val_str(gguf_ctx, i);
            }
        } else if (key_str == "general.name") {
            // Optional: capture model name
        } else if (key_str == "general.quantization_version") {
            if (gguf_get_kv_type(gguf_ctx, i) == GGUF_TYPE_UINT32) {
                uint32_t val = gguf_get_val_u32(gguf_ctx, i);
                quantization = std::to_string(val);
            }
        } else if (key_str == "general.file_type") {
            if (gguf_get_kv_type(gguf_ctx, i) == GGUF_TYPE_UINT32) {
                uint32_t val = gguf_get_val_u32(gguf_ctx, i);
                quantization = std::to_string(val);
            }
        }
    }

    // Attempt to get parameter count from tensor metadata
    const int n_tensors = gguf_get_n_tensors(gguf_ctx);
    int64_t total_params = 0;
    for (int i = 0; i < n_tensors; i++) {
        const char *tensor_name = gguf_get_tensor_name(gguf_ctx, i);
        if (!tensor_name) continue;
        // Count elements via tensor info offset (approximate from file metadata)
    }
    if (total_params > 0) {
        if (total_params >= 1000000000) {
            char buf[64];
            snprintf(buf, sizeof(buf), "%.1fB", total_params / 1e9);
            param_count = buf;
        } else if (total_params >= 1000000) {
            char buf[64];
            snprintf(buf, sizeof(buf), "%.1fM", total_params / 1e6);
            param_count = buf;
        } else {
            param_count = std::to_string(total_params);
        }
    }

    gguf_free(gguf_ctx);

    // Build JSON response — manual construction to avoid dependency on a JSON library
    std::string json = "{";
    json += "\"param_count\":\"" + param_count + "\",";
    json += "\"quantization\":\"" + quantization + "\",";
    json += "\"architecture\":\"" + architecture + "\"";
    json += "}";

    LOGI("Model info: %s", json.c_str());
    return env->NewStringUTF(json.c_str());
}

/**
 * Create an inference context from a loaded model.
 *
 * @param modelPtr  Pointer to the loaded llama_model.
 * @param nCtx      Context window size in tokens.
 * @param nThreads  Number of CPU threads for inference.
 * @return Context pointer as jlong, or 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_digitalpet_llm_LlamaNative_createContext(JNIEnv * /*env*/, jobject /*thiz*/,
                                                  jlong modelPtr, jint nCtx, jint nThreads) {
    auto *model = reinterpret_cast<llama_model *>(modelPtr);
    if (!model) {
        LOGE("createContext: model pointer is null");
        return 0;
    }

    LOGI("Creating context: nCtx=%d, nThreads=%d", nCtx, nThreads);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(nCtx);
    ctx_params.n_threads = static_cast<int32_t>(nThreads);
    ctx_params.n_threads_batch = static_cast<int32_t>(nThreads);

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create llama context");
        return 0;
    }

    LOGI("Context created: %p", (void *)ctx);
    return reinterpret_cast<jlong>(ctx);
}

/**
 * Warm up the model by prefilling the system prompt.
 *
 * @param ctxPtr       Pointer to the llama_context.
 * @param modelPtr     Pointer to the llama_model.
 * @param systemPrompt System prompt text.
 */
JNIEXPORT void JNICALL
Java_com_digitalpet_llm_LlamaNative_warmup(JNIEnv *env, jobject /*thiz*/,
                                           jlong ctxPtr, jlong modelPtr,
                                           jstring systemPrompt) {
    auto *ctx = reinterpret_cast<llama_context *>(ctxPtr);
    auto *model = reinterpret_cast<llama_model *>(modelPtr);

    if (!ctx || !model) {
        LOGE("warmup: null context or model pointer");
        return;
    }

    std::string sys = jstring_to_string(env, systemPrompt);
    std::string formatted_prompt = "<|system|>\n" + sys + "<|end|>\n";

    LOGI("Warmup prompt length: %zu chars", formatted_prompt.length());

    const llama_vocab *vocab = llama_model_get_vocab(model);
    const int max_prompt_tokens = static_cast<int>(formatted_prompt.length()) + 128;
    std::vector<llama_token> tokens(max_prompt_tokens);

    int n_tokens = llama_tokenize(vocab, formatted_prompt.c_str(),
                                  static_cast<int32_t>(formatted_prompt.length()),
                                  tokens.data(), max_prompt_tokens,
                                  true,  // add_special (BOS)
                                  true); // parse_special

    if (n_tokens < 0) {
        tokens.resize(static_cast<size_t>(-n_tokens));
        n_tokens = llama_tokenize(vocab, formatted_prompt.c_str(),
                                  static_cast<int32_t>(formatted_prompt.length()),
                                  tokens.data(), static_cast<int32_t>(tokens.size()),
                                  true, true);
        if (n_tokens < 0) {
            LOGE("Warmup tokenization failed");
            return;
        }
    }
    tokens.resize(static_cast<size_t>(n_tokens));
    LOGI("Warmup tokenized prompt: %d tokens", n_tokens);

    // Write to KV Cache
    std::vector<llama_token> &session_tokens = [&]() -> std::vector<llama_token>& {
        std::lock_guard<std::mutex> lock(g_session_mutex);
        return g_session_tokens[ctx];
    }();

    // Clear any existing cache since this is a fresh warmup
    llama_memory_seq_rm(llama_get_memory(ctx), 0, -1, -1);
    session_tokens.clear();

    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    for (int i = 0; i < n_tokens; i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = false;
    }
    batch.n_tokens = n_tokens;

    if (decode_in_chunks(ctx, batch) != 0) {
        LOGE("Warmup decode failed");
    } else {
        LOGI("Warmup prefill complete for %d tokens", n_tokens);
        session_tokens = tokens;
    }

    llama_batch_free(batch);
}

/**
 * Run full text generation with streaming token callback.
 *
 * Builds a chat-formatted prompt, tokenizes, runs prefill, then autoregressively
 * samples tokens using a temp -> top_p -> dist sampler chain. Each generated
 * token string is streamed to the Kotlin callback via JNI reflection.
 *
 * @param ctxPtr       Pointer to the llama_context.
 * @param modelPtr     Pointer to the llama_model.
 * @param prompt       User prompt text.
 * @param systemPrompt System prompt text.
 * @param maxTokens    Maximum number of tokens to generate.
 * @param temperature  Sampling temperature.
 * @param topP         Top-p (nucleus) sampling threshold.
 * @param callback     Kotlin Function1<String, Unit> for streaming tokens.
 * @return The complete generated text as a jstring.
 */
JNIEXPORT jstring JNICALL
Java_com_digitalpet_llm_LlamaNative_generate(JNIEnv *env, jobject /*thiz*/,
                                             jlong ctxPtr, jlong modelPtr,
                                             jstring prompt, jstring systemPrompt,
                                             jint maxTokens, jfloat temperature, jfloat topP,
                                             jobject callback) {
    auto *ctx = reinterpret_cast<llama_context *>(ctxPtr);
    auto *model = reinterpret_cast<llama_model *>(modelPtr);

    if (!ctx || !model) {
        LOGE("generate: null context or model pointer");
        return env->NewStringUTF("");
    }

    // -------------------------------------------------------------------------
    // 1. Build chat-formatted prompt
    // -------------------------------------------------------------------------
    std::string formatted_prompt = jstring_to_string(env, prompt);

    LOGI("Formatted prompt length: %zu chars", formatted_prompt.length());

    // -------------------------------------------------------------------------
    // 2. Tokenize
    // -------------------------------------------------------------------------
    const llama_vocab *vocab = llama_model_get_vocab(model);
    const int max_prompt_tokens = static_cast<int>(formatted_prompt.length()) + 128;
    std::vector<llama_token> tokens(max_prompt_tokens);

    int n_tokens = llama_tokenize(vocab, formatted_prompt.c_str(),
                                  static_cast<int32_t>(formatted_prompt.length()),
                                  tokens.data(), max_prompt_tokens,
                                  true,  // add_special (BOS)
                                  true); // parse_special

    if (n_tokens < 0) {
        // Buffer too small — resize and retry
        tokens.resize(static_cast<size_t>(-n_tokens));
        n_tokens = llama_tokenize(vocab, formatted_prompt.c_str(),
                                  static_cast<int32_t>(formatted_prompt.length()),
                                  tokens.data(), static_cast<int32_t>(tokens.size()),
                                  true, true);
        if (n_tokens < 0) {
            LOGE("Tokenization failed after retry");
            return env->NewStringUTF("");
        }
    }
    tokens.resize(static_cast<size_t>(n_tokens));
    LOGI("Tokenized prompt: %d tokens", n_tokens);

    // -------------------------------------------------------------------------
    // 3. Resolve Java callback method
    // -------------------------------------------------------------------------
    jclass callbackClass = nullptr;
    jmethodID invokeMethod = nullptr;

    if (callback) {
        callbackClass = env->GetObjectClass(callback);
        if (callbackClass) {
            invokeMethod = env->GetMethodID(callbackClass, "invoke",
                                            "(Ljava/lang/Object;)Ljava/lang/Object;");
            if (!invokeMethod) {
                LOGW("Could not find invoke method on callback — streaming disabled");
            }
        } else {
            LOGW("Could not get callback class — streaming disabled");
        }
    }

    // -------------------------------------------------------------------------
    // 4. KV Cache Reuse (Context Shifting) and Prefill
    // -------------------------------------------------------------------------
    
    std::vector<llama_token> &session_tokens = [&]() -> std::vector<llama_token>& {
        std::lock_guard<std::mutex> lock(g_session_mutex);
        return g_session_tokens[ctx];
    }();

    // Find the longest common prefix between the new prompt and our previous session state
    int n_past = 0;
    while (n_past < session_tokens.size() && n_past < n_tokens && session_tokens[n_past] == tokens[n_past]) {
        n_past++;
    }

    // Always leave at least one prompt token to decode in this call.
    //
    // If the whole prompt is already cached, n_new_tokens below is 0, and two
    // separate things go wrong. llama_batch_init(0, ...) allocates a one-element
    // seq_id array whose only entry is nullptr (see llama-batch.cpp), while the
    // generation loop writes batch.seq_id[0][0] on every iteration — a store
    // through a null pointer, which is the SIGSEGV (SEGV_MAPERR at 0x0, inside
    // this function) that used to kill the app mid-reply. And with nothing
    // decoded here, llama_sampler_sample would draw from whatever logits the
    // *previous* generation happened to leave in the context.
    //
    // It is reachable whenever a prompt is byte-identical to the last one, which
    // with conversation history set to 0 means simply saying the same thing
    // twice — hence a crash that looked intermittent.
    if (n_past > 0 && n_past == n_tokens) {
        n_past--;
    }

    // If the new prompt diverges from the cached state, remove the divergent suffix from the cache
    if (n_past < session_tokens.size()) {
        LOGI("Cache diverged at token %d. Removing cached tokens from %d to %zu", n_past, n_past, session_tokens.size());
        llama_memory_seq_rm(llama_get_memory(ctx), 0, n_past, -1);
    }
    
    // We only need to prefill the new tokens that aren't already in the cache
    int n_new_tokens = n_tokens - n_past;
    
    // Sized for at least one token independently of the prefill: the generation
    // loop below reuses this same batch one token at a time, so a batch with no
    // room is unusable even when there is nothing to prefill. The n_past
    // adjustment above should already guarantee this; belt and braces, because
    // the failure mode is a null-pointer store rather than anything diagnosable.
    llama_batch batch = llama_batch_init(std::max(n_new_tokens, 1), 0, 1);

    for (int i = 0; i < n_new_tokens; i++) {
        batch.token[batch.n_tokens] = tokens[n_past + i];
        batch.pos[batch.n_tokens] = n_past + i;
        batch.n_seq_id[batch.n_tokens] = 1;
        batch.seq_id[batch.n_tokens][0] = 0;
        batch.logits[batch.n_tokens] = false;
        batch.n_tokens++;
    }
    
    // Enable logits for the very last prompt token to begin generation
    if (batch.n_tokens > 0) {
        batch.logits[batch.n_tokens - 1] = true;
        if (decode_in_chunks(ctx, batch) != 0) {
            LOGE("Prefill decode failed");
            llama_batch_free(batch);
            return env->NewStringUTF("");
        }
        LOGI("Prefill complete: %d new tokens decoded (reused %d cached tokens)", n_new_tokens, n_past);
    } else {
        LOGI("Prefill skipped: all %d tokens were already in the cache", n_past);
    }


    // -------------------------------------------------------------------------
    // 5. Build sampler chain: penalties -> temp -> top_p -> dist
    //
    // The penalty stage is not optional. Without it a small model readily falls
    // into a degenerate loop, emitting the same clause until it hits the token
    // limit (observed: "you've been mentioned in a message" repeated for
    // hundreds of tokens while summarising notifications). Penalising recently
    // emitted tokens breaks the cycle. Applied before temp/top_p so the logits
    // are adjusted prior to being narrowed.
    // -------------------------------------------------------------------------
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());

    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
            PENALTY_LAST_N, PENALTY_REPEAT, PENALTY_FREQ, PENALTY_PRESENT));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // -------------------------------------------------------------------------
    // 6. Autoregressive generation loop
    // -------------------------------------------------------------------------
    std::string generated_text;
    std::string token_buffer; // Buffers partial UTF-8 tokens
    int n_cur = n_tokens;
    const int n_max = n_tokens + maxTokens;
    char token_buf[256];

    while (n_cur < n_max) {
        // Sample next token
        llama_token new_token = llama_sampler_sample(smpl, ctx, -1);

        // Check for end of generation
        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGI("EOS/EOG token encountered at position %d", n_cur);
            break;
        }

        // Convert token to text
        int token_len = llama_token_to_piece(vocab, new_token,
                                             token_buf, sizeof(token_buf) - 1,
                                             0, true);
        if (token_len < 0) {
            LOGW("Failed to convert token %d to text", new_token);
            break;
        }
        token_buf[token_len] = '\0';

        std::string token_str(token_buf, static_cast<size_t>(token_len));
        generated_text += token_str;
        token_buffer += token_str;

        // Only stream to Kotlin if we have a complete UTF-8 sequence.
        // Otherwise, JNI NewStringUTF will crash with "illegal continuation byte".
        if (is_complete_utf8(token_buffer)) {
            if (callback && invokeMethod) {
                jstring jTokenStr = env->NewStringUTF(token_buffer.c_str());
                if (jTokenStr) {
                    env->CallObjectMethod(callback, invokeMethod, jTokenStr);
                    env->DeleteLocalRef(jTokenStr);

                    // Check for Java exception (e.g., cancellation)
                    if (env->ExceptionCheck()) {
                        LOGW("Exception in callback — aborting generation");
                        env->ExceptionClear();
                        token_buffer.clear(); // Prevent trailing buffer from emitting
                        break;
                    }
                }
            }
            token_buffer.clear();
        }

        // Prepare batch for next token
        batch.n_tokens = 0;
        batch.token[batch.n_tokens] = new_token;
        batch.pos[batch.n_tokens] = n_cur;
        batch.n_seq_id[batch.n_tokens] = 1;
        batch.seq_id[batch.n_tokens][0] = 0;
        batch.logits[batch.n_tokens] = true;
        batch.n_tokens++;

        if (llama_decode(ctx, batch) != 0) {
            LOGE("Decode failed at position %d", n_cur);
            break;
        }
        
        tokens.push_back(new_token);

        n_cur++;
    }

    LOGI("Generation complete: %d tokens generated, total text length: %zu",
         n_cur - n_tokens, generated_text.length());

    // Emit any trailing buffer
    if (!token_buffer.empty() && callback && invokeMethod) {
        jstring jTokenStr = env->NewStringUTF(token_buffer.c_str());
        if (jTokenStr) {
            env->CallObjectMethod(callback, invokeMethod, jTokenStr);
            env->DeleteLocalRef(jTokenStr);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        }
    }

    // -------------------------------------------------------------------------
    // 7. Cleanup and update session state
    // -------------------------------------------------------------------------
    
    // Save the evaluated sequence of tokens for the next turn
    session_tokens = tokens;
    
    llama_sampler_free(smpl);
    llama_batch_free(batch);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    return env->NewStringUTF(generated_text.c_str());
}

/**
 * Get performance metrics from the context.
 *
 * @param ctxPtr Pointer to the llama_context.
 * @return float array: [prompt_eval_tps, gen_tps, load_time_ms]
 */
JNIEXPORT jfloatArray JNICALL
Java_com_digitalpet_llm_LlamaNative_getPerformanceMetrics(JNIEnv *env, jobject /*thiz*/,
                                                          jlong ctxPtr) {
    auto *ctx = reinterpret_cast<llama_context *>(ctxPtr);

    jfloatArray result = env->NewFloatArray(3);
    if (!result) {
        LOGE("getPerformanceMetrics: failed to allocate float array");
        return nullptr;
    }

    float metrics[3] = {0.0f, 0.0f, 0.0f};

    if (ctx) {
        struct llama_perf_context_data perf = llama_perf_context(ctx);

        // Calculate tokens per second
        // prompt_eval: n_p_eval tokens in t_p_eval_ms milliseconds
        if (perf.t_p_eval_ms > 0 && perf.n_p_eval > 0) {
            metrics[0] = static_cast<float>(perf.n_p_eval) / (static_cast<float>(perf.t_p_eval_ms) / 1000.0f);
        }
        // generation: n_eval tokens in t_eval_ms milliseconds
        if (perf.t_eval_ms > 0 && perf.n_eval > 0) {
            metrics[1] = static_cast<float>(perf.n_eval) / (static_cast<float>(perf.t_eval_ms) / 1000.0f);
        }
        // load time
        metrics[2] = static_cast<float>(perf.t_load_ms);

        LOGI("Performance: prompt_tps=%.1f, gen_tps=%.1f, load_ms=%.1f",
             metrics[0], metrics[1], metrics[2]);
    } else {
        LOGW("getPerformanceMetrics: null context pointer");
    }

    env->SetFloatArrayRegion(result, 0, 3, metrics);
    return result;
}

/**
 * Free a llama context and release associated resources.
 */
JNIEXPORT void JNICALL
Java_com_digitalpet_llm_LlamaNative_freeContext(JNIEnv * /*env*/, jobject /*thiz*/, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<llama_context *>(ctxPtr);
    if (ctx) {
        LOGI("Freeing context: %p", (void *)ctx);
        
        {
            std::lock_guard<std::mutex> lock(g_session_mutex);
            g_session_tokens.erase(ctx);
        }
        
        llama_free(ctx);
    } else {
        LOGW("freeContext: null pointer");
    }
}

/**
 * Free a loaded model and release associated memory.
 */
JNIEXPORT void JNICALL
Java_com_digitalpet_llm_LlamaNative_freeModel(JNIEnv * /*env*/, jobject /*thiz*/, jlong modelPtr) {
    auto *model = reinterpret_cast<llama_model *>(modelPtr);
    if (model) {
        LOGI("Freeing model: %p", (void *)model);
        llama_model_free(model);
    } else {
        LOGW("freeModel: null pointer");
    }
}

/**
 * Shut down the llama backend and release global resources.
 */
JNIEXPORT void JNICALL
Java_com_digitalpet_llm_LlamaNative_freeBackend(JNIEnv * /*env*/, jobject /*thiz*/) {
    LOGI("Freeing llama backend");
    llama_backend_free();
    LOGI("llama backend freed");
}

} // extern "C"
