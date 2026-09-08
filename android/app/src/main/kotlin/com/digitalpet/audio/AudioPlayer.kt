package com.digitalpet.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlayer @Inject constructor() {

    companion object {
        private const val TAG = "AudioPlayer"
    }

    private val lock = Object()
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate = -1

    /**
     * Initializes the AudioTrack with the given sample rate if it's not already initialized
     * or if the sample rate has changed.
     */
    private fun ensureTrack(sampleRate: Int) {
        // Must be called while holding `lock`
        if (audioTrack != null && currentSampleRate == sampleRate) {
            // Resume if it was paused/stopped
            if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    audioTrack?.play()
                } catch (e: Exception) {
                    Log.e(TAG, "Error resuming track, recreating", e)
                    releaseInternal()
                    // Fall through to create a new one
                }
            }
            if (audioTrack != null) return
        }

        releaseInternal()

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * 4) // Use a larger buffer for smoother playback
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        currentSampleRate = sampleRate
        audioTrack?.play()
    }

    /**
     * Plays a chunk of raw 16-bit PCM audio.
     */
    fun playAudioChunk(pcmData: ShortArray, sampleRate: Int) {
        if (pcmData.isEmpty() || sampleRate <= 0) return

        synchronized(lock) {
            try {
                ensureTrack(sampleRate)

                val written = audioTrack?.write(pcmData, 0, pcmData.size) ?: 0
                if (written < 0) {
                    Log.e(TAG, "Failed to write audio data: error code $written")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error playing audio chunk", e)
            }
        }
    }

    /**
     * Stops playback and releases the track so the next playAudioChunk
     * will start fresh.  This avoids the broken pause+flush+re-play pattern.
     */
    fun stop() {
        synchronized(lock) {
            releaseInternal()
        }
    }

    /**
     * Releases resources.
     */
    fun release() {
        synchronized(lock) {
            releaseInternal()
        }
    }

    /** Must be called while holding [lock]. */
    private fun releaseInternal() {
        try {
            audioTrack?.stop()
        } catch (_: Exception) { }
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio track", e)
        } finally {
            audioTrack = null
            currentSampleRate = -1
        }
    }
}
