package com.digitalpet.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import com.digitalpet.llm.LlmManager
import com.digitalpet.llm.ModelInfo
import com.digitalpet.stt.SttService
import com.digitalpet.tts.TtsService
import com.digitalpet.util.DiagnosticLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.digitalpet.pet.PetFaculty
import com.digitalpet.pet.PetReadiness
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A Piper voice: an ONNX model, and the sidecar JSON config it cannot run
 * without.
 *
 * **[configPath] is nullable, and that is the point.** A voice whose config is
 * missing used to be dropped from the listing entirely, so an `.onnx` sitting in
 * the models directory appeared as *nothing at all* — the panel said there were
 * no voices while the file was right there, and no screen anywhere said why.
 * Claude Design 2d asks for the opposite: list it and flag it. An unusable voice
 * the user can see is a problem they can fix; one that is silently skipped is a
 * bug report.
 */
data class TtsModelPair(
    val name: String,
    val modelPath: String,
    val configPath: String?,
    val modelSizeMb: Long,
) {
    /** Loadable only with both halves. */
    val isUsable: Boolean get() = configPath != null
}

/**
 * Owns the *files* behind the three on-device models — the LLM (GGUF), the
 * Piper voice (ONNX + JSON) and the Whisper STT model (GGML .bin) — and the
 * preferences recording which of each is active.
 *
 * The `*Service` classes own a loaded model; this owns which model that is.
 * It is the single place that knows the on-disk layout and the `last_*_path`
 * preference keys, so a swap panel never has to agree with the startup path by
 * coincidence — both go through here.
 *
 * A singleton: the models it tracks are process-wide and survive any one
 * screen, so [PetChatViewModel] observes its state rather than holding it.
 */
@Singleton
class ModelRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val llmManager: LlmManager,
    private val ttsService: TtsService,
    private val sttService: SttService,
    private val logger: DiagnosticLogger
) {

    private companion object {
        const val TAG = "ModelRepository"

        const val PREFS = "digital_pet_prefs"
        const val KEY_LLM_PATH = "last_llm_path"
        const val KEY_TTS_MODEL_PATH = "last_tts_model_path"
        const val KEY_TTS_CONFIG_PATH = "last_tts_config_path"
        const val KEY_TTS_MODEL_NAME = "last_tts_model_name"
        const val KEY_STT_PATH = "last_stt_model_path"

        const val TTS_DIR = "tts_models"
        const val STT_DIR = "stt_models"

    }

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Model loading outlives any ViewModel — a voice import must finish even if
     * the user closes the drawer — so this owns a process-lifetime scope rather
     * than borrowing `viewModelScope`.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Observable state ─────────────────────────────────────────────────

    /** Loading progress / readiness of the LLM, straight from [LlmManager]. */
    val llmState: StateFlow<LlmManager.ModelState> = llmManager.modelState

    private val _isTtsReady = MutableStateFlow(false)
    val isTtsReady: StateFlow<Boolean> = _isTtsReady.asStateFlow()

    private val _ttsModelName = MutableStateFlow<String?>(null)
    val ttsModelName: StateFlow<String?> = _ttsModelName.asStateFlow()

    private val _sttModelName = MutableStateFlow<String?>(null)
    val sttModelName: StateFlow<String?> = _sttModelName.asStateFlow()

    /**
     * Why the Whisper model would not load, or null.
     *
     * **Exists because a null name could not tell two very different states
     * apart.** Both `restoreStt` and `loadSttModel` caught their exception and
     * logged it, leaving the name null — so a model that was present and broken
     * looked exactly like one that had never been installed, and `PetReadiness`
     * reported "add a Whisper .bin" to someone who already had one. The advice
     * was not merely unhelpful; it was wrong, and following it would not have
     * fixed anything.
     *
     * Set wherever init fails, cleared wherever it succeeds. The two always move
     * together, which is why they are set on adjacent lines rather than in
     * separate helpers — the failure mode of this pair is one being updated
     * without the other.
     */
    private val _sttError = MutableStateFlow<String?>(null)
    val sttError: StateFlow<String?> = _sttError.asStateFlow()

    /**
     * Whether the pet can answer at all — DESIGN.md §2.1's Axis C.
     *
     * **Lives here rather than in a ViewModel because the pet works with no
     * screen.** The conversation runs between the pet's microphone and its
     * speaker, so anything a UI owns is unavailable in the case that matters:
     * the foreground service coming up on a reconnect with the app never
     * opened. The engine and the notification both read this.
     *
     * Eagerly started, and that is the point. Restoring the models is
     * asynchronous — the LLM alone was measured at 11.2 s — so there is a real
     * window after every service start where the pet is connected, listening,
     * and unable to reply. A lazily-started flow would only begin reporting once
     * something subscribed, which is exactly backwards.
     */
    val readiness: StateFlow<PetReadiness> =
        combine(llmState, _isTtsReady, _sttModelName, _sttError) { llm, tts, stt, sttErr ->
            PetReadiness.of(llm, tts, stt, sttErr)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            PetReadiness.Missing(PetFaculty.entries.toList())
        )

    init {
        restoreLlm()
        restoreTts()
        scope.launch { restoreStt() }
    }

    // ── LLM ──────────────────────────────────────────────────────────────

    /** Scans Downloads and the app's external files dir for `.gguf` files. */
    fun availableLlmModels(): List<ModelInfo> = llmManager.getAvailableModels(appContext)

    suspend fun loadLlm(path: String) {
        prefs.edit().putString(KEY_LLM_PATH, path).apply()
        llmManager.loadModel(path)
    }

    /**
     * Copy a picked `.gguf` into the app's external files dir and record it as
     * the active model. Does not load it — the panel offers a separate "Load
     * Model" action, since loading a multi-GB model is not something to do as a
     * side effect of importing one.
     */
    suspend fun importLlm(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val outFile = File(appContext.getExternalFilesDir(null), displayName(uri, "unknown_file"))
            copyUriTo(uri, outFile)
            prefs.edit().putString(KEY_LLM_PATH, outFile.absolutePath).apply()
        } catch (e: Exception) {
            logger.log(TAG, "Failed to import LLM: ${e.message}")
        }
    }

    suspend fun deleteLlm(path: String) = withContext(Dispatchers.IO) {
        try {
            File(path).delete()
            if (prefs.getString(KEY_LLM_PATH, null) == path) {
                prefs.edit().remove(KEY_LLM_PATH).apply()
                llmManager.unloadModel()
            }
        } catch (e: Exception) {
            logger.log(TAG, "Failed to delete LLM: ${e.message}")
        }
    }

    private fun restoreLlm() {
        val path = prefs.getString(KEY_LLM_PATH, null) ?: return
        if (!File(path).exists()) return
        scope.launch { llmManager.loadModel(path) }
    }

    // ── TTS (Piper voices) ───────────────────────────────────────────────

    /**
     * Voices are `<name>.onnx` paired with `<name>.onnx.json`.
     *
     * **An ONNX without its config is listed, not skipped** — see [TtsModelPair].
     * It cannot be loaded and the screen says so; hiding it made a fixable
     * problem invisible.
     *
     * **The pair must be in the SAME directory** — [voiceDirs] has why there is
     * more than one, and `importTtsVoice` writes a lone config beside the voice
     * it names rather than into [TTS_DIR] precisely because of this line.
     */
    fun availableTtsVoices(): List<TtsModelPair> =
        voiceDirs()
            .flatMap { dir -> dir.listFiles()?.asList() ?: emptyList() }
            .filter { it.extension == "onnx" }
            .mapNotNull { onnx ->
                val config = File(onnx.parentFile, "${onnx.name}.json")
                TtsModelPair(
                    name = onnx.name,
                    modelPath = onnx.absolutePath,
                    configPath = config.absolutePath.takeIf { config.exists() },
                    modelSizeMb = onnx.length() / (1024 * 1024)
                )
            }

    suspend fun loadTtsVoice(modelPath: String, configPath: String) {
        val modelFile = File(modelPath)
        if (!modelFile.exists() || !File(configPath).exists()) return

        prefs.edit()
            .putString(KEY_TTS_MODEL_PATH, modelPath)
            .putString(KEY_TTS_CONFIG_PATH, configPath)
            .putString(KEY_TTS_MODEL_NAME, modelFile.name)
            .apply()
        _ttsModelName.value = modelFile.name

        initTts(modelPath, configPath)
    }

    /**
     * Import a picked voice into [TTS_DIR] — the `.onnx`, its `.json`, or both.
     *
     * The destination names are normalised rather than taken as picked, because
     * [availableTtsVoices] finds a voice only as `<name>.onnx` + `<name>.onnx.json`.
     * Piper distributes voices under exactly those names, but a renamed download
     * would otherwise import successfully and then never appear in the list.
     *
     * **A half-picked voice still imports its half.** Both files are needed and
     * neither can be conjured from the other, but *discarding* the `.onnx`
     * because no `.json` came with it was the worst of the three options: it
     * copied nothing, said nothing, and left the screen looking exactly as it
     * did before the picker opened — so backing out of the old second picker
     * threw away the first pick in silence. Copying it lands the voice in the
     * one state this screen was built to explain: listed, flagged
     * *No .onnx.json beside it*, and fixable. See [VoiceImport] for why the
     * picked files are sorted by name rather than by position.
     */
    suspend fun importTtsVoice(uris: List<Uri>): VoiceImport.Result = withContext(Dispatchers.IO) {
        try {
            // A neutral fallback: one that looked like a voice would make an
            // unnameable file outrank a real `.onnx` sitting beside it.
            val names = uris.map { displayName(it, "imported") }
            val picked = VoiceImport.classify(names)
            val dir = File(appContext.filesDir, TTS_DIR).apply { mkdirs() }
            val modelIndex = picked.model

            if (modelIndex == null) {
                val configIndex = picked.config
                    ?: return@withContext VoiceImport.Result.NothingUsable.also {
                        logger.log(TAG, "TTS import: nothing usable in ${names.joinToString()}")
                    }
                /*
                 * A CONFIG ON ITS OWN, which is a first-class way to import.
                 *
                 * Piper names a config after its voice, so the file says where
                 * it belongs — and it is put BESIDE that voice wherever the
                 * voice already lives, because [availableTtsVoices] pairs the
                 * two only within one directory. Dropping it in [TTS_DIR] by
                 * reflex would leave a voice in the external dir still flagged
                 * as having no config, with the config sitting on the device.
                 *
                 * That is what makes this the way OUT of that flagged row
                 * rather than a second way into it.
                 */
                val voiceName = VoiceImport.voiceFileNameForConfig(names[configIndex])
                val configName = VoiceImport.configFileName(voiceName)
                val voice = locateVoice(voiceName)

                copyUriTo(uris[configIndex], File(voice?.parentFile ?: dir, configName))

                return@withContext if (voice != null) VoiceImport.Result.Complete(voiceName)
                    else VoiceImport.Result.NeedsVoice(voice = voiceName, config = configName)
            }

            val voiceName = VoiceImport.voiceFileName(names[modelIndex])
            val configName = VoiceImport.configFileName(voiceName)

            copyUriTo(uris[modelIndex], File(dir, voiceName))
            picked.config?.let { copyUriTo(uris[it], File(dir, configName)) }

            /*
             * ITS OWN DIRECTORY IS THE ONLY PLACE WORTH LOOKING, because that
             * is the rule [availableTtsVoices] pairs by. A config in the other
             * directory would not pair with the voice just written here, so
             * calling the import complete on the strength of it would announce
             * success over a row the screen immediately draws as unloadable.
             *
             * This is also what stops the prompt nagging for a file that is
             * already there: re-importing a voice whose config was imported
             * earlier finds it and says nothing.
             */
            if (File(dir, configName).exists()) VoiceImport.Result.Complete(voiceName)
            else VoiceImport.Result.NeedsConfig(voice = voiceName, config = configName)
        } catch (e: Exception) {
            logger.log(TAG, "Failed to import TTS voice: ${e.message}")
            VoiceImport.Result.Failed(e.message ?: e.toString())
        }
    }

    /**
     * Every directory a voice can be in, in the order [availableTtsVoices]
     * scans them.
     *
     * [TTS_DIR] is where imports land, but voices pushed to the external files
     * dir by hand also count — that is where the ones already on this device
     * live, and scanning only [TTS_DIR] made the panel report no voices while a
     * voice was loaded and audibly working. One definition, because a lister
     * and an importer that disagree about where voices live is the same bug
     * wearing a different hat.
     */
    private fun voiceDirs(): List<File> =
        listOfNotNull(File(appContext.filesDir, TTS_DIR), appContext.getExternalFilesDir(null))

    /** Where a voice with this exact file name is, or null if it is not here. */
    private fun locateVoice(fileName: String): File? =
        voiceDirs().map { File(it, fileName) }.firstOrNull { it.exists() }

    suspend fun deleteTtsVoice(modelPath: String, configPath: String) = withContext(Dispatchers.IO) {
        try {
            File(modelPath).delete()
            File(configPath).delete()

            if (prefs.getString(KEY_TTS_MODEL_PATH, null) == modelPath) {
                prefs.edit()
                    .remove(KEY_TTS_MODEL_PATH)
                    .remove(KEY_TTS_CONFIG_PATH)
                    .remove(KEY_TTS_MODEL_NAME)
                    .apply()
                ttsService.destroy()
                _isTtsReady.value = false
                _ttsModelName.value = null
            }
        } catch (e: Exception) {
            logger.log(TAG, "Failed to delete TTS voice: ${e.message}")
        }
    }

    private fun restoreTts() {
        val modelPath = prefs.getString(KEY_TTS_MODEL_PATH, null) ?: return
        val configPath = prefs.getString(KEY_TTS_CONFIG_PATH, null) ?: return

        val modelFile = File(modelPath)
        if (!modelFile.exists() || !File(configPath).exists()) return

        _ttsModelName.value = prefs.getString(KEY_TTS_MODEL_NAME, null) ?: modelFile.name
        scope.launch { initTts(modelPath, configPath) }
    }

    private suspend fun initTts(modelPath: String, configPath: String) {
        try {
            ttsService.init(appContext, modelPath, configPath)
            _isTtsReady.value = ttsService.isReady
        } catch (e: Exception) {
            logger.log(TAG, "Failed to init TTS: ${e.message}")
            _isTtsReady.value = false
        }
    }

    // ── STT (Whisper) ────────────────────────────────────────────────────

    fun availableSttModels(): List<File> {
        val dir = File(appContext.filesDir, STT_DIR)
        return dir.listFiles()?.filter { it.extension == "bin" } ?: emptyList()
    }

    suspend fun loadSttModel(path: String) = withContext(Dispatchers.IO) {
        // Declared outside the try so the catch can name the file that failed.
        val file = File(path)
        if (!file.exists()) return@withContext
        try {
            sttService.init(file.absolutePath)
            prefs.edit().putString(KEY_STT_PATH, file.absolutePath).apply()
            _sttModelName.value = file.name
            _sttError.value = null
        } catch (e: Exception) {
            _sttModelName.value = file.name
            _sttError.value = e.message ?: e.toString()
            logger.log(TAG, "Failed to load STT model: ${e.message}")
        }
    }

    /**
     * Import a Whisper model into [STT_DIR].
     *
     * **The name is normalised, because [availableSttModels] filters on an
     * exact `bin` extension.** Without it a model picked as `ggml-tiny-q5_1` —
     * or `ggml-tiny-q5_1.bin (1)`, which is what a browser writes on a
     * re-download — copied successfully and then appeared in no list at all:
     * the import said it worked, the screen refreshed, and the model was
     * nowhere. The voice slot has had this since its own import was rewritten;
     * this one had not, and it is the same silent failure.
     */
    suspend fun importSttModel(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val dir = File(appContext.filesDir, STT_DIR).apply { mkdirs() }
            val name = ModelFileName.withExtension(displayName(uri, "imported_stt"), ".bin")
            copyUriTo(uri, File(dir, name))
        } catch (e: Exception) {
            logger.log(TAG, "Failed to import STT model: ${e.message}")
        }
    }

    suspend fun deleteSttModel(path: String) = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (!file.exists()) return@withContext
            file.delete()

            if (prefs.getString(KEY_STT_PATH, null) == path) {
                prefs.edit().remove(KEY_STT_PATH).apply()
                sttService.destroy()
                _sttModelName.value = null
                // Deleting the file that failed clears the failure with it —
                // otherwise readiness would go on reporting a broken model that
                // no longer exists, and offer a retry with nothing to retry.
                _sttError.value = null
            }
        } catch (e: Exception) {
            logger.log(TAG, "Failed to delete STT model: ${e.message}")
        }
    }

    /**
     * Restore the chosen STT model. There is no fallback, and that is the point.
     *
     * **NOTHING IS BUNDLED ANY MORE.** A ~75 MB `ggml-tiny.en.bin` shipped in
     * assets and was unpacked here on first run, so the ears worked before the
     * user had imported anything — and it cost 75 MB in the APK *plus* 75 MB
     * unpacked, for a model that was also the one faculty nobody got to choose.
     * All three now arrive the same way: the user fetches them and imports them
     * through the system picker.
     *
     * So an absent pref means absent ears, not a default. `PetReadiness` already
     * reports `Missing(EARS)` and the first-run flow already asks for a model,
     * which is why removing this needed no new UI — the honest state was
     * already built, it just never happened.
     */
    private fun restoreStt() {
        val path = prefs.getString(KEY_STT_PATH, null) ?: return
        if (!File(path).exists()) return

        try {
            sttService.init(path)
            prefs.edit().putString(KEY_STT_PATH, path).apply()
            _sttModelName.value = File(path).name
            _sttError.value = null
        } catch (e: Exception) {
            // The name is set too, so readiness can say WHICH model failed
            // rather than just that one did. Both move together; see _sttError.
            _sttModelName.value = File(path).name
            _sttError.value = e.message ?: e.toString()
            logger.log(TAG, "Failed to auto-init STT: ${e.message}")
        }
    }

    // ── Shared file helpers ──────────────────────────────────────────────

    private fun copyUriTo(uri: Uri, dest: File) {
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
    }

    /**
     * The user-visible file name behind a picked `content://` URI. The name
     * matters: TTS pairing and the STT `.bin` filter both key off it, so
     * [fallback] must still look like the file it stands in for.
     */
    private fun displayName(uri: Uri, fallback: String): String {
        if (uri.scheme == "content") {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.let { return it }
                }
            }
        }
        return uri.path?.let { File(it).name } ?: fallback
    }
}
