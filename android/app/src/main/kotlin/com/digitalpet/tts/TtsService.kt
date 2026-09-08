package com.digitalpet.tts

import android.content.Context
import android.util.Log
import com.digitalpet.util.DiagnosticLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TtsService @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val diagnosticLogger: DiagnosticLogger
) {

    companion object {
        private const val TAG = "TtsService"
        private const val ESPEAK_DATA_DIR = "espeak-ng-data"
        // Keep letters, marks, numbers, punctuation, spaces, and newlines. Strip everything else (like emojis and symbols).
        private val EMOJI_REGEX = Regex("[^\\p{L}\\p{M}\\p{N}\\p{P}\\p{Z}\\n]")

        init {
            System.loadLibrary("digitalpet-native")
        }
    }

    private var isInitialised = false
    val isReady: Boolean get() = isInitialised
    private val nativeMutex = Mutex()
    private var nativeSampleRate = 0

    // JNI bindings
    private external fun initNative(modelPath: String, configPath: String, espeakDataPath: String): Boolean
    private external fun synthesizeNative(text: String): ShortArray
    private external fun getSampleRateNative(): Int
    private external fun destroyNative()

    suspend fun init(
        context: Context,
        modelPath: String,
        configPath: String
    ) = withContext(Dispatchers.Default) {
        val startTime = System.nanoTime()
        diagnosticLogger.log(TAG, "init — model=$modelPath, config=$configPath")

        nativeMutex.withLock {
            val espeakPath = extractEspeakData(context)
            diagnosticLogger.log(TAG, "eSpeak data at $espeakPath")

            try {
                if (initNative(modelPath, configPath, espeakPath)) {
                    isInitialised = true
                    nativeSampleRate = getSampleRateNative()
                    val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
                    diagnosticLogger.log(TAG, "init complete in ${elapsedMs}ms — sampleRate=${nativeSampleRate}Hz")
                } else {
                    diagnosticLogger.log(TAG, "Native init returned false")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Native init failed", e)
                diagnosticLogger.log(TAG, "Native init error: ${e.message}")
            }
        }
    }

    suspend fun synthesize(text: String): ShortArray = withContext(Dispatchers.Default) {
        val cleanText = text.replace(EMOJI_REGEX, " ").replace(Regex("\\s+"), " ").trim()
        if (cleanText.isBlank()) return@withContext ShortArray(0)

        val startTime = System.nanoTime()
        diagnosticLogger.log(TAG, "synthesize — \"${cleanText.take(60)}\" (${cleanText.length} chars)")

        nativeMutex.withLock {
            check(isInitialised) { "TtsService not initialised — call init() first" }

            val pcm = try {
                synthesizeNative(cleanText)
            } catch (e: Exception) {
                Log.e(TAG, "Native synthesize failed", e)
                diagnosticLogger.log(TAG, "synthesize error: ${e.message}")
                ShortArray(0)
            }

            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
            val durationSec = if (getSampleRate() > 0) pcm.size.toFloat() / getSampleRate() else 0f
            diagnosticLogger.log(TAG, "synthesize complete in ${elapsedMs}ms — ${pcm.size} samples (~${"%.1f".format(durationSec)}s)")

            pcm
        }
    }

    fun getSampleRate(): Int = nativeSampleRate

    fun destroy() {
        diagnosticLogger.log(TAG, "destroy")
        if (isInitialised) {
            destroyNative()
            isInitialised = false
        }
    }

    private fun extractEspeakData(context: Context): String {
        val destDir = File(context.filesDir, ESPEAK_DATA_DIR)
        val phontab = File(destDir, "phontab")

        if (phontab.exists()) {
            return destDir.absolutePath
        }

        diagnosticLogger.log(TAG, "Extracting eSpeak data to ${destDir.absolutePath}")
        destDir.deleteRecursively()
        destDir.mkdirs()

        try {
            copyAssetDir(context, ESPEAK_DATA_DIR, destDir)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract eSpeak data", e)
            diagnosticLogger.log(TAG, "eSpeak extraction error: ${e.message}")
        }

        return destDir.absolutePath
    }

    private fun copyAssetDir(context: Context, assetDir: String, destDir: File) {
        val assets = context.assets.list(assetDir) ?: return

        if (assets.isEmpty()) {
            context.assets.open(assetDir).use { input ->
                FileOutputStream(File(destDir, File(assetDir).name)).use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        for (child in assets) {
            val childAssetPath = "$assetDir/$child"
            val childDest = File(destDir, child)

            val grandChildren = context.assets.list(childAssetPath)
            if (grandChildren != null && grandChildren.isNotEmpty()) {
                childDest.mkdirs()
                copyAssetDir(context, childAssetPath, childDest)
            } else {
                context.assets.open(childAssetPath).use { input ->
                    FileOutputStream(childDest).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}
