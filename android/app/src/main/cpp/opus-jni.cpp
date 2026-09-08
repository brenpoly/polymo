/**
 * opus-jni.cpp — JNI bridge for Opus audio codec
 *
 * Provides encoder/decoder creation, PCM-to-Opus encoding,
 * Opus-to-PCM decoding, packet loss concealment, and resource cleanup.
 *
 * JNI class: com.digitalpet.audio.OpusNative
 */

#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "opus.h"

#define TAG "OpusJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/**
 * Maximum encoded Opus frame size in bytes.
 * Opus documentation recommends 4000 bytes as a safe upper bound.
 */
static constexpr int MAX_OPUS_FRAME_SIZE = 4000;

extern "C" {

/**
 * Create an Opus encoder.
 *
 * @param sampleRate Audio sample rate in Hz (8000, 12000, 16000, 24000, or 48000).
 * @param channels   Number of audio channels (1 for mono, 2 for stereo).
 * @param bitrate    Target bitrate in bits per second.
 * @param frameSize  Frame size in samples per channel (unused during creation but
 *                   validated for informational logging).
 * @return Encoder pointer as jlong, or 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_digitalpet_audio_OpusNative_createEncoder(JNIEnv * /*env*/, jobject /*thiz*/,
                                                   jint sampleRate, jint channels,
                                                   jint bitrate, jint frameSize) {
    LOGI("Creating Opus encoder: sampleRate=%d, channels=%d, bitrate=%d, frameSize=%d",
         sampleRate, channels, bitrate, frameSize);

    int error = OPUS_OK;
    OpusEncoder *encoder = opus_encoder_create(
        static_cast<opus_int32>(sampleRate),
        static_cast<int>(channels),
        OPUS_APPLICATION_VOIP,
        &error
    );

    if (error != OPUS_OK || !encoder) {
        LOGE("Failed to create Opus encoder: %s (error=%d)",
             opus_strerror(error), error);
        return 0;
    }

    // Set target bitrate
    error = opus_encoder_ctl(encoder, OPUS_SET_BITRATE(static_cast<opus_int32>(bitrate)));
    if (error != OPUS_OK) {
        LOGW("Failed to set bitrate to %d: %s", bitrate, opus_strerror(error));
    }

    // Enable inband FEC for packet loss resilience
    error = opus_encoder_ctl(encoder, OPUS_SET_INBAND_FEC(1));
    if (error != OPUS_OK) {
        LOGW("Failed to enable inband FEC: %s", opus_strerror(error));
    }

    // Set expected packet loss percentage hint
    error = opus_encoder_ctl(encoder, OPUS_SET_PACKET_LOSS_PERC(10));
    if (error != OPUS_OK) {
        LOGW("Failed to set packet loss percentage: %s", opus_strerror(error));
    }

    LOGI("Opus encoder created: %p", (void *)encoder);
    return reinterpret_cast<jlong>(encoder);
}

/**
 * Create an Opus decoder.
 *
 * @param sampleRate Audio sample rate in Hz.
 * @param channels   Number of audio channels.
 * @return Decoder pointer as jlong, or 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_digitalpet_audio_OpusNative_createDecoder(JNIEnv * /*env*/, jobject /*thiz*/,
                                                   jint sampleRate, jint channels) {
    LOGI("Creating Opus decoder: sampleRate=%d, channels=%d", sampleRate, channels);

    int error = OPUS_OK;
    OpusDecoder *decoder = opus_decoder_create(
        static_cast<opus_int32>(sampleRate),
        static_cast<int>(channels),
        &error
    );

    if (error != OPUS_OK || !decoder) {
        LOGE("Failed to create Opus decoder: %s (error=%d)",
             opus_strerror(error), error);
        return 0;
    }

    LOGI("Opus decoder created: %p", (void *)decoder);
    return reinterpret_cast<jlong>(decoder);
}

/**
 * Encode PCM audio samples to Opus format.
 *
 * @param encoderPtr Pointer to the OpusEncoder.
 * @param pcmData    Short array of 16-bit PCM audio samples.
 * @param frameSize  Number of samples per channel in this frame.
 * @return Byte array of Opus-encoded data, or null on failure.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_digitalpet_audio_OpusNative_encode(JNIEnv *env, jobject /*thiz*/,
                                            jlong encoderPtr, jshortArray pcmData,
                                            jint frameSize) {
    auto *encoder = reinterpret_cast<OpusEncoder *>(encoderPtr);
    if (!encoder) {
        LOGE("encode: null encoder pointer");
        return nullptr;
    }

    if (!pcmData) {
        LOGE("encode: null PCM data");
        return nullptr;
    }

    jint pcm_len = env->GetArrayLength(pcmData);
    if (pcm_len <= 0) {
        LOGW("encode: empty PCM data");
        return nullptr;
    }

    jshort *pcm_ptr = env->GetShortArrayElements(pcmData, nullptr);
    if (!pcm_ptr) {
        LOGE("encode: failed to get PCM array elements");
        return nullptr;
    }

    // Allocate output buffer
    unsigned char opus_buffer[MAX_OPUS_FRAME_SIZE];

    opus_int32 encoded_bytes = opus_encode(
        encoder,
        pcm_ptr,
        static_cast<int>(frameSize),
        opus_buffer,
        MAX_OPUS_FRAME_SIZE
    );

    env->ReleaseShortArrayElements(pcmData, pcm_ptr, JNI_ABORT);

    if (encoded_bytes < 0) {
        LOGE("Opus encode failed: %s (error=%d)",
             opus_strerror(static_cast<int>(encoded_bytes)),
             static_cast<int>(encoded_bytes));
        return nullptr;
    }

    // Create Java byte array with encoded data
    jbyteArray result = env->NewByteArray(encoded_bytes);
    if (!result) {
        LOGE("encode: failed to allocate byte array of size %d", encoded_bytes);
        return nullptr;
    }

    env->SetByteArrayRegion(result, 0, encoded_bytes,
                            reinterpret_cast<const jbyte *>(opus_buffer));
    return result;
}

/**
 * Decode Opus-encoded data to PCM audio samples.
 *
 * @param decoderPtr Pointer to the OpusDecoder.
 * @param opusData   Byte array of Opus-encoded audio data.
 * @param frameSize  Number of samples per channel to decode.
 * @return Short array of 16-bit PCM audio samples, or null on failure.
 */
JNIEXPORT jshortArray JNICALL
Java_com_digitalpet_audio_OpusNative_decode(JNIEnv *env, jobject /*thiz*/,
                                            jlong decoderPtr, jbyteArray opusData,
                                            jint frameSize) {
    auto *decoder = reinterpret_cast<OpusDecoder *>(decoderPtr);
    if (!decoder) {
        LOGE("decode: null decoder pointer");
        return nullptr;
    }

    if (!opusData) {
        LOGE("decode: null Opus data");
        return nullptr;
    }

    jint opus_len = env->GetArrayLength(opusData);
    if (opus_len <= 0) {
        LOGW("decode: empty Opus data");
        return nullptr;
    }

    jbyte *opus_ptr = env->GetByteArrayElements(opusData, nullptr);
    if (!opus_ptr) {
        LOGE("decode: failed to get Opus array elements");
        return nullptr;
    }

    // Allocate output buffer for decoded PCM (mono assumed; caller controls channels)
    // Use a generous buffer: frameSize * max channels(2)
    const int max_pcm_samples = static_cast<int>(frameSize) * 2;
    std::vector<opus_int16> pcm_buffer(static_cast<size_t>(max_pcm_samples));

    int decoded_samples = opus_decode(
        decoder,
        reinterpret_cast<const unsigned char *>(opus_ptr),
        static_cast<opus_int32>(opus_len),
        pcm_buffer.data(),
        static_cast<int>(frameSize),
        0  // no FEC decoding for normal packets
    );

    env->ReleaseByteArrayElements(opusData, opus_ptr, JNI_ABORT);

    if (decoded_samples < 0) {
        LOGE("Opus decode failed: %s (error=%d)",
             opus_strerror(decoded_samples), decoded_samples);
        return nullptr;
    }

    // Create Java short array with decoded PCM data
    jshortArray result = env->NewShortArray(decoded_samples);
    if (!result) {
        LOGE("decode: failed to allocate short array of size %d", decoded_samples);
        return nullptr;
    }

    env->SetShortArrayRegion(result, 0, decoded_samples, pcm_buffer.data());
    return result;
}

/**
 * Decode with Packet Loss Concealment (PLC).
 *
 * When a packet is lost, Opus can generate plausible audio to fill the gap.
 * This is invoked with NULL data to signal a missing packet.
 *
 * @param decoderPtr Pointer to the OpusDecoder.
 * @param frameSize  Number of samples per channel expected in the missing frame.
 * @return Short array of concealed PCM audio samples, or null on failure.
 */
JNIEXPORT jshortArray JNICALL
Java_com_digitalpet_audio_OpusNative_decodePLC(JNIEnv *env, jobject /*thiz*/,
                                               jlong decoderPtr, jint frameSize) {
    auto *decoder = reinterpret_cast<OpusDecoder *>(decoderPtr);
    if (!decoder) {
        LOGE("decodePLC: null decoder pointer");
        return nullptr;
    }

    const int max_pcm_samples = static_cast<int>(frameSize) * 2;
    std::vector<opus_int16> pcm_buffer(static_cast<size_t>(max_pcm_samples));

    // Pass NULL data and 0 length to trigger PLC
    int decoded_samples = opus_decode(
        decoder,
        nullptr,  // NULL triggers packet loss concealment
        0,
        pcm_buffer.data(),
        static_cast<int>(frameSize),
        0
    );

    if (decoded_samples < 0) {
        LOGE("Opus PLC decode failed: %s (error=%d)",
             opus_strerror(decoded_samples), decoded_samples);
        return nullptr;
    }

    LOGI("PLC generated %d samples", decoded_samples);

    jshortArray result = env->NewShortArray(decoded_samples);
    if (!result) {
        LOGE("decodePLC: failed to allocate short array of size %d", decoded_samples);
        return nullptr;
    }

    env->SetShortArrayRegion(result, 0, decoded_samples, pcm_buffer.data());
    return result;
}

/**
 * Destroy an Opus encoder and free its resources.
 */
JNIEXPORT void JNICALL
Java_com_digitalpet_audio_OpusNative_destroyEncoder(JNIEnv * /*env*/, jobject /*thiz*/,
                                                    jlong encoderPtr) {
    auto *encoder = reinterpret_cast<OpusEncoder *>(encoderPtr);
    if (encoder) {
        LOGI("Destroying Opus encoder: %p", (void *)encoder);
        opus_encoder_destroy(encoder);
    } else {
        LOGW("destroyEncoder: null pointer");
    }
}

/**
 * Destroy an Opus decoder and free its resources.
 */
JNIEXPORT void JNICALL
Java_com_digitalpet_audio_OpusNative_destroyDecoder(JNIEnv * /*env*/, jobject /*thiz*/,
                                                    jlong decoderPtr) {
    auto *decoder = reinterpret_cast<OpusDecoder *>(decoderPtr);
    if (decoder) {
        LOGI("Destroying Opus decoder: %p", (void *)decoder);
        opus_decoder_destroy(decoder);
    } else {
        LOGW("destroyDecoder: null pointer");
    }
}

} // extern "C"
