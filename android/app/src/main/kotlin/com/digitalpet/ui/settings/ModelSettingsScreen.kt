package com.digitalpet.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.digitalpet.data.ModelDisplay
import com.digitalpet.data.TtsModelPair
import com.digitalpet.data.VoiceImport
import com.digitalpet.llm.LlmManager
import com.digitalpet.ui.components.core.Badge
import com.digitalpet.ui.components.settings.AlternativeRow
import com.digitalpet.ui.components.settings.SlotCard
import com.digitalpet.ui.screens.PetChatViewModel
import com.digitalpet.ui.theme.PetSpacing
import com.digitalpet.ui.theme.PetTextSize
import com.digitalpet.ui.theme.PetTheme
import java.io.File
import com.digitalpet.ui.components.core.PetTextButton

/**
 * Voice and language — Claude Design 2d. One card per slot.
 *
 * **Named by what it does, not by what it is**: Brain, Ears, Voice. The files
 * are `.gguf`, `.bin` and `.onnx`, and those names appear where they matter —
 * on the import button, so it is obvious which slot a downloaded file belongs
 * to — but a slot called "Whisper" tells you nothing about what breaks when it
 * is empty.
 *
 * **Each slot owns its own import.** The panels this replaces had three import
 * buttons at three different scroll positions, all of them opening the same
 * unfiltered picker, so a loose `.gguf` could be handed to the voice slot and
 * fail somewhere far away. Now a file can only ever be offered to the slot that
 * can use it.
 *
 * **The pencil expands, and the alternatives live inside.** A card at rest says
 * what is loaded and nothing else, because that is the only thing that matters
 * when nothing is wrong.
 *
 * **The list shows what is IN the slot, not only what could replace it.** It is
 * headed "Imported models" and includes the loaded file, marked with a ring and
 * a *Loaded* pill instead of a Load button. Excluding it — which is what the
 * first build did, under the heading "Other brain files" — meant the only place
 * that listed your models never showed you the one you were using, and a slot
 * with a single imported model showed an empty list.
 *
 * **Delete is absent on the loaded row rather than disabled**, which is the same
 * rule as reset on a living pet: a control that cannot be used is better not
 * drawn than drawn refusing. It reclaims the gigabyte a spare model costs, and
 * that is the one thing on this screen that can be done nowhere else.
 */
@Composable
fun ModelSettingsScreen(
    padding: PaddingValues,
    viewModel: PetChatViewModel = hiltViewModel(),
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PetSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(PetSpacing.s10)
    ) {
        BrainCard(viewModel)
        EarsCard(viewModel)
        VoiceCard(viewModel)
        Text(
            "The pet needs all three: it hears you with its ears, thinks with its " +
                "brain, and speaks with its voice. Any one missing and it goes quiet.",
            style = MaterialTheme.typography.bodySmall,
            fontSize = PetTextSize.t11_5,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = PetSpacing.s6, bottom = PetSpacing.scrollBottom)
        )
    }
}

@Composable
private fun BrainCard(viewModel: PetChatViewModel) {
    val state by viewModel.modelState.collectAsState()
    var files by remember { mutableStateOf(viewModel.getAvailableModels()) }
    var importing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importing = true
            viewModel.importModelFromUri(uri) {
                files = viewModel.getAvailableModels()
                importing = false
            }
        }
    }

    val loadedPath = (state as? LlmManager.ModelState.Ready)?.info?.path
    SlotCard(
        icon = Icons.Default.Psychology,
        title = "Brain",
        badge = when (state) {
            is LlmManager.ModelState.Ready -> Badge.Loaded
            is LlmManager.ModelState.Loading -> Badge.Working("loading")
            is LlmManager.ModelState.Error -> Badge.Failed
            LlmManager.ModelState.Unloaded -> Badge.None
        },
        detail = when (val s = state) {
            is LlmManager.ModelState.Ready ->
                "${s.info.fileName} · ${ModelDisplay.fileSize(s.info.fileSizeBytes)}"
            is LlmManager.ModelState.Loading -> "${(s.progress * 100).toInt()}%"
            // Verbatim, because the loader's own words are the only thing that
            // says WHICH way it failed.
            is LlmManager.ModelState.Error -> s.message
            LlmManager.ModelState.Unloaded -> "No brain loaded — the pet cannot answer"
        },
        importLabel = "Import .gguf",
        importing = importing,
        onImport = { picker.launch(arrayOf("*/*")) },
        othersLabel = "Imported models",
    ) {
        files.forEach { model ->
            AlternativeRow(
                name = model.fileName,
                // Only the parts that say something: an unreadable parameter
                // count or a version number masquerading as a quantisation are
                // left out rather than printed as "unknown · 2".
                meta = listOfNotNull(
                    ModelDisplay.parameters(model.parameterCount),
                    ModelDisplay.quantisation(model.quantization, model.fileName),
                    ModelDisplay.fileSize(model.fileSizeBytes),
                ).joinToString(" · "),
                loaded = model.path == loadedPath,
                enabled = !importing,
                onLoad = { viewModel.loadModel(model.path) },
                onDelete = {
                    viewModel.deleteModel(model.path) { files = viewModel.getAvailableModels() }
                },
            )
        }
    }
}

/**
 * The ears slot, which is the one whose extension names no format.
 *
 * `.gguf` and `.onnx` each identify a format on sight; **`.bin` identifies
 * nothing**, and the three things people arrive with under the name "Whisper" —
 * a whisper.cpp GGML `.bin`, an ONNX export, a `.gguf` — are not
 * interchangeable, while one of them is what the slot NEXT DOOR takes. So this
 * card names the format where the other two can leave it to the extension.
 *
 * **A rejected model used to report as loaded.** `loadSttModel` sets the model
 * name in its catch as well as on success — deliberately, so the failure can
 * say which file it was — and the badge keyed off that name alone. The result
 * was a green *Loaded* pill and a tidy `name · size` line over a model Whisper
 * had refused, while `PetReadiness` showed Failed on the pet surface. Two
 * screens, opposite answers, and the lie was on the one you visit in order to
 * fix it. The error now outranks the name here exactly as it does in
 * `PetReadiness.of`.
 */
@Composable
private fun EarsCard(viewModel: PetChatViewModel) {
    val name by viewModel.sttModelName.collectAsState()
    val error by viewModel.sttError.collectAsState()
    var files by remember { mutableStateOf(viewModel.getAvailableSttModels()) }
    var importing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importing = true
            viewModel.importSttModel(uri) {
                files = viewModel.getAvailableSttModels()
                importing = false
            }
        }
    }

    // A model that FAILED is not the loaded one, so nothing is marked loaded —
    // the same shape as the Brain card, whose loadedPath comes off ModelState
    // .Ready and is therefore null on Error. Without this the rejected file
    // wore the Loaded pill in the list too, which also took away its Load
    // button and left no way to retry it.
    val working = if (error == null) files.firstOrNull { it.name == name } else null
    SlotCard(
        icon = Icons.Default.Hearing,
        title = "Ears",
        badge = when {
            error != null -> Badge.Failed
            name != null -> Badge.Loaded
            else -> Badge.None
        },
        detail = when {
            // Verbatim, for the same reason the Brain card prints its loader's
            // words: a paraphrase throws away the only thing that says WHICH
            // way it failed.
            error != null -> error!!
            name != null -> listOfNotNull(
                name, working?.let { ModelDisplay.fileSize(it.length()) },
            ).joinToString(" · ")
            // Names the format, not just the failure. ".bin" is on the import
            // button and says nothing; "the .gguf the brain takes" is the
            // mistake this screen can actually cause, both slots being on it.
            else -> "No ears loaded — the pet hears you and cannot understand. " +
                "It needs a Whisper ggml-*.bin, not the .gguf the brain takes."
        },
        importLabel = "Import Whisper .bin",
        importing = importing,
        onImport = { picker.launch(arrayOf("*/*")) },
        othersLabel = "Imported models",
    ) {
        files.forEach { file: File ->
            AlternativeRow(
                name = file.name,
                meta = ModelDisplay.fileSize(file.length()),
                loaded = file.name == working?.name,
                enabled = !importing,
                onLoad = { viewModel.loadSttModel(file.absolutePath) },
                onDelete = {
                    viewModel.deleteSttModel(file.absolutePath) {
                        files = viewModel.getAvailableSttModels()
                    }
                },
            )
        }
    }
}

/**
 * The voice slot, which is the one with a second file to worry about.
 *
 * **It lists the `.onnx` and pairs the `.json` by name**, per the design, rather
 * than asking for two files. A voice whose config is missing is *shown* and
 * marked unloadable — it used to be filtered out of the list entirely, so an
 * unusable `.onnx` looked exactly like no voice at all.
 *
 * **The import takes both files in one visit to the picker.** It still needs
 * both — a config that is not on the device cannot be conjured from one that is
 * — but it used to ask twice, chaining a second picker straight off the first.
 * SAF gives no way to title a picker, so that second one opened in the same
 * folder looking pixel-identical to the first, with nothing anywhere saying a
 * `.json` was what it wanted. It read as a failure rather than as step 2, and
 * backing out of it silently discarded the `.onnx` already picked. Now the
 * picker allows multiple selection — the two files are adjacent in the folder
 * they were downloaded into — and the button says both extensions.
 *
 * **When a half really is missing, the app says so before opening anything.**
 * The picker is the one surface this app cannot write on, so an explanation
 * placed there is an explanation nobody gets; it goes in a dialog that names
 * the exact file — `en_GB-alba-medium.onnx.json`, not "the config" — and only
 * then offers to open the picker. Declining leaves the imported half listed and
 * flagged rather than losing it, which is the same rule as the row above.
 *
 * **A `.json` on its own is a real import**, not an error. It is the way out of
 * a row flagged as having no config: the file names the voice it belongs to, so
 * it is filed beside it. Before this, that row could only be cleared by deleting
 * the voice and fetching both files again.
 */
@Composable
private fun VoiceCard(viewModel: PetChatViewModel) {
    val ready by viewModel.isTtsReady.collectAsState()
    val name by viewModel.ttsModelName.collectAsState()
    var voices by remember { mutableStateOf<List<TtsModelPair>>(emptyList()) }
    var importing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { voices = viewModel.getAvailableTtsModels() }

    var prompt by remember { mutableStateOf<VoiceImport.Prompt?>(null) }

    // ONE picker, both files. Piper needs the pair and there is no point
    // pretending otherwise — but asking for them one after the other put the
    // second request somewhere the app cannot write on, which is the system
    // file picker. Multiple selection puts it on the button instead, and
    // anything still missing afterwards is named in [prompt].
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            importing = true
            viewModel.importTtsModel(uris) { result ->
                voices = viewModel.getAvailableTtsModels()
                importing = false
                // Null on success: the voice is in the list and a dialog saying
                // so would be a tap spent on news the card already carries.
                prompt = VoiceImport.prompt(result)
            }
        }
    }

    prompt?.let { p ->
        AlertDialog(
            onDismissRequest = { prompt = null },
            title = { Text(p.title) },
            text = { Text(p.body) },
            confirmButton = {
                PetTextButton(
                    onClick = {
                        prompt = null
                        // Only NOW is a second picker opened, and only because
                        // the sentence above it said which file it wants.
                        if (p.action != null) picker.launch(arrayOf("*/*"))
                    },
                ) { Text(p.action ?: "OK") }
            },
            // Absent rather than disabled when there is nothing to go and get —
            // the same rule as delete on a loaded row.
            dismissButton = p.action?.let {
                {
                    PetTextButton(
                        onClick = { prompt = null },
                    ) { Text("Not now") }
                }
            },
        )
    }

    val loaded = voices.firstOrNull { it.name == name }
    SlotCard(
        icon = Icons.Default.RecordVoiceOver,
        title = "Voice",
        badge = if (ready && name != null) Badge.Loaded else Badge.None,
        // `<file> · <size>`, as the other two slots read. The old line added
        // "paired with <config>", which is information the row cannot lack: a
        // voice without its config cannot be loaded at all, so saying so on the
        // loaded one is a sentence that is always true and never useful.
        detail = name?.let { n ->
            listOfNotNull(
                ModelDisplay.voiceName(n),
                loaded?.let { ModelDisplay.fileSize(it.modelSizeMb * 1_000_000) },
            ).joinToString(" · ")
        } ?: "No voice loaded — the pet answers in silence",
        // BOTH extensions, because both are what it takes. The button said
        // ".onnx" while the flow demanded two files, so the only place that
        // could have set the expectation was quietly setting the wrong one.
        importLabel = "Import .onnx + .json",
        importing = importing,
        onImport = { picker.launch(arrayOf("*/*")) },
        othersLabel = "Imported models",
    ) {
        voices.forEach { voice ->
            val problem = ModelDisplay.voiceProblem(voice)
            AlternativeRow(
                name = ModelDisplay.voiceName(voice.name),
                meta = problem ?: "${voice.modelSizeMb} MB · config found",
                metaIsProblem = problem != null,
                // A voice with no config cannot be loaded, and the row says why
                // rather than offering a button that would fail.
                loaded = voice.name == name,
                enabled = !importing && voice.isUsable,
                onLoad = { viewModel.loadTtsModel(voice.modelPath, voice.configPath!!) },
                onDelete = {
                    viewModel.deleteTtsModel(voice.modelPath, voice.configPath ?: "") {
                        voices = viewModel.getAvailableTtsModels()
                    }
                },
            )
        }
    }
}
