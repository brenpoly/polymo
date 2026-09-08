package com.digitalpet.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides whether an imported model is ever seen again.
 *
 * Both listers filter on an exact, lower-case extension, so a name that does
 * not end in one produces a completely silent failure: the copy succeeds, the
 * screen refreshes, and the model is in no list. These are the names that
 * actually turn up.
 */
class ModelFileNameTest {

    @Test
    fun `a name that already ends in the extension is left alone`() {
        assertEquals(
            "ggml-tiny-q5_1.bin",
            ModelFileName.withExtension("ggml-tiny-q5_1.bin", ".bin"),
        )
        assertEquals(
            "en_GB-alba-medium.onnx",
            ModelFileName.withExtension("en_GB-alba-medium.onnx", ".onnx"),
        )
    }

    @Test
    fun `a name with no extension gains one`() {
        assertEquals("ggml-tiny-q5_1.bin", ModelFileName.withExtension("ggml-tiny-q5_1", ".bin"))
    }

    @Test
    fun `a re-download keeps its browser suffix AND becomes visible`() {
        // `File("x.bin (1)").extension` is "bin (1)", so this imported and then
        // appeared nowhere. The result is ugly; the alternative is invisible.
        assertEquals(
            "ggml-tiny-q5_1.bin (1).bin",
            ModelFileName.withExtension("ggml-tiny-q5_1.bin (1)", ".bin"),
        )
    }

    @Test
    fun `the check is case-SENSITIVE, and that is what makes the file visible`() {
        // The listers compare File.extension against a lower-case literal, so
        // matching case-insensitively here would leave these named as picked —
        // imported, on disk, and in no list. The doubled extension is correct.
        assertEquals("GGML-TINY.BIN.bin", ModelFileName.withExtension("GGML-TINY.BIN", ".bin"))
        assertEquals("Voice.ONNX.onnx", ModelFileName.withExtension("Voice.ONNX", ".onnx"))
    }

    @Test
    fun `only a TRAILING extension counts`() {
        // A model whose name contains the extension earlier still needs one on
        // the end, or the lister will not see it.
        assertEquals("bin-tiny.bin", ModelFileName.withExtension("bin-tiny", ".bin"))
        assertEquals("ggml.bin.q5.bin", ModelFileName.withExtension("ggml.bin.q5", ".bin"))
    }

    @Test
    fun `the voice slot goes through the same rule`() {
        // VoiceImport delegates here, so the two slots cannot drift apart.
        assertEquals(
            ModelFileName.withExtension("custom-voice", ".onnx"),
            VoiceImport.voiceFileName("custom-voice"),
        )
        assertEquals("ko_golden_final.onnx", VoiceImport.voiceFileName("ko_golden_final"))
    }
}
