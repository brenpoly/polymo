package com.digitalpet.data

/**
 * Importing a Piper voice, minus the filesystem: which picked file is which,
 * what each half is stored as, and what to say when only one of them arrived.
 *
 * A voice is `<name>.onnx` plus `<name>.onnx.json`, and the import needs both.
 * It used to ask for them in two visits to the file picker, the second chained
 * straight off the first — and SAF gives no way to title a picker, so step 2
 * arrived in the same folder looking pixel-identical to step 1, which reads as
 * "that didn't take" rather than as a second step. The picker is now opened
 * once with multiple selection, and when a half is genuinely missing the app
 * says so **in its own words** before opening anything, because the picker is
 * the one surface it cannot write on.
 *
 * Everything here is a pure function of file names, which is the point: the
 * pairing rule is the thing that can be quietly wrong, and it is checkable
 * without a device.
 */
object VoiceImport {

    /**
     * Indices into the list that was classified, or null where nothing picked
     * fits that half.
     *
     * Indices rather than names because the caller has to get back to the
     * `Uri` it resolved the name from — the name alone cannot be read.
     */
    data class Picked(val model: Int?, val config: Int?)

    /**
     * **Extensions decide, and only a trailing one counts.** `en_GB-alba-medium.onnx.json`
     * ends in `.json` and is the config; a voice with `json` elsewhere in its
     * name is still a voice.
     *
     * **Pick order is not selection order.** SAF returns a multiple selection
     * in the provider's order, not the order the files were tapped, so "the
     * first one is the voice" would be right roughly half the time.
     *
     * **A renamed download is still a voice.** Anything left over that is not a
     * config is taken as the model, because [voiceFileName] puts the extension
     * back on and the alternative is refusing a file that is perfectly good.
     * Two configs and no model is the one case with nothing to take: neither of
     * them is a voice, and guessing would import a JSON file as one.
     */
    fun classify(names: List<String>): Picked {
        val config = names.indexOfFirst { it.isConfig() }.takeIf { it >= 0 }
        val model = names.indexOfFirst { it.endsWith(".onnx", ignoreCase = true) }
            .takeIf { it >= 0 }
            ?: names.indices.firstOrNull { it != config && !names[it].isConfig() }
        return Picked(model, config)
    }

    // ---- the names a voice is STORED under ---------------------------------

    /**
     * What a picked voice file is saved as.
     *
     * [ModelFileName.withExtension] carries the rule and the reason it is
     * case-sensitive where [classify] is not. The ears slot needs the same
     * rule, and had gone without it.
     */
    fun voiceFileName(picked: String): String =
        ModelFileName.withExtension(picked, ".onnx")

    /** The config that belongs beside a stored voice: `x.onnx` → `x.onnx.json`. */
    fun configFileName(voiceFileName: String): String = "$voiceFileName.json"

    /**
     * The voice a config belongs to, read off the config's own name:
     * `x.onnx.json` → `x.onnx`.
     *
     * This is what lets a lone `.json` be imported at all. Piper names a config
     * after its voice, so the file says where it goes — and a voice already on
     * the device with no config is exactly the row the screen flags as
     * unloadable. Without this the only way out of that row was to delete the
     * voice and fetch both files again.
     *
     * Round-trips with [voiceFileName] for any case: a voice picked as
     * `V.ONNX` and a config picked as `V.ONNX.JSON` both resolve to
     * `V.ONNX.onnx`, so the two halves land on each other rather than beside
     * each other.
     */
    fun voiceFileNameForConfig(configName: String): String {
        val stem = if (configName.isConfig()) configName.dropLast(CONFIG_SUFFIX.length)
                   else configName
        return voiceFileName(stem)
    }

    // ---- what happened, and what to say about it ---------------------------

    /** The outcome of an import, in terms of what is now on the device. */
    sealed interface Result {
        /** Both halves are in place. The voice can be loaded; nothing needs saying. */
        data class Complete(val voice: String) : Result

        /** The voice landed and is listed, flagged; [config] is what it still needs. */
        data class NeedsConfig(val voice: String, val config: String) : Result

        /** The config landed; [voice] is the file it is waiting for. */
        data class NeedsVoice(val voice: String, val config: String) : Result

        /** Files were picked and none of them was a voice or a config. */
        data object NothingUsable : Result

        /** The copy itself failed. */
        data class Failed(val reason: String) : Result
    }

    /** A dialog: what to say, and the picker-opening action to offer, if any. */
    data class Prompt(val title: String, val body: String, val action: String?)

    /**
     * What to tell someone after an import, or null when there is nothing to
     * tell them.
     *
     * **Success is silent.** The card refreshes and the voice is in the list;
     * a dialog saying so would be a tap spent on news the screen already
     * carries.
     *
     * **Every other outcome says something**, which is the change. A half-done
     * import used to reopen the file picker with no explanation, and a failed
     * one went to the log — so from the outside, "it worked", "it needs one
     * more file" and "it broke" were the same event: a picker, or nothing.
     *
     * The missing file is named in full rather than described. `.json` sends
     * someone hunting; `en_GB-alba-medium.onnx.json` is a thing to look for in
     * a folder they are already standing in.
     */
    fun prompt(result: Result): Prompt? = when (result) {
        is Result.Complete -> null

        is Result.NeedsConfig -> Prompt(
            title = "One more file",
            body = "${result.voice} is in. Piper also needs ${result.config} — it " +
                "carries the sample rate and the phoneme map, and the voice cannot " +
                "speak without it. It sits beside the voice wherever you downloaded " +
                "it. Until it arrives the voice is listed but cannot be loaded.",
            action = "Choose the .json",
        )

        is Result.NeedsVoice -> Prompt(
            title = "One more file",
            body = "${result.config} is in, but the voice itself is not on this " +
                "phone. Piper needs ${result.voice} beside it — that is the large " +
                "file of the two, and it sits next to the config wherever you " +
                "downloaded it.",
            action = "Choose the .onnx",
        )

        Result.NothingUsable -> Prompt(
            title = "Nothing to import",
            body = "A Piper voice is a .onnx file with a .onnx.json beside it, and " +
                "neither was among what you picked. The two sit next to each other " +
                "wherever you downloaded the voice, and you can select both at once.",
            action = null,
        )

        is Result.Failed -> Prompt(
            title = "The import did not finish",
            body = "Copying the voice onto this phone failed: ${result.reason}. " +
                "Anything that did copy is in the list above, and picking the files " +
                "again is safe — an import overwrites what is already there.",
            action = null,
        )
    }

    private const val CONFIG_SUFFIX = ".json"

    private fun String.isConfig() = endsWith(CONFIG_SUFFIX, ignoreCase = true)
}
