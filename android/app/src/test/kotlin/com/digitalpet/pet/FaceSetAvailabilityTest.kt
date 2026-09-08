package com.digitalpet.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FaceSetAvailability] — the three reasons a face cannot be changed.
 *
 * Written after the Face screen shipped and did nothing on hardware. The taps
 * were landing and `selectFaceSet` was being called; Android was serving a
 * cached GATT database from before the characteristic existed, so the write had
 * nowhere to go. The screen confirms nothing until the pet does, so that was
 * invisible.
 *
 * These tests are about telling the three cases apart, because the wrong message
 * is worse than none: "this pet does not support face sets" would have been
 * false and would have sent someone to check the firmware.
 */
class FaceSetAvailabilityTest {

    @Test
    fun `a present characteristic is all it takes`() {
        assertEquals(
            FaceSetAvailability.AVAILABLE,
            FaceSetAvailability.of(connected = true, characteristicPresent = true, petProtocol = 9),
        )
    }

    @Test
    fun `DISCONNECTED OUTRANKS EVERYTHING, because the others would all be true too`() {
        /*
         * The ordering rule. A disconnected pet has no protocol version and no
         * characteristics, so "unsupported" and "stale cache" are both trivially
         * satisfiable — and both would be wrong and unhelpful. This fails if
         * someone reorders the `when`.
         */
        assertEquals(
            FaceSetAvailability.DISCONNECTED,
            FaceSetAvailability.of(false, characteristicPresent = false, petProtocol = null),
        )
        assertEquals(
            FaceSetAvailability.DISCONNECTED,
            FaceSetAvailability.of(false, characteristicPresent = false, petProtocol = 9),
        )
        // Even with everything else present: no link, no change.
        assertEquals(
            FaceSetAvailability.DISCONNECTED,
            FaceSetAvailability.of(false, characteristicPresent = true, petProtocol = 9),
        )
    }

    @Test
    fun `A V9 PET WITH NO CHARACTERISTIC IS A CACHED SERVICE LIST, not an old pet`() {
        /*
         * The case that cost real time. The pet said v9 — so it HAS the
         * characteristic — and the phone could not see it. Nothing but Android's
         * per-device GATT cache produces that combination, and the fix is
         * re-pairing rather than anything to do with firmware.
         */
        assertEquals(
            FaceSetAvailability.STALE_CACHE,
            FaceSetAvailability.of(true, characteristicPresent = false, petProtocol = 9),
        )
        // A later protocol is still v9-or-better, so still the cache.
        assertEquals(
            FaceSetAvailability.STALE_CACHE,
            FaceSetAvailability.of(true, characteristicPresent = false, petProtocol = 12),
        )
    }

    @Test
    fun `a genuinely older pet is UNSUPPORTED, at the boundary`() {
        // 8 and 9 are one character apart and mean completely different advice.
        assertEquals(
            FaceSetAvailability.UNSUPPORTED,
            FaceSetAvailability.of(true, characteristicPresent = false, petProtocol = 8),
        )
        assertEquals(
            FaceSetAvailability.STALE_CACHE,
            FaceSetAvailability.of(true, characteristicPresent = false, petProtocol = 9),
        )
        // Not yet read: assume the honest thing rather than blaming the cache.
        assertEquals(
            FaceSetAvailability.UNSUPPORTED,
            FaceSetAvailability.of(true, characteristicPresent = false, petProtocol = null),
        )
    }

    @Test
    fun `only AVAILABLE is selectable`() {
        FaceSetAvailability.entries.forEach {
            assertEquals(
                "$it selectable",
                it == FaceSetAvailability.AVAILABLE,
                it.selectable,
            )
        }
    }

    @Test
    fun `EVERY UNAVAILABLE STATE EXPLAINS ITSELF, and AVAILABLE says nothing`() {
        // A silent unavailable state is the bug this whole class exists for.
        assertNull(FaceSetAvailability.AVAILABLE.message)
        FaceSetAvailability.entries.filter { it != FaceSetAvailability.AVAILABLE }.forEach {
            assertNotNull("$it has no message", it.message)
            assertTrue("$it's message is empty", it.message!!.isNotBlank())
        }
    }

    @Test
    fun `THE STALE-CACHE MESSAGE NAMES THE FIX AND BLAMES NEITHER SIDE`() {
        /*
         * The wording is the whole value here. The user's pet is fine and their
         * app is fine; the only useful sentence is the one about re-pairing.
         * Mentioning firmware or a version would send them somewhere useless.
         */
        val m = FaceSetAvailability.STALE_CACHE.message!!
        assertTrue("does not say how to fix it", m.contains("pair", ignoreCase = true))
        assertFalse("blames the firmware", m.contains("firmware", ignoreCase = true))
        assertFalse("mentions a version", m.contains("v9", ignoreCase = true))
    }

    @Test
    fun `THE UNSUPPORTED MESSAGE DOES NOT TELL SOMEONE TO RE-PAIR`() {
        // The mirror of the test above, and the reason the two states exist
        // separately: re-pairing an older pet would achieve nothing at all.
        val m = FaceSetAvailability.UNSUPPORTED.message!!
        assertTrue("does not name the real cause", m.contains("firmware", ignoreCase = true))
        assertFalse("sends them to re-pair pointlessly", m.contains("pair", ignoreCase = true))
    }
}
