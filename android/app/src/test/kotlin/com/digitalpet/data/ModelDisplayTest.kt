package com.digitalpet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How the Voice and language cards describe a model file.
 *
 * The sizes here are the Claude Design 2d mockup's own, because a size that
 * disagrees with the page the file was downloaded from is worse than no size:
 * it is read at exactly the moment somebody is deciding which model to keep.
 */
class ModelDisplayTest {

    // ---- sizes, against the design's own figures ---------------------------

    @Test
    fun `megabytes below a gigabyte, with no decimal`() {
        assertEquals("148 MB", ModelDisplay.fileSize(148_000_000))
        assertEquals("75 MB", ModelDisplay.fileSize(75_000_000))
        assertEquals("986 MB", ModelDisplay.fileSize(986_000_000))
        assertEquals("28 MB", ModelDisplay.fileSize(28_000_000))
    }

    @Test
    fun `gigabytes with one decimal`() {
        assertEquals("1.6 GB", ModelDisplay.fileSize(1_600_000_000))
        assertEquals("1.4 GB", ModelDisplay.fileSize(1_400_000_000))
        assertEquals("1.0 GB", ModelDisplay.fileSize(1_000_000_000))
    }

    @Test
    fun `DECIMAL units, so the screen agrees with where the file came from`() {
        // 1.6e9 bytes is "1.6 GB" everywhere a model is published. Binary units
        // would render the same file as 1.5 and make this screen the only place
        // that disagrees — which is the one thing a size must not do.
        assertEquals("1.6 GB", ModelDisplay.fileSize(1_600_000_000))
        assertEquals("1 MB", ModelDisplay.fileSize(1_000_000))
    }

    @Test
    fun `tenths round to nearest rather than truncating`() {
        // The first version added a nudge that was a thousand times too small,
        // so it truncated while looking exactly like rounding. It still
        // compiled and still passed every whole-number case above.
        assertEquals("1.7 GB", ModelDisplay.fileSize(1_650_000_000))
        assertEquals("2.5 GB", ModelDisplay.fileSize(2_450_000_001))
        assertEquals("2.0 GB", ModelDisplay.fileSize(1_990_000_000))
    }

    @Test
    fun `the boundary between the two units is a thousand megabytes`() {
        assertEquals("999 MB", ModelDisplay.fileSize(999_999_999))
        assertEquals("1.0 GB", ModelDisplay.fileSize(1_000_000_000))
    }

    @Test
    fun `a missing or nonsense size is not rendered as negative`() {
        assertEquals("0 MB", ModelDisplay.fileSize(0))
        assertEquals("0 MB", ModelDisplay.fileSize(-1))
        assertEquals("0 MB", ModelDisplay.fileSize(-5_000_000_000))
    }

    // ---- the voice that cannot run -----------------------------------------

    private fun voice(config: String?) = TtsModelPair(
        name = "en_GB-alba-medium.onnx",
        modelPath = "/x/en_GB-alba-medium.onnx",
        configPath = config,
        modelSizeMb = 60,
    )

    @Test
    fun `a paired voice has nothing wrong with it`() {
        assertNull(ModelDisplay.voiceProblem(voice("/x/en_GB-alba-medium.onnx.json")))
    }

    @Test
    fun `an unpaired voice names the file it is missing`() {
        // "Invalid" would be true and useless. The fix is to go and fetch one
        // specific file, so the message is that file.
        assertEquals(
            "No .onnx.json beside it — cannot be loaded",
            ModelDisplay.voiceProblem(voice(null)),
        )
    }

    @Test
    fun `a voice is named without the extension every voice shares`() {
        // The row is long and the extension never varies; the slot's own import
        // button already says ".onnx".
        assertEquals("en_GB-alba-medium", ModelDisplay.voiceName("en_GB-alba-medium.onnx"))
        assertEquals("en_US-amy-low", ModelDisplay.voiceName("en_US-amy-low.onnx"))
    }

    @Test
    fun `only a trailing extension is stripped, and only once`() {
        // A voice whose NAME contains the string, and one that arrived without
        // the suffix at all, must both survive intact.
        assertEquals("onnx-test", ModelDisplay.voiceName("onnx-test.onnx"))
        assertEquals("plain-name", ModelDisplay.voiceName("plain-name"))
        assertEquals("v.onnx", ModelDisplay.voiceName("v.onnx.onnx"))
    }

    @Test
    fun `usability is the presence of the config and nothing else`() {
        // The listing shows unusable voices rather than hiding them, so this is
        // the flag the Load button is disabled by. It used to be the condition
        // for the voice existing at all.
        assertEquals(true, voice("/x/c.json").isUsable)
        assertEquals(false, voice(null).isUsable)
    }

    // ---- parameter counts ---------------------------------------------------

    @Test
    fun `a raw parameter total becomes something a person says`() {
        assertEquals("2.6 B", ModelDisplay.parameters("2614341888"))
        assertEquals("1.5 B", ModelDisplay.parameters("1500000000"))
        assertEquals("500 M", ModelDisplay.parameters("500000000"))
    }

    @Test
    fun `a count already in human form is left alone`() {
        // Some GGUFs declare general.parameter_count as text. Reformatting it
        // would be inventing precision the file did not offer.
        assertEquals("1.5 B", ModelDisplay.parameters("1.5 B"))
        assertEquals("E2B", ModelDisplay.parameters("E2B"))
    }

    @Test
    fun `nothing usable becomes nothing at all, never the word unknown`() {
        // The row leaves the field out. "unknown · 2 · 807 MB" spends two thirds
        // of its width saying nothing, which is what this replaced.
        assertNull(ModelDisplay.parameters(null))
        assertNull(ModelDisplay.parameters(""))
        assertNull(ModelDisplay.parameters("unknown"))
        assertNull(ModelDisplay.parameters("UNKNOWN"))
        assertNull(ModelDisplay.parameters("0"))
    }

    @Test
    fun `billions round to a tenth`() {
        assertEquals("2.6 B", ModelDisplay.parameters("2649999999"))
        assertEquals("2.7 B", ModelDisplay.parameters("2650000000"))
        assertEquals("1.0 B", ModelDisplay.parameters("1000000000"))
    }

    // ---- quantisation -------------------------------------------------------

    @Test
    fun `the scheme comes from the filename, which is where it actually lives`() {
        // The JNI reads general.quantization_version — 2 for every modern GGUF,
        // because it is the format version, not the scheme. That is why rows
        // were showing a bare "2".
        assertEquals(
            "Q5_K_M",
            ModelDisplay.quantisation("2", "google_gemma-4-E2B-it-Q5_K_M.gguf"),
        )
        assertEquals(
            "Q4_K_M",
            ModelDisplay.quantisation("14", "llama-3.2-1b-instruct-q4_k_m.gguf"),
        )
    }

    @Test
    fun `metadata is used when it actually looks like a scheme`() {
        assertEquals("Q8_0", ModelDisplay.quantisation("Q8_0", "model.gguf"))
        assertEquals("Q8_0", ModelDisplay.quantisation("q8_0", "model.gguf"))
    }

    @Test
    fun `the newer IQ schemes are recognised too`() {
        assertEquals("IQ3_XXS", ModelDisplay.quantisation(null, "mixtral-IQ3_XXS.gguf"))
    }

    @Test
    fun `a name with no scheme in it yields nothing rather than a guess`() {
        assertNull(ModelDisplay.quantisation("2", "some-model.gguf"))
        assertNull(ModelDisplay.quantisation(null, "some-model.gguf"))
    }
}
