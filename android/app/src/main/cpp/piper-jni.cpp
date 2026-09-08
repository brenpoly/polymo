#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <android/log.h>
#include "piper.hpp"

#define LOG_TAG "PiperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static piper::PiperConfig piperConfig;
static piper::Voice piperVoice;
static bool isInitialized = false;
static std::mutex piperMutex;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_digitalpet_tts_TtsService_initNative(JNIEnv* env, jobject /* this */,
                                              jstring jModelPath, jstring jConfigPath, jstring jEspeakDataPath) {
    std::lock_guard<std::mutex> lock(piperMutex);

    const char* modelPathStr = env->GetStringUTFChars(jModelPath, nullptr);
    const char* configPathStr = env->GetStringUTFChars(jConfigPath, nullptr);
    const char* espeakDataPathStr = env->GetStringUTFChars(jEspeakDataPath, nullptr);

    if (!modelPathStr || !configPathStr || !espeakDataPathStr) {
        LOGE("Invalid arguments passed to initNative.");
        if (modelPathStr) env->ReleaseStringUTFChars(jModelPath, modelPathStr);
        if (configPathStr) env->ReleaseStringUTFChars(jConfigPath, configPathStr);
        if (espeakDataPathStr) env->ReleaseStringUTFChars(jEspeakDataPath, espeakDataPathStr);
        return JNI_FALSE;
    }

    std::string modelPath = modelPathStr;
    std::string configPath = configPathStr;
    std::string espeakDataPath = espeakDataPathStr;

    env->ReleaseStringUTFChars(jModelPath, modelPathStr);
    env->ReleaseStringUTFChars(jConfigPath, configPathStr);
    env->ReleaseStringUTFChars(jEspeakDataPath, espeakDataPathStr);

    try {
        // Clean up any previous initialization before re-initializing
        if (isInitialized) {
            LOGI("Cleaning up previous Piper instance before re-init.");
            piper::terminate(piperConfig);
            piperVoice = piper::Voice();
            isInitialized = false;
        }

        piperConfig = piper::PiperConfig();
        piperConfig.eSpeakDataPath = espeakDataPath;
        piperConfig.useESpeak = true;

        piper::initialize(piperConfig);

        std::optional<piper::SpeakerId> speakerId;
        piper::loadVoice(piperConfig, modelPath, configPath, piperVoice, speakerId, false);

        // Speed up the TTS and remove artificial pauses since we are streaming sentences
        piperVoice.synthesisConfig.lengthScale = 0.85f;
        piperVoice.synthesisConfig.sentenceSilenceSeconds = 0.0f;

        isInitialized = true;
        LOGI("Piper initialized successfully. Sample rate: %d", piperVoice.synthesisConfig.sampleRate);
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("Failed to initialize Piper: %s", e.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_digitalpet_tts_TtsService_synthesizeNative(JNIEnv* env, jobject /* this */, jstring jText) {
    std::lock_guard<std::mutex> lock(piperMutex);

    if (!isInitialized) {
        LOGE("Synthesize called before initialization.");
        return env->NewShortArray(0);
    }

    const char* textStr = env->GetStringUTFChars(jText, nullptr);
    if (!textStr) {
        return env->NewShortArray(0);
    }
    std::string text = textStr;
    env->ReleaseStringUTFChars(jText, textStr);

    try {
        std::vector<int16_t> audioBuffer;
        piper::SynthesisResult result;

        // Callback is not used for streaming in this simplified JNI wrapper, we collect all audio and return it
        piper::textToAudio(piperConfig, piperVoice, text, audioBuffer, result, nullptr);

        jshortArray jArray = env->NewShortArray(audioBuffer.size());
        if (jArray == nullptr) {
            LOGE("Out of memory when allocating short array.");
            return nullptr;
        }

        env->SetShortArrayRegion(jArray, 0, audioBuffer.size(), (jshort*)audioBuffer.data());
        return jArray;
    } catch (const std::exception& e) {
        LOGE("Failed to synthesize audio: %s", e.what());
        return env->NewShortArray(0);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_digitalpet_tts_TtsService_getSampleRateNative(JNIEnv* env, jobject /* this */) {
    if (!isInitialized) return 0;
    return piperVoice.synthesisConfig.sampleRate;
}

extern "C" JNIEXPORT void JNICALL
Java_com_digitalpet_tts_TtsService_destroyNative(JNIEnv* env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(piperMutex);
    if (isInitialized) {
        piper::terminate(piperConfig);
        isInitialized = false;
        LOGI("Piper terminated.");
    }
}
