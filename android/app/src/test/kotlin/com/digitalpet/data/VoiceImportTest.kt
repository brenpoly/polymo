package com.digitalpet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sorting a multiple selection into the voice and its config.
 *
 * This exists because the thing it replaced could not be tested at all: the
 * import used to take two files in two chained pickers, so "which file is
 * which" was answered by *when* it arrived. One picker means both arrive at
 * once, in the provider's order rather than the tap order, and the names are
 * the only thing left that knows.
 *
 * Every case here is a real shape a Piper download comes in.
 */
class VoiceImportTest {

    private fun classify(vararg names: String) = VoiceImport.classify(names.toList())

    @Test
    fun `the pair, picked in either order`() {
        // SAF returns a multiple selection in the provider's order, which is
        // usually alphabetical — and `x.onnx.json` sorts AFTER `x.onnx`, so the
        // second of these is the order the Downloads folder actually produces.
        val forwards = classify("en_GB-alba-medium.onnx", "en_GB-alba-medium.onnx.json")
        assertEquals(0, forwards.model)
        assertEquals(1, forwards.config)

        val backwards = classify("en_GB-alba-medium.onnx.json", "en_GB-alba-medium.onnx")
        assertEquals(1, backwards.model)
        assertEquals(0, backwards.config)
    }

    @Test
    fun `the voice alone imports as a voice with no config`() {
        // The case the whole change is about: one file picked, and it is kept
        // rather than discarded. The row that results says what is missing.
        val one = classify("en_US-amy-low.onnx")
        assertEquals(0, one.model)
        assertNull(one.config)
    }

    @Test
    fun `a config alone is not mistaken for a voice`() {
        // There is nothing to import, and importing the JSON as `x.onnx` would
        // produce a listed voice that fails at load. Better to take nothing.
        val one = classify("en_US-amy-low.onnx.json")
        assertNull(one.model)
        assertEquals(0, one.config)
    }

    @Test
    fun `two configs and no voice yields no voice`() {
        // The naive "anything that is not the config is the voice" rule takes
        // the SECOND json here and imports it as an .onnx. Nothing about the
        // result looks wrong until Piper is asked to speak.
        val two = classify("a.onnx.json", "b.onnx.json")
        assertNull(two.model)
        assertEquals(0, two.config)
    }

    @Test
    fun `only a TRAILING extension counts`() {
        // A voice whose name contains "json", and a config whose name contains
        // "onnx" — which every Piper config does, being `<name>.onnx.json`.
        // Matching anywhere in the string swaps these two.
        val p = classify("json-tts-medium.onnx", "json-tts-medium.onnx.json")
        assertEquals(0, p.model)
        assertEquals(1, p.config)
    }

    @Test
    fun `a renamed download is still a voice`() {
        // A browser that stripped the extension, or a file saved as
        // "voice(1)". The repository puts `.onnx` back on; refusing it here
        // would reject a file that works.
        val p = classify("en_GB-alba-medium")
        assertEquals(0, p.model)
        assertNull(p.config)

        val paired = classify("en_GB-alba-medium", "en_GB-alba-medium.onnx.json")
        assertEquals(0, paired.model)
        assertEquals(1, paired.config)
    }

    @Test
    fun `a real onnx outranks an unnamed file beside it`() {
        // The fallback must not win while a genuine `.onnx` is in the list, or
        // picking three files imports the wrong one.
        val p = classify("readme", "en_GB-alba-medium.onnx", "en_GB-alba-medium.onnx.json")
        assertEquals(1, p.model)
        assertEquals(2, p.config)
    }

    @Test
    fun `extensions match regardless of case`() {
        val p = classify("Voice.ONNX", "Voice.ONNX.JSON")
        assertEquals(0, p.model)
        assertEquals(1, p.config)
    }

    @Test
    fun `nothing picked is not a crash`() {
        // The picker returns an empty list when it is dismissed.
        val none = VoiceImport.classify(emptyList())
        assertNull(none.model)
        assertNull(none.config)
    }

    // ---- the names a voice is stored under ---------------------------------

    @Test
    fun `a picked voice keeps its name and gains the extension only if it lacks one`() {
        assertEquals(
            "en_GB-alba-medium.onnx",
            VoiceImport.voiceFileName("en_GB-alba-medium.onnx"),
        )
        assertEquals("en_GB-alba-medium.onnx", VoiceImport.voiceFileName("en_GB-alba-medium"))
    }

    @Test
    fun `normalisation is case-SENSITIVE, and that is what makes the file visible`() {
        // `availableTtsVoices` lists a file only when its extension is exactly
        // "onnx". Matching case-insensitively here would leave this named
        // "Voice.ONNX" — imported, on disk, and in no list. The doubled
        // extension is ugly and correct.
        assertEquals("Voice.ONNX.onnx", VoiceImport.voiceFileName("Voice.ONNX"))
    }

    @Test
    fun `a config is named after the voice it sits beside`() {
        assertEquals(
            "en_GB-alba-medium.onnx.json",
            VoiceImport.configFileName("en_GB-alba-medium.onnx"),
        )
    }

    @Test
    fun `a config names the voice it belongs to`() {
        // This is what lets a lone .json be imported: the file says where it
        // goes, so a voice already on the device gets its config back.
        assertEquals(
            "en_GB-alba-medium.onnx",
            VoiceImport.voiceFileNameForConfig("en_GB-alba-medium.onnx.json"),
        )
    }

    @Test
    fun `a config that dropped the onnx from its name still finds the voice`() {
        // Some download flows save `x.onnx.json` as `x.json`.
        assertEquals("en_US-amy-low.onnx", VoiceImport.voiceFileNameForConfig("en_US-amy-low.json"))
    }

    @Test
    fun `the two halves resolve to the SAME name in any case`() {
        // The load-bearing property: a voice and its config picked in odd case
        // must land on each other rather than beside each other. If these two
        // disagree the pair is imported as two files that never pair.
        val fromVoice = VoiceImport.voiceFileName("V.ONNX")
        val fromConfig = VoiceImport.voiceFileNameForConfig("V.ONNX.JSON")
        assertEquals(fromVoice, fromConfig)
        assertEquals(
            VoiceImport.configFileName(fromVoice),
            VoiceImport.configFileName(fromConfig),
        )
    }

    // ---- what gets said afterwards -----------------------------------------

    @Test
    fun `a complete import says nothing at all`() {
        // The card refreshes and the voice is in it. A dialog here is a tap
        // spent on news the screen already carries.
        assertNull(VoiceImport.prompt(VoiceImport.Result.Complete("en_GB-alba-medium.onnx")))
    }

    @Test
    fun `a missing config is NAMED, not described`() {
        // ".json" sends someone hunting; the full filename is a thing to look
        // for in the folder they are already standing in.
        val p = VoiceImport.prompt(
            VoiceImport.Result.NeedsConfig(
                voice = "en_GB-alba-medium.onnx",
                config = "en_GB-alba-medium.onnx.json",
            )
        )!!
        assertTrue(p.body, p.body.contains("en_GB-alba-medium.onnx.json"))
        assertEquals("Choose the .json", p.action)
    }

    @Test
    fun `a missing voice is named too, and asks for the other extension`() {
        val p = VoiceImport.prompt(
            VoiceImport.Result.NeedsVoice(
                voice = "en_US-amy-low.onnx",
                config = "en_US-amy-low.onnx.json",
            )
        )!!
        assertTrue(p.body, p.body.contains("en_US-amy-low.onnx"))
        assertEquals("Choose the .onnx", p.action)
    }

    @Test
    fun `an outcome with nothing to fetch offers no picker`() {
        // A null action is what makes the dialog draw one button instead of
        // two. Offering "Choose the .json" after a failed copy would send
        // someone back to the picker to repeat exactly what just broke.
        assertNull(VoiceImport.prompt(VoiceImport.Result.NothingUsable)!!.action)
        assertNull(VoiceImport.prompt(VoiceImport.Result.Failed("EACCES"))!!.action)
    }

    @Test
    fun `a failure repeats the reason it was given`() {
        // A paraphrase of a copy failure throws away the only thing that says
        // which failure it was.
        val p = VoiceImport.prompt(VoiceImport.Result.Failed("ENOSPC: no space left"))!!
        assertTrue(p.body, p.body.contains("ENOSPC: no space left"))
    }

    @Test
    fun `every outcome except success says something`() {
        // The change this makes: a half-done import used to reopen the picker
        // with no explanation and a failed one went to the log, so from the
        // outside all three outcomes were the same event.
        val outcomes = listOf(
            VoiceImport.Result.NeedsConfig("v.onnx", "v.onnx.json"),
            VoiceImport.Result.NeedsVoice("v.onnx", "v.onnx.json"),
            VoiceImport.Result.NothingUsable,
            VoiceImport.Result.Failed("boom"),
        )
        outcomes.forEach { r ->
            val p = VoiceImport.prompt(r)
            assertTrue("$r said nothing", p != null && p.body.isNotBlank() && p.title.isNotBlank())
        }
    }
}
