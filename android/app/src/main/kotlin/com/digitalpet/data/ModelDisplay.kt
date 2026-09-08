package com.digitalpet.data

/**
 * How model files are described on the Local AI models screen.
 *
 * Small, but it is the part of that screen that can be quietly wrong: a file
 * size is the only thing distinguishing two builds of the same model, and it is
 * the number someone uses to decide what to delete when a phone is full.
 */
object ModelDisplay {

    /**
     * A file size as the cards state it: `148 MB`, `1.6 GB`.
     *
     * **Decimal units, matching the design's own figures** — 2d shows a
     * 1,600,000,000-byte GGUF as "1.6 GB", which is what every download page and
     * every model card says it is. Binary units would render the same file as
     * "1.5 GB" and make the screen disagree with the place the file came from,
     * which is precisely when a size is being read.
     *
     * Gigabytes get one decimal because the difference between 1.6 and 2.4 GB
     * decides whether a model fits; megabytes get none, because nobody has ever
     * needed to know a Whisper model is 148.3 MB.
     */
    fun fileSize(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        val mb = safe / 1_000_000
        if (mb < 1_000) return "$mb MB"
        // Round to the nearest tenth of a GB: half a tenth is 50 MB, and adding
        // it before an integer divide is what turns truncation into rounding.
        // Written out because getting the magnitude of that constant wrong is
        // invisible — it still compiles, still looks like rounding, and quietly
        // truncates instead. It did, on the first pass.
        val tenths = (safe + 50_000_000) / 100_000_000
        return "${tenths / 10}.${tenths % 10} GB"
    }

    /**
     * What is wrong with a voice, or null when nothing is.
     *
     * Piper needs `<name>.onnx` and `<name>.onnx.json` side by side. The message
     * names the file that is missing rather than saying "invalid", because the
     * fix is to go and fetch that exact file — and it is the sentence the design
     * puts on the row.
     */
    fun voiceProblem(voice: TtsModelPair): String? =
        if (voice.isUsable) null else "No .onnx.json beside it — cannot be loaded"

    /**
     * A voice's name without the extension every voice shares.
     *
     * `en_GB-alba-medium.onnx` is a filename; `en_GB-alba-medium` is a voice.
     * The extension is already stated by the slot's own import button, so
     * repeating it on every row spends width on the one part that never varies —
     * and these names are long enough to truncate on a phone.
     */
    fun voiceName(fileName: String): String = fileName.removeSuffix(".onnx")

    /**
     * A parameter count as a person says it: `2.6 B`, `1.5 B`, `500 M`.
     *
     * The GGUF metadata gives a raw total — `2614341888` — which is true and
     * unreadable, and the design's row wants "1.5 B". Anything already in that
     * shape is passed through, so a model that declares `general.parameter_count`
     * as text keeps its own wording.
     *
     * **Returns null rather than "unknown"**, so the caller can leave the field
     * out entirely. A row reading `unknown · 2 · 807 MB` is worse than one
     * reading `807 MB`: it spends two thirds of its width saying nothing.
     */
    fun parameters(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.equals("unknown", ignoreCase = true)) return null
        val count = text.toLongOrNull() ?: return text  // already human, e.g. "1.5 B"
        return when {
            count <= 0L -> null
            count >= 1_000_000_000L -> "${tenths(count, 1_000_000_000L)} B"
            count >= 1_000_000L -> "${count / 1_000_000} M"
            else -> "$count"
        }
    }

    private fun tenths(value: Long, unit: Long): String {
        val t = (value * 10 + unit / 2) / unit
        return "${t / 10}.${t % 10}"
    }

    /**
     * The quantisation scheme, e.g. `Q4_K_M`.
     *
     * **The metadata cannot be trusted for this and the filename can.** The JNI
     * reads `general.quantization_version` — which is 2 for every modern GGUF,
     * being the format version rather than the scheme — and falls back to
     * `general.file_type`, an enum it stringifies as a bare integer. Either way
     * the answer arrives as a number that means nothing to a reader, which is
     * why rows were showing `· 2 ·`.
     *
     * Every GGUF in circulation carries the scheme in its filename, because that
     * is how people tell two builds of one model apart. So the name is the
     * source, and a metadata value is used only when it already looks like a
     * scheme rather than a version number.
     */
    fun quantisation(raw: String?, fileName: String): String? {
        val fromMeta = raw?.trim().orEmpty()
        if (QUANT.matches(fromMeta)) return fromMeta.uppercase()
        return QUANT.find(fileName.uppercase())?.value
    }

    /** `Q4_K_M`, `Q5_1`, `Q8_0`, `IQ3_XXS` — a Q, digits, then optional suffixes. */
    private val QUANT = Regex("I?Q\\d+(?:_[A-Z0-9]+)*", RegexOption.IGNORE_CASE)
}
