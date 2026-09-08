package com.digitalpet.service

import com.digitalpet.ble.PetBleRepository.State

/**
 * What the boot give-up should do, as a pure function of the link's state.
 *
 * **This is `sleep_decide()` in Kotlin, and for the same reason.** The firmware
 * grew three bugs in thirty lines of early-return chain, each one a case that
 * could be skipped in silence under a comment asserting it was handled; the fix
 * was to write the whole state space out and let `-Werror=switch` catch a
 * missing branch. This decision had exactly one of those bugs in it.
 *
 * ### The bug this replaces
 *
 * The check was `if (state != READY) standDown()`, which reads as "no pet" and
 * is not. `CONNECTING` means a pet **has been found** and the handshake — MTU,
 * service discovery, the first reads — is still running. Treating that as
 * absence drops a pet that turned up near the deadline. Measured on a real
 * reboot, 2026-08-11:
 *
 * ```
 * 21:37:00  connected; requesting MTU
 * 21:37:05  boot: no pet after 180s, standing down
 * ```
 *
 * Five seconds. Nothing was wrong with the pet, the link, or the three minutes;
 * only with reading "not finished" as "not there".
 *
 * **It took a mistake to find.** The deliberate test powers the pet off, so the
 * state at the deadline is always IDLE and this branch never runs. It surfaced
 * because the pet was switched on part way through — the window in which it can
 * happen at all is a few seconds, three minutes after a reboot.
 *
 * Written as an exhaustive `when` over `State` so that adding a fifth state is
 * a compile error here rather than a silent fall-through into [STAND_DOWN],
 * which is the direction that loses somebody's pet.
 */
enum class BootOutcome {
    /** The link is up. Leave the service running. */
    KEEP,

    /**
     * A handshake is in flight. Give it a bounded grace period and ask again —
     * do not stand down, and do not wait forever either.
     */
    WAIT,

    /**
     * Nothing was found. Stop, rather than leave a notification about a pet
     * that is not there and a radio retrying into an empty room.
     */
    STAND_DOWN;

    companion object {
        fun of(state: State): BootOutcome = when (state) {
            State.READY -> KEEP
            State.CONNECTING -> WAIT
            // Never got as far as a device. SCANNING is included deliberately:
            // three minutes of scanning without a connection is a pet that is
            // off or out of range, not one mid-handshake.
            State.IDLE, State.SCANNING -> STAND_DOWN
        }
    }
}
