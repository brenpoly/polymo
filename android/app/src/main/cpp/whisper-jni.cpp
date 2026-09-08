/**
 * whisper-jni.cpp — JNI bridge for whisper.cpp speech-to-text engine
 *
 * Provides model loading, audio transcription with greedy decoding,
 * and resource cleanup.
 *
 * JNI class: com.digitalpet.stt.WhisperNative
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/**
 * Safely convert a jstring to a std::string with null checks.
 */
static std::string jstring_to_string(JNIEnv *env, jstring jstr) {
    if (!jstr) return "";
    const char *raw = env->GetStringUTFChars(jstr, nullptr);
    if (!raw) return "";
    std::string result(raw);
    env->ReleaseStringUTFChars(jstr, raw);
    return result;
}

extern "C" {

/**
 * Initialize a Whisper context by loading a model from disk.
 *
 * @param modelPath Absolute path to the Whisper GGML model file.
 * @return Context pointer as jlong, or 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_digitalpet_stt_WhisperNative_initContext(JNIEnv *env, jobject /*thiz*/, jstring modelPath) {
    std::string path = jstring_to_string(env, modelPath);
    if (path.empty()) {
        LOGE("initContext: model path is null or empty");
        return 0;
    }

    LOGI("Loading Whisper model from: %s", path.c_str());

    struct whisper_context_params ctx_params = whisper_context_default_params();

    struct whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), ctx_params);
    if (!ctx) {
        LOGE("Failed to load Whisper model from: %s", path.c_str());
        return 0;
    }

    LOGI("Whisper model loaded successfully: %p", (void *)ctx);
    return reinterpret_cast<jlong>(ctx);
}

/**
 * Transcribe audio samples to text.
 *
 * @param ctxPtr    Pointer to the whisper_context.
 * @param audioData Float array of audio samples (mono, 16kHz, normalized [-1,1]).
 * @param language  ISO 639-1 language code (e.g. "en"), or empty for auto-detect.
 * @param nThreads  Number of CPU threads for inference.
 * @return Transcribed text as a jstring.
 */
JNIEXPORT jstring JNICALL
Java_com_digitalpet_stt_WhisperNative_transcribe(JNIEnv *env, jobject /*thiz*/,
                                                 jlong ctxPtr, jfloatArray audioData,
                                                 jstring language, jint nThreads) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(ctxPtr);
    if (!ctx) {
        LOGE("transcribe: null context pointer");
        return env->NewStringUTF("");
    }

    if (!audioData) {
        LOGE("transcribe: null audio data");
        return env->NewStringUTF("");
    }

    // Get audio samples from Java array
    jint audio_len = env->GetArrayLength(audioData);
    if (audio_len <= 0) {
        LOGW("transcribe: empty audio data");
        return env->NewStringUTF("");
    }

    jfloat *audio_ptr = env->GetFloatArrayElements(audioData, nullptr);
    if (!audio_ptr) {
        LOGE("transcribe: failed to get audio array elements");
        return env->NewStringUTF("");
    }

    LOGI("Transcribing %d audio samples with %d threads", audio_len, nThreads);

    // Configure whisper parameters
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = static_cast<int>(nThreads);
    params.no_timestamps = true;
    params.single_segment = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;

    // Set language if provided
    std::string lang = jstring_to_string(env, language);
    if (!lang.empty()) {
        params.language = lang.c_str();
        LOGI("Language set to: %s", lang.c_str());
    } else {
        params.language = "en";
        LOGI("Language defaulting to: en");
    }

    // Run transcription
    int result = whisper_full(ctx, params, audio_ptr, static_cast<int>(audio_len));

    // Release audio array regardless of result
    env->ReleaseFloatArrayElements(audioData, audio_ptr, JNI_ABORT);

    if (result != 0) {
        LOGE("whisper_full failed with code: %d", result);
        return env->NewStringUTF("");
    }

    // Collect all segments into a single string
    const int n_segments = whisper_full_n_segments(ctx);
    LOGI("Transcription produced %d segments", n_segments);

    std::string transcribed_text;
    transcribed_text.reserve(1024);

    for (int i = 0; i < n_segments; i++) {
        const char *segment_text = whisper_full_get_segment_text(ctx, i);
        if (segment_text) {
            if (!transcribed_text.empty() && transcribed_text.back() != ' ') {
                transcribed_text += ' ';
            }
            transcribed_text += segment_text;
        }
    }

    // Trim leading whitespace that Whisper sometimes produces
    size_t start = transcribed_text.find_first_not_of(" \t\n\r");
    if (start != std::string::npos && start > 0) {
        transcribed_text = transcribed_text.substr(start);
    }

    LOGI("Transcription result (%zu chars): %.80s%s",
         transcribed_text.length(),
         transcribed_text.c_str(),
         transcribed_text.length() > 80 ? "..." : "");

    return env->NewStringUTF(transcribed_text.c_str());
}

/**
 * Free a Whisper context and release all associated resources.
 */
JNIEXPORT void JNICALL
Java_com_digitalpet_stt_WhisperNative_freeContext(JNIEnv * /*env*/, jobject /*thiz*/, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(ctxPtr);
    if (ctx) {
        LOGI("Freeing Whisper context: %p", (void *)ctx);
        whisper_free(ctx);
    } else {
        LOGW("freeContext: null pointer");
    }
}

} // extern "C"
