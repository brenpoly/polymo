package com.digitalpet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [PetProtocol] is the phone's half of a wire format whose other half is
 * `pet-esp32/main/pet_proto.h`. Every byte here has a counterpart in C that no
 * compiler checks, and the two have already drifted once — a 128-byte off-by-one
 * between the wire limit and the firmware's receive buffer, which presented as
 * "long messages never appear".
 *
 * These tests pin the parsing side: the byte layouts, the unsigned conversions,
 * and above all what happens to input that is short, malformed or from a
 * firmware newer than this app.
 */
class PetProtocolTest {

    // ---- Info -------------------------------------------------------------

    @Test
    fun `parses version and capabilities`() {
        // What a v3 pet actually returns: caps 31 = text|mood|events|mic|speaker.
        val info = PetProtocol.parseInfo(byteArrayOf(3, 31, 0))!!

        assertEquals(3, info.version)
        assertTrue(info.capabilities.text)
        assertTrue(info.capabilities.mood)
        assertTrue(info.capabilities.events)
        assertTrue(info.capabilities.mic)
        assertTrue(info.capabilities.speaker)
        // Not implemented yet; the app must not light these up.
        assertTrue(!info.capabilities.imu)
        assertTrue(!info.capabilities.touch)
        assertTrue(!info.capabilities.battery)
    }

    @Test
    fun `capability bits above 7 survive the two-byte split`() {
        // capsHi is a separate byte on the wire. Reading it as signed, or
        // forgetting to shift it, silently loses every capability past bit 7 —
        // which is where the next milestones' bits live.
        val info = PetProtocol.parseInfo(byteArrayOf(3, 0, 0x01))!!
        assertEquals(0x0100, info.capabilities.bits)
    }

    @Test
    fun `a short Info payload is rejected rather than half-read`() {
        assertNull(PetProtocol.parseInfo(byteArrayOf()))
        assertNull(PetProtocol.parseInfo(byteArrayOf(3)))
        assertNull(PetProtocol.parseInfo(byteArrayOf(3, 31)))
    }

    @Test
    fun `trailing bytes from a newer firmware are tolerated`() {
        // The header promises this explicitly: a client that knows a later
        // version must not assume the length. Rejecting extra bytes would mean
        // every future firmware bump bricks older apps.
        val info = PetProtocol.parseInfo(byteArrayOf(4, 31, 0, 99, 99))!!
        assertEquals(4, info.version)
        assertEquals(31, info.capabilities.bits)
    }

    // ---- Mood -------------------------------------------------------------

    @Test
    fun `known moods round-trip and unknown ones fall back to neutral`() {
        for (mood in PetProtocol.Mood.entries) {
            assertEquals(mood, PetProtocol.Mood.from(mood.value))
        }
        // A newer firmware inventing mood 7 must leave the app with a face it
        // can draw, not an exception.
        assertEquals(PetProtocol.Mood.NEUTRAL, PetProtocol.Mood.from(7))
    }

    // ---- Events -----------------------------------------------------------

    @Test
    fun `parses a ready event`() {
        val evt = PetProtocol.parseEvent(byteArrayOf(0x01, 3, 3, 31, 0))
        assertTrue(evt is PetProtocol.Event.Ready)
        assertEquals(3, (evt as PetProtocol.Event.Ready).info.version)
    }

    @Test
    fun `parses a mood change`() {
        val evt = PetProtocol.parseEvent(byteArrayOf(0x02, 1, 2))
        assertEquals(
            PetProtocol.Mood.SLEEPY,
            (evt as PetProtocol.Event.MoodChanged).mood
        )
    }

    @Test
    fun `audio started carries no frame count`() {
        val evt = PetProtocol.parseEvent(byteArrayOf(0x20, 1, 1))
        val state = evt as PetProtocol.Event.AudioState
        assertTrue(state.listening)
        assertEquals(0, state.frames)
    }

    @Test
    fun `audio stopped carries a little-endian frame count`() {
        // 352 frames = 0x0160, which is the count from a real 7 s utterance.
        // Byte order matters: read big-endian this becomes 24577, and the app
        // would report thousands of missing frames on every utterance.
        val evt = PetProtocol.parseEvent(byteArrayOf(0x20, 3, 0, 0x60, 0x01))
        val state = evt as PetProtocol.Event.AudioState
        assertTrue(!state.listening)
        assertEquals(352, state.frames)
    }

    @Test
    fun `a frame count above 255 is not truncated`() {
        // The high byte is where a signed-byte bug hides: counts under 256 look
        // perfect while every longer utterance is wrong.
        val evt = PetProtocol.parseEvent(byteArrayOf(0x20, 3, 0, 0xFF.toByte(), 0xFF.toByte()))
        assertEquals(65535, (evt as PetProtocol.Event.AudioState).frames)
    }

    @Test
    fun `speak done is distinguished from capture state`() {
        // Both are PET_EVT_AUDIO; only payload byte 0 tells them apart. Confusing
        // them would have the app believe the pet is listening when it just
        // finished speaking.
        assertEquals(
            PetProtocol.Event.SpeakDone,
            PetProtocol.parseEvent(byteArrayOf(0x20, 1, 2))
        )
    }

    @Test
    fun `unknown event types are skipped, not fatal`() {
        // The firmware header requires this: clients must skip unknown events
        // via the length byte so new ones can ship without breaking old apps.
        val evt = PetProtocol.parseEvent(byteArrayOf(0x7F, 2, 9, 9))
        val unknown = evt as PetProtocol.Event.Unknown
        assertEquals(0x7F, unknown.type)
        assertEquals(2, unknown.payload.size)
    }

    @Test
    fun `a truncated event does not read past the buffer`() {
        // A length byte claiming more than arrived is the shape of a malformed
        // or clipped notification; it must clamp rather than throw.
        val evt = PetProtocol.parseEvent(byteArrayOf(0x7F, 200.toByte(), 1, 2))
        assertEquals(2, (evt as PetProtocol.Event.Unknown).payload.size)
    }

    @Test
    fun `events too short to have a header are rejected`() {
        assertNull(PetProtocol.parseEvent(byteArrayOf()))
        assertNull(PetProtocol.parseEvent(byteArrayOf(0x02)))
    }

    // ---- Audio frames -----------------------------------------------------

    @Test
    fun `parses a sequence number and the opus packet after it`() {
        val frame = PetProtocol.parseAudioFrame(byteArrayOf(7, 0x4b, 0x41, 0x05))!!
        assertEquals(7, frame.seq)
        // 0x4b is the TOC byte the firmware actually emits; the packet must
        // arrive byte-identical or opus_decode gets garbage.
        assertArrayEqualsB(byteArrayOf(0x4b, 0x41, 0x05), frame.packet)
    }

    @Test
    fun `sequence numbers above 127 stay positive`() {
        // Kotlin's Byte is signed, so seq 200 arrives as -56. Losing the mask
        // makes the gap arithmetic downstream compute enormous negative gaps
        // for half of every 256-frame cycle.
        val frame = PetProtocol.parseAudioFrame(byteArrayOf(200.toByte(), 1))!!
        assertEquals(200, frame.seq)
    }

    @Test
    fun `a header with no packet is rejected`() {
        // An empty payload would decode to nothing and desynchronise the
        // sequence, so it must not become a frame at all.
        assertNull(PetProtocol.parseAudioFrame(byteArrayOf(7)))
        assertNull(PetProtocol.parseAudioFrame(byteArrayOf()))
    }

    // ---- consistency with the firmware header -----------------------------

    @Test
    fun `frame geometry matches pet_proto_h`() {
        // These four constants are duplicated in C. If they drift, audio is
        // pitched wrong or framed wrong, and nothing fails loudly.
        assertEquals(16_000, PetProtocol.AUDIO_SAMPLE_RATE)
        assertEquals(20, PetProtocol.AUDIO_FRAME_MS)
        assertEquals(320, PetProtocol.AUDIO_FRAME_SAMPLES)
        assertEquals(240, PetProtocol.TEXT_MAX)
    }

    private fun assertArrayEqualsB(expected: ByteArray, actual: ByteArray) =
        assertEquals(expected.toList(), actual.toList())

    // --- v4 Condition -------------------------------------------------------
    //
    // The pet reports itself now, rather than the phone pushing its face at it,
    // so mis-parsing this means silently believing the wrong thing about a pet
    // that is actually starving.

    @Test
    fun `condition parses scores, flags and mistakes`() {
        val c = PetProtocol.Condition.parse(byteArrayOf(3, 1, 0x05, 0x2A, 0x01))!!
        assertEquals(3, c.satiety)
        assertEquals(1, c.happiness)
        assertTrue(c.calling)          // bit 0
        assertTrue(!c.sick)            // bit 1 clear
        assertTrue(c.dead)             // bit 2
        assertEquals(0x012A, c.careMistakes)   // little-endian
    }

    @Test
    fun `care mistakes are little-endian and unsigned`() {
        // 0xFF in the low byte must be 255, not -1: a signed byte here would
        // make a well-cared-for pet look catastrophically neglected.
        val c = PetProtocol.Condition.parse(byteArrayOf(0, 0, 0, 0xFF.toByte(), 0))!!
        assertEquals(255, c.careMistakes)
    }

    @Test
    fun `scores are unsigned`() {
        val c = PetProtocol.Condition.parse(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0, 0, 0))!!
        assertEquals(255, c.satiety)
        assertEquals(255, c.happiness)
    }

    @Test
    fun `a short payload is rejected rather than guessed at`() {
        assertEquals(null, PetProtocol.Condition.parse(byteArrayOf(4, 4, 0, 0)))
        assertEquals(null, PetProtocol.Condition.parse(byteArrayOf()))
    }

    @Test
    fun `trailing bytes from a later firmware are tolerated`() {
        // The pet may grow fields. Refusing to parse would make an older app
        // stop understanding a newer pet entirely, rather than degrade.
        val c = PetProtocol.Condition.parse(byteArrayOf(2, 2, 0, 0, 0, 9, 9, 9))!!
        assertEquals(2, c.satiety)
        assertEquals(0, c.careMistakes)
    }

    @Test
    fun `the sick flag is bit 1`() {
        // Phase 4's whole output on the wire is this one bit. Reading the wrong
        // one would show a sick pet as calling, or as dead.
        val c = PetProtocol.Condition.parse(byteArrayOf(2, 2, 0x02, 0, 0))!!
        assertTrue(c.sick)
        assertTrue(!c.calling)
        assertTrue(!c.dead)
    }

    // --- v6 life stages -----------------------------------------------------

    @Test
    fun `the life stage is byte 5`() {
        val c = PetProtocol.Condition.parse(byteArrayOf(2, 2, 0, 0, 0, 2))!!
        assertEquals(PetProtocol.Stage.TEEN, c.stage)
    }

    @Test
    fun `every stage byte the firmware can send decodes`() {
        // pet_stage_t is 0..3 and the order is load-bearing on both sides — it
        // sets the decay rate and the size of the face, not just a label.
        val expected = listOf(
            PetProtocol.Stage.EGG, PetProtocol.Stage.CHILD,
            PetProtocol.Stage.TEEN, PetProtocol.Stage.ADULT
        )
        for ((raw, stage) in expected.withIndex()) {
            val c = PetProtocol.Condition.parse(byteArrayOf(2, 2, 0, 0, 0, raw.toByte()))!!
            assertEquals("stage byte $raw", stage, c.stage)
        }
    }

    @Test
    fun `a pre-v6 pet reports no stage rather than an egg`() {
        // THE one that matters. A 5-byte Condition comes from a pet that has no
        // concept of life stages, and defaulting that to EGG would have the app
        // confidently describe a pet it knows nothing about as a newborn — and
        // would put "You are a very young pet" into the system prompt on the
        // strength of a byte that was never sent.
        val c = PetProtocol.Condition.parse(byteArrayOf(2, 2, 0, 0, 0))!!
        assertEquals(null, c.stage)
    }

    @Test
    fun `an unknown stage byte is null, not a crash`() {
        // Forward compatibility in the other direction: a later firmware with a
        // fifth stage must not take the app down.
        val c = PetProtocol.Condition.parse(byteArrayOf(2, 2, 0, 0, 0, 99))!!
        assertEquals(null, c.stage)
    }

    @Test
    fun `the dead flag is bit 2`() {
        val c = PetProtocol.Condition.parse(byteArrayOf(0, 0, 0x04, 0, 0, 3))!!
        assertTrue(c.dead)
        assertTrue(!c.sick)
        assertTrue(!c.calling)
    }

    @Test
    fun `reset is the only command and it is not zero`() {
        // Zero is PET_CMD_NONE on the pet. A RESET that encoded as 0 would mean
        // an empty or zeroed write silently wiping a pet.
        assertEquals(1.toByte(), PetProtocol.Command.RESET.value)
        assertTrue(PetProtocol.Command.RESET.value != PetProtocol.Command.NONE.value)
    }

    // --- v7 battery ---------------------------------------------------------

    @Test
    fun `battery is parsed from bytes 6 to 8`() {
        // 3828 mV = 0x0EF4, little-endian.
        val c = PetProtocol.Condition.parse(
            byteArrayOf(2, 2, 0, 0, 0, 1, 64, 0xF4.toByte(), 0x0E)
        )!!
        assertEquals(64, c.batteryPercent)
        assertEquals(3828, c.batteryMillivolts)
    }

    @Test
    fun `a pre-v7 pet reports no battery rather than a flat one`() {
        // THE one that matters, and the same shape as the pre-v6 stage test: a
        // 6-byte Condition comes from a pet that cannot measure its battery.
        // Defaulting that to 0 would show a fully charged pet as flat.
        val c = PetProtocol.Condition.parse(byteArrayOf(2, 2, 0, 0, 0, 1))!!
        assertEquals(null, c.batteryPercent)
        assertEquals(null, c.batteryMillivolts)
    }

    @Test
    fun `the pet's unknown markers do not decode as real readings`() {
        // The firmware sends 0xFF percent and 0 mV when the PMIC did not answer
        // or the gauge has not settled. Both must read as "not known", because
        // 255% is nonsense and 0 mV would look like an emergency.
        val c = PetProtocol.Condition.parse(
            byteArrayOf(2, 2, 0, 0, 0, 1, 0xFF.toByte(), 0, 0)
        )!!
        assertEquals(null, c.batteryPercent)
        assertEquals(null, c.batteryMillivolts)
    }

    // --- v5 screen time -----------------------------------------------------

    @Test
    fun `screen time levels match the firmware's enum`() {
        // pet_proto.h: PET_SCREEN_OK = 0, PET_SCREEN_OVERUSE = 1. These two
        // bytes are the entire phase 4 uplink, and swapping them would make the
        // pet ill exactly when the user was behaving.
        assertEquals(0.toByte(), PetProtocol.ScreenTime.OK.value)
        assertEquals(1.toByte(), PetProtocol.ScreenTime.OVERUSE.value)
    }

    @Test
    fun `the screen characteristic is 0x0016`() {
        assertEquals(
            java.util.UUID.fromString("D16B0016-A1B2-4C3D-8E5F-0A1B2C3D4E5F"),
            PetProtocol.CHAR_SCREEN
        )
    }

    @Test
    fun `the protocol version matches the firmware`() {
        // pet_proto.h's PET_PROTO_VERSION. The two files are hand-mirrored, and
        // a version that drifts is how a phone silently talks to a pet that
        // does not have the characteristic it is writing to.
        //
        // This used to assert against a hardcoded literal, which only ever
        // reminded you to update it — it could not tell a deliberate bump from
        // a drift, because it never looked at the firmware at all. It now reads
        // the header, which is what makes it a mirror check rather than a
        // second copy of the same guess.
        val header = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "pet-esp32/main/pet_proto.h") }
            .firstOrNull { it.isFile }

        assertTrue(
            "pet_proto.h not found from " + File(".").absolutePath +
                " — if the firmware moved, fix this test rather than deleting it",
            header != null
        )

        val fromFirmware = Regex("#define\\s+PET_PROTO_VERSION\\s+(\\d+)")
            .find(header!!.readText())
            ?.groupValues?.get(1)?.toInt()

        assertEquals(
            "PetProtocol.kt and pet_proto.h disagree about the protocol version",
            fromFirmware, PetProtocol.VERSION
        )
    }

    @Test
    fun `the poll interval fits several times inside the pet's stale timeout`() {
        // The pet stops believing an overuse report after SCREEN_STALE_SEC, and
        // the phone renews it once a minute. If that ever inverted, a pet would
        // recover on its own while the user was still scrolling — the mechanic
        // failing open, silently and in the direction nobody notices.
        assertTrue(
            "the pet would time out between polls",
            PetProtocol.SCREEN_STALE_SEC >= 4 * 60
        )
    }
}
