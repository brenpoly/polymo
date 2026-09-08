package com.digitalpet.service

import com.digitalpet.ble.PetBleRepository.State
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The boot give-up's decision, over the whole state space.
 *
 * The case that matters is `CONNECTING`, and it is the one that shipped wrong:
 * a pet found in the last seconds of the three-minute window was being dropped
 * because the handshake had not finished. Everything else here exists so that
 * fixing that case cannot quietly break the others.
 */
class BootOutcomeTest {

    @Test
    fun `a connected pet is kept`() {
        assertEquals(BootOutcome.KEEP, BootOutcome.of(State.READY))
    }

    @Test
    fun `a handshake in flight is waited for, not stood down`() {
        // THE REGRESSION. CONNECTING means a pet has been FOUND and the link is
        // still being set up — MTU, discovery, the first reads. Standing down
        // here is what dropped a pet five seconds after it connected.
        assertEquals(BootOutcome.WAIT, BootOutcome.of(State.CONNECTING))
    }

    @Test
    fun `nothing found means stand down`() {
        // Both of these are "three minutes and no device". Scanning is included
        // on purpose: a long scan is a pet that is off or out of range, not one
        // part way through a handshake.
        assertEquals(BootOutcome.STAND_DOWN, BootOutcome.of(State.IDLE))
        assertEquals(BootOutcome.STAND_DOWN, BootOutcome.of(State.SCANNING))
    }

    @Test
    fun `every state has an outcome, and only one state is kept`() {
        /*
         * Guards the shape rather than a value. The old code was a single `!=
         * READY` comparison, so a new state would have fallen silently into
         * "stand down" — the direction that loses somebody's pet. This fails if
         * a state is added without a decision being made about it.
         */
        val all = State.entries.associateWith { BootOutcome.of(it) }
        assertEquals("every state must map to an outcome", State.entries.size, all.size)
        assertEquals(
            "exactly one state should keep the service alive",
            listOf(State.READY),
            all.filterValues { it == BootOutcome.KEEP }.keys.toList(),
        )
    }
}
