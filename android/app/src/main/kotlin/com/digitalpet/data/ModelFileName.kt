package com.digitalpet.data

/**
 * The name an imported model file is **stored** under.
 *
 * Every slot's lister filters on an exact, lower-case extension —
 * `it.extension == "onnx"`, `it.extension == "bin"` — so a file whose picked
 * name does not end in that extension imports successfully, sits on disk, and
 * appears in no list. That failure is completely silent: the copy works, the
 * screen refreshes, and the model is simply not there.
 *
 * It is a real shape rather than a hypothetical one. A browser writes
 * `ggml-tiny-q5_1.bin (1)` on a re-download, which has the extension `bin (1)`;
 * a file saved from a chat app can arrive with no extension at all.
 *
 * One definition because the rule is subtle enough to get wrong twice — and it
 * *was* only applied to voices, which is why the ears slot could swallow a
 * model whole.
 */
object ModelFileName {

    /**
     * [picked] with [extension] on the end, unless it is already there.
     *
     * **The test is deliberately case-SENSITIVE**, and that is the whole point
     * rather than an oversight. The listers compare `File.extension` against a
     * lower-case literal, so a file picked as `Voice.ONNX` has to become
     * `Voice.ONNX.onnx` to be visible at all. Matching case-insensitively here
     * would leave it named `Voice.ONNX` — imported, on disk, and in no list,
     * which is exactly the failure this exists to prevent. The doubled
     * extension is ugly and correct.
     *
     * Classification is a separate question and is *not* case-sensitive: see
     * [VoiceImport.classify], which has to recognise what a person picked
     * rather than decide what to call it.
     */
    fun withExtension(picked: String, extension: String): String =
        if (picked.endsWith(extension)) picked else "$picked$extension"
}
