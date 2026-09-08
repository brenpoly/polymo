package com.digitalpet.pet

import com.digitalpet.llm.LlmManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Axis C — whether the pet can answer at all.
 *
 * The reason this is worth pinning: its failure mode is *silence*. With a model
 * missing the pet listens, understands, and says nothing, and the only evidence
 * is a log line. Every test here is really the same test — that the app says
 * which of the three is missing instead of looking broken.
 */
class PetReadinessTest {

    private val ready = LlmManager.ModelState.Ready(
        com.digitalpet.llm.ModelInfo(
            path = "/x", fileName = "llama.gguf", parameterCount = "1B",
            quantization = "Q4_K_M", architecture = "llama", fileSizeBytes = 1L
        )
    )
    private val unloaded = LlmManager.ModelState.Unloaded

    private fun of(
        llm: LlmManager.ModelState = ready,
        tts: Boolean = true,
        stt: String? = "ggml-tiny.en.bin",
        sttError: String? = null,
    ) = PetReadiness.of(llm, tts, stt, sttError)

    // ---- the one that matters ----------------------------------------------

    @Test
    fun `all three loaded says nothing at all`() {
        // Absent, not a green tick. A working product should not spend a row of
        // its home screen telling you it is working.
        assertEquals(PetReadiness.Ready, of())
        assertNull(PetReadiness.headline(PetReadiness.Ready))
        assertNull(PetReadiness.action(PetReadiness.Ready))
    }

    @Test
    fun `each missing faculty is named, not just counted`() {
        // "Something is missing" sends someone to a search engine. The whole
        // point is to say WHICH.
        assertEquals(PetReadiness.Missing(listOf(PetFaculty.BRAIN)), of(llm = unloaded))
        assertEquals(PetReadiness.Missing(listOf(PetFaculty.EARS)), of(stt = null))
        assertEquals(PetReadiness.Missing(listOf(PetFaculty.VOICE)), of(tts = false))
    }

    @Test
    fun `the first-run case names all three`() {
        val r = of(llm = unloaded, tts = false, stt = null)
        assertEquals(PetReadiness.Missing(PetFaculty.entries.toList()), r)

        val head = PetReadiness.headline(r)!!
        val act = PetReadiness.action(r)!!
        assertTrue("did not read as a fresh install: $head", head.contains("no models yet"))
        // Every file kind must be named, or the user can only fix one of three.
        for (f in PetFaculty.entries) {
            assertTrue("did not say what to add for $f: $act", act.contains(f.what))
        }
    }

    // ---- precedence --------------------------------------------------------

    @Test
    fun `a failure outranks anything missing`() {
        // A failure needs a decision; missing files need a file manager. The
        // thing the user can act on first wins.
        val r = PetReadiness.of(
            LlmManager.ModelState.Error("bad magic"), ttsReady = false, sttModelName = null
        )
        assertEquals(PetReadiness.Failed(PetFaculty.BRAIN, "bad magic"), r)
    }

    @Test
    fun `loading outranks missing, because it resolves itself`() {
        val r = PetReadiness.of(
            LlmManager.ModelState.Loading(0.5f), ttsReady = false, sttModelName = null
        )
        assertEquals(PetReadiness.Loading(PetFaculty.BRAIN), r)
    }

    @Test
    fun `a load failure keeps the native error verbatim`() {
        // A paraphrase of a loader failure has thrown away the only thing that
        // identifies it. This is the one string a user will paste to someone.
        val err = "llama_model_load: error loading model: unknown tensor 'blk.0'"
        val act = PetReadiness.action(PetReadiness.Failed(PetFaculty.BRAIN, err))!!
        assertTrue("the error was summarised away: $act", act.contains(err))
    }

    @Test
    fun `no line reads as broken english`() {
        // Caught in real output, not in review: `what` already carries its own
        // article, so "Loading its a .gguf language model." shipped. Cheap to
        // assert, and the kind of thing nobody re-reads once it compiles.
        val lines = buildList {
            for (f in PetFaculty.entries) {
                add(PetReadiness.action(PetReadiness.Loading(f)))
                add(PetReadiness.action(PetReadiness.Failed(f, "e")))
                add(PetReadiness.action(PetReadiness.Missing(listOf(f))))
                add(PetReadiness.headline(PetReadiness.Missing(listOf(f))))
            }
        }.filterNotNull()
        for (l in lines) {
            assertTrue("double article in: $l", !l.contains("its a "))
            assertTrue("double article in: $l", !l.contains("a a "))
            assertTrue("doubled space in: $l", !l.contains("  "))
        }
    }

    // ---- what the pet itself says ------------------------------------------

    @Test
    fun `a ready pet says nothing on its own screen`() {
        // It should be showing whatever it was showing. A pet that announces
        // its own health is a diagnostic, not a pet.
        assertNull(PetReadiness.petText(PetReadiness.Ready))
    }

    @Test
    fun `the pet speaks for itself when it cannot answer`() {
        // THE one that matters for the out-of-range reconnect: the service
        // starts itself with the app never opened, so the phone's banner is
        // unreachable and the pet's own screen is the only surface there is.
        // Without this the pet sits there looking well while ignoring you.
        for (r in listOf(
            PetReadiness.Loading(PetFaculty.BRAIN),
            PetReadiness.Failed(PetFaculty.BRAIN, "boom"),
            PetReadiness.Missing(listOf(PetFaculty.EARS)),
            PetReadiness.Missing(PetFaculty.entries.toList()),
        )) {
            val t = PetReadiness.petText(r)
            assertNotNull("the pet said nothing for $r", t)
            assertTrue("blank for $r", t!!.isNotBlank())
        }
    }

    @Test
    fun `what the pet says fits its screen and the wire`() {
        // PET_TEXT_MAX is 240 bytes and the screen fits about six short lines.
        // A message that overruns is truncated by the firmware, so the useful
        // half would be the half that got cut.
        for (r in listOf(
            PetReadiness.Loading(PetFaculty.BRAIN),
            PetReadiness.Failed(PetFaculty.VOICE, "a".repeat(500)),
            PetReadiness.Missing(PetFaculty.entries.toList()),
        )) {
            val t = PetReadiness.petText(r)!!
            assertTrue("too long for the pet ($r): ${t.length}", t.toByteArray().size <= 240)
        }
    }

    // ---- wording -----------------------------------------------------------

    @Test
    fun `every state a user can reach says something`() {
        // A state with no words renders as an empty banner, which reads as a
        // bug rather than as a status.
        val states = listOf(
            PetReadiness.Loading(PetFaculty.BRAIN),
            PetReadiness.Failed(PetFaculty.EARS, "boom"),
            PetReadiness.Missing(listOf(PetFaculty.VOICE)),
            PetReadiness.Missing(listOf(PetFaculty.BRAIN, PetFaculty.EARS)),
            PetReadiness.Missing(PetFaculty.entries.toList()),
        )
        for (s in states) {
            assertNotNull("no headline for $s", PetReadiness.headline(s))
            assertNotNull("no action for $s", PetReadiness.action(s))
            assertTrue("blank headline for $s", PetReadiness.headline(s)!!.isNotBlank())
        }
    }

    @Test
    fun `two missing faculties read as a sentence, not a list`() {
        val head = PetReadiness.headline(
            PetReadiness.Missing(listOf(PetFaculty.BRAIN, PetFaculty.EARS))
        )!!
        assertTrue("not joined into prose: $head", head.contains(" or "))
        assertTrue(head.contains("think"))
        assertTrue(head.contains("hear you"))
    }

    @Test
    fun `faculties are ordered by how badly the pet is broken without them`() {
        // Brain first: without it nothing works. Ears next — you can still type.
        // Voice last — it still writes on its own screen.
        assertEquals(
            listOf(PetFaculty.BRAIN, PetFaculty.EARS, PetFaculty.VOICE),
            PetFaculty.entries.toList()
        )
    }

    // ---- a broken Whisper model is not a missing one (fixed 2026-08-05) -----
    //
    // Before the fix both STT init paths swallowed their exception and left the
    // name null, so these two states were the same state. The distinction is the
    // whole point: one needs a file fetched, the other needs a file replaced.

    @Test
    fun `a whisper model that will not load reports Failed, not Missing`() {
        val r = of(stt = "ggml-tiny.en.bin", sttError = "invalid model header")
        assertEquals(PetReadiness.Failed(PetFaculty.EARS, "invalid model header"), r)
    }

    @Test
    fun `a broken whisper model does not tell the user to go and find one`() {
        // The actual harm of the old behaviour: it gave advice that was wrong.
        // Someone who already had the file was sent to fetch it again.
        val broken = of(stt = "ggml-tiny.en.bin", sttError = "invalid model header")
        val absent = of(stt = null)

        assertTrue(PetReadiness.action(broken)!!.contains("would not load"))
        assertTrue(PetReadiness.action(absent)!!.startsWith("Add "))
        assertNotNull(PetReadiness.headline(broken))
    }

    @Test
    fun `the whisper error is carried verbatim`() {
        // A paraphrase of a loader failure throws away the only thing that
        // identifies it. Same rule as the LLM's error.
        val r = of(sttError = "ggml_aligned_malloc: insufficient memory")
        assertTrue(
            PetReadiness.action(r)!!.contains("ggml_aligned_malloc: insufficient memory")
        )
    }

    @Test
    fun `a brain failure outranks an ears failure`() {
        // Both broken at once: report the one that is actually stopping them.
        val r = of(llm = LlmManager.ModelState.Error("no such file"), sttError = "bad header")
        assertEquals(PetReadiness.Failed(PetFaculty.BRAIN, "no such file"), r)
    }

    @Test
    fun `an ears failure outranks a loading brain`() {
        // Precedence is Failed, then Loading, then Missing — a failure needs a
        // decision and loading resolves itself.
        val r = of(llm = LlmManager.ModelState.Loading(0.5f), sttError = "bad header")
        assertEquals(PetReadiness.Failed(PetFaculty.EARS, "bad header"), r)
    }

    @Test
    fun `no stt error means the old behaviour is unchanged`() {
        // Guards the default argument: existing callers that pass three
        // arguments must still get exactly what they got before.
        assertEquals(PetReadiness.Ready, of(sttError = null))
        assertEquals(PetReadiness.Missing(listOf(PetFaculty.EARS)), of(stt = null))
    }
}
