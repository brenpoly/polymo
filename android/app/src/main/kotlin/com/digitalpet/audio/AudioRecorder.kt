package com.digitalpet.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioRecorder @Inject constructor() {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val recordedData = mutableListOf<ShortArray>()

    @SuppressLint("MissingPermission")
    suspend fun startRecording() = withContext(Dispatchers.IO) {
        if (isRecording) return@withContext

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid AudioRecord parameters")
            return@withContext
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufferSize * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            audioRecord?.release()
            audioRecord = null
            return@withContext
        }

        recordedData.clear()
        audioRecord?.startRecording()
        isRecording = true

        val buffer = ShortArray(minBufferSize)
        while (isRecording && isActive) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (read > 0) {
                recordedData.add(buffer.copyOfRange(0, read))
            }
        }

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun stopRecording(): ShortArray {
        isRecording = false
        
        // Flatten the list of ShortArrays into a single ShortArray
        val totalLength = recordedData.sumOf { it.size }
        val result = ShortArray(totalLength)
        var offset = 0
        for (chunk in recordedData) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }
        
        return result
    }
}
