package com.digitalpet.pet

import com.digitalpet.ble.PetBleRepository
import com.digitalpet.ble.PetProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the care card says about the pet.
 *
 * This is the mapping most likely to drift from what the pet actually means,
 * and unlike a wrong colour a wrong sentence here sends someone to feed a pet
 * that is fine — or worse, leaves one starving because the card looked calm.
 */
class PetStatusTextTest {

    private fun condition(
        satiety: Int = 4,
        happiness: Int = 4,
        calling: Boolean = false,
        sick: Boolean = false,
        dead: Boolean = false,
        careMistakes: Int = 0,
        stage: PetProtocol.Stage? = PetProtocol.Stage.CHILD,
        batteryPercent: Int? = 50,
        batteryMillivolts: Int? = 3800,
    ) = PetProtocol.Condition(
        satiety, happiness, calling, sick, dead, careMistakes,
        stage, batteryPercent, batteryMillivolts
    )

    // ---- the one that matters ----------------------------------------------

    @Test
    fun `an unknown condition never reads as a pet in trouble`() {
        // Null means the link has not reported yet, not that the pet is empty.
        // Rendering zeros here would invent a starving pet out of a BLE link
        // that simply has not come up — the same rule the prompt clause and the
        // Condition parser follow, and the one with a screen attached.
        val text = PetStatusText.headline(null)

        assertTrue("said something was wrong: $text", !text.contains("Starving"))
        assertTrue(!text.contains("died"))
        assertTrue(!text.contains("Unwell"))
        assertEquals("—", PetStatusText.stageLabel(null))
        assertEquals("—", PetStatusText.batteryLabel(null, null))
    }

    // ---- precedence must match the pet's own face ---------------------------

    @Test
    fun `death outranks everything`() {
        val text = PetStatusText.headline(
            condition(satiety = 0, happiness = 0, sick = true, calling = true, dead = true)
        )
        assertTrue(text.contains("died"))
        assertTrue("a dead pet was described as hungry", !text.contains("Starving"))
    }

    @Test
    fun `sickness outranks the scores`() {
        // DESIGN.md §1 ranks sick above the scores because it is the thing the
        // user can act on right now. The card must agree with the face.
        val text = PetStatusText.headline(condition(satiety = 0, sick = true))
        assertTrue(text.contains("Unwell"))
        assertTrue(!text.contains("Starving"))
    }

    @Test
    fun `an empty score tells you the gesture that fixes it`() {
        // A care app that says "hungry" without saying how to feed it has told
        // the user off rather than helped them.
        assertTrue(PetStatusText.headline(condition(satiety = 0)).contains("double tap"))
        assertTrue(PetStatusText.headline(condition(happiness = 0)).contains("shake"))
    }

    @Test
    fun `calling never replaces the instruction that would answer it`() {
        // `calling` is only ever true while a score is 0, and those cases carry
        // the actionable wording. Checking it first would replace "feed it —
        // double tap" with the strictly less useful "asking for attention".
        val text = PetStatusText.headline(condition(satiety = 0, calling = true))
        assertTrue("the call hid the fix: $text", text.contains("double tap"))
    }

    @Test
    fun `a well pet is not nagged about`() {
        assertEquals("Doing well.", PetStatusText.headline(condition(satiety = 4, happiness = 4)))
        assertEquals("Doing well.", PetStatusText.headline(condition(satiety = 3, happiness = 3)))
    }

    // ---- the link ----------------------------------------------------------

    @Test
    fun `never paired and out of range do not read the same`() {
        // They look identical on screen and mean opposite things: one needs
        // setting up, the other needs walking into the next room. Collapsing
        // them sends a user hunting for a fault that is not there.
        val never = PetStatusText.connectionLabel(PetBleRepository.State.IDLE, paired = false)
        val away  = PetStatusText.connectionLabel(PetBleRepository.State.IDLE, paired = true)
        assertTrue("the two silences read the same: $never", never != away)
    }

    @Test
    fun `every connection state says something`() {
        // A state with no label renders as an empty row beside a red dot, which
        // reads as a bug rather than as a status.
        for (state in PetBleRepository.State.entries) {
            for (paired in listOf(true, false)) {
                assertTrue(
                    "no label for $state paired=$paired",
                    PetStatusText.connectionLabel(state, paired).isNotBlank()
                )
            }
        }
    }

    @Test
    fun `only a live link is trusted for the readings`() {
        // THE one that matters. The pet keeps decaying and ageing while the
        // phone is away — DESIGN.md §1 decision 1 — so a dropped link does not
        // make the numbers merely old, it makes them wrong in a direction
        // nobody can predict. Anything short of READY must stop asserting them.
        assertTrue(!PetStatusText.readingsAreStale(PetBleRepository.State.READY))
        for (state in PetBleRepository.State.entries - PetBleRepository.State.READY) {
            assertTrue("$state was treated as live", PetStatusText.readingsAreStale(state))
        }
    }

    // ---- the lock screen ---------------------------------------------------

    @Test
    fun `the discreet headline does not narrate the user's phone habits`() {
        // Sickness is the one line here that is a statement about the USER
        // rather than about the pet, and the lock screen is readable by anyone
        // who glances at a desk.
        val sick = condition(sick = true)
        val open = PetStatusText.headline(sick, discreet = false)
        val locked = PetStatusText.headline(sick, discreet = true)

        assertTrue("the reason leaked to the lock screen: $locked",
            !locked.contains("screen time"))
        assertTrue("the pet stopped saying it was unwell at all: $locked",
            locked.isNotBlank() && locked != open)
        assertTrue(open.contains("screen time"))
    }

    @Test
    fun `discreet mode hides only the reason, not the pet`() {
        // Everything that is a fact about the PET stays visible when locked —
        // hiding a hungry pet would defeat a notification whose whole job is to
        // be seen. Only the screen-time explanation is held back.
        for (c in listOf(
            condition(satiety = 0),
            condition(happiness = 0),
            condition(dead = true),
            condition(satiety = 4, happiness = 4)
        )) {
            assertEquals(
                "discreet mode changed a line that was not about the user",
                PetStatusText.headline(c, discreet = false),
                PetStatusText.headline(c, discreet = true)
            )
        }
    }

    // ---- the notification subtitle ------------------------------------------

    @Test
    fun `the subtitle leads with the link`() {
        val text = PetStatusText.notificationSubtitle(
            condition(), PetBleRepository.State.READY, paired = true
        )
        assertTrue("did not say it was connected: $text", text.startsWith("connected"))
        assertTrue(text.contains("50%"))
        // The stage is deliberately unnamed for now — see stageIsNamed. Asserted
        // rather than just dropped, so switching it back on fails here and the
        // notification is not left to be checked by eye.
        assertTrue("named the stage: $text", !text.contains("child"))
        assertEquals("connected · 50%", text)
    }

    @Test
    fun `a dropped link shows why instead of stale readings`() {
        // The notification is what someone glances at to decide whether to go
        // and feed the pet. Showing an hour-old battery and stage as fact is
        // exactly what would stop them.
        val text = PetStatusText.notificationSubtitle(
            condition(), PetBleRepository.State.IDLE, paired = true
        )
        assertTrue("leaked a reading from a dead link: $text", !text.contains("50%"))
        assertTrue(!text.contains("child"))
        assertEquals(PetStatusText.connectionLabel(PetBleRepository.State.IDLE, true), text)
    }

    @Test
    fun `the stop action says what pressing it will do`() {
        // It said "Stop pet" in every state, including the ones with no pet in
        // them — the service outlives the connection, so the button is often
        // offered while nothing is connected.
        assertEquals("Stop pet", PetStatusText.stopActionLabel(PetBleRepository.State.READY))
        for (s in PetBleRepository.State.entries - PetBleRepository.State.READY) {
            assertTrue(
                "offered to stop a pet that is not there ($s)",
                !PetStatusText.stopActionLabel(s).contains("pet")
            )
        }
    }

    // ---- the readouts ------------------------------------------------------

    @Test
    fun `pips show filled and empty against the real maximum`() {
        assertEquals("●●○○", PetStatusText.pips(2))
        assertEquals("○○○○", PetStatusText.pips(0))
        assertEquals("●●●●", PetStatusText.pips(4))
    }

    @Test
    fun `a score outside the range cannot draw a broken row`() {
        // The pet is the authority on this byte and it is unsigned on the wire,
        // so a firmware bug or a future MAX_SCORE change must not produce a row
        // of the wrong length or a negative repeat count (which throws).
        assertEquals(4, PetStatusText.pips(9).length)
        assertEquals(4, PetStatusText.pips(-1).length)
    }

    @Test
    fun `battery shows volts beside the percentage`() {
        // Both, because this gauge has already been confidently wrong: it read
        // 0% through a hundred millivolts of charging until a full discharge
        // calibrated it. A percentage alone cannot be sanity-checked by eye.
        assertEquals("50% · 3.80V", PetStatusText.batteryLabel(50, 3800))
        assertEquals("3.72V", PetStatusText.batteryLabel(null, 3720))
        assertEquals("50%", PetStatusText.batteryLabel(50, null))
    }

    @Test
    fun `care mistakes are worded, and zero is not hidden`() {
        // Shown even at zero: it is the figure that shortens the pet's life, so
        // "none so far" is information, not noise.
        assertEquals("No care mistakes", PetStatusText.careMistakesLabel(0))
        assertEquals("1 care mistake", PetStatusText.careMistakesLabel(1))
        assertEquals("4 care mistakes", PetStatusText.careMistakesLabel(4))
    }

    @Test
    fun `every stage the pet can report still has a word, ready for when it is named`() {
        // The vocabulary is KEPT rather than deleted while the stage is unnamed,
        // so turning it back on is one line. A stage with no label would render
        // blank, and the enum is what the wire can carry.
        for (stage in PetProtocol.Stage.entries) {
            val label = PetStatusText.stageLabel(stage)
            assertTrue("no label for $stage", label.isNotBlank() && label != "—")
        }
    }

    @Test
    fun `no surface names the stage while it is unnamed`() {
        // The whole point of the switch: one flag, both surfaces, no leaks. If
        // this ever fails while stageIsNamed is false, a surface has gone
        // straight to stageLabel and bypassed the gate.
        assertTrue("stageIsNamed should be off for the first release", !PetStatusText.stageIsNamed)
        for (stage in PetProtocol.Stage.entries) {
            assertNull("named $stage", PetStatusText.stageLabelOnSurface(stage))
        }
        assertNull(PetStatusText.stageLabelOnSurface(null))
    }

    @Test
    fun `a pet that has reported no stage is indistinguishable from a hidden one`() {
        // Both are absent rather than "—", which is what lets the CareCard row
        // and the notification join collapse cleanly instead of leaving a stray
        // separator.
        assertEquals(
            PetStatusText.stageLabelOnSurface(null),
            PetStatusText.stageLabelOnSurface(PetProtocol.Stage.ADULT),
        )
    }

    // ---- Bluetooth off (DESIGN.md §5.3, added 2026-08-05) ------------------
    //
    // The repository has consulted the adapter since the beginning and put the
    // answer in `status`, where nothing ever rendered it. This is the row of
    // §5.3's table that said nothing at all.

    @Test
    fun `bluetooth off is said plainly rather than as not connected`() {
        // The harm being fixed: "Not connected" sends someone to look for a pet
        // that is sitting in front of them, when the radio is the problem.
        assertEquals(
            "Bluetooth is off",
            PetStatusText.connectionLabel(
                PetBleRepository.State.IDLE, paired = true, bluetoothOn = false
            )
        )
    }

    @Test
    fun `bluetooth off outranks even a READY link`() {
        // The adapter can be switched off while a link is up, and a stale READY
        // would otherwise outlive the radio it depends on.
        assertEquals(
            "Bluetooth is off",
            PetStatusText.connectionLabel(
                PetBleRepository.State.READY, paired = true, bluetoothOn = false
            )
        )
    }

    @Test
    fun `bluetooth off blanks the readings`() {
        // §5.0 rule 2: never assert what we have not been told. A radio that is
        // off cannot have told us anything, whatever the last state said.
        assertTrue(
            PetStatusText.readingsAreStale(PetBleRepository.State.READY, bluetoothOn = false)
        )
        assertTrue(
            !PetStatusText.readingsAreStale(PetBleRepository.State.READY, bluetoothOn = true)
        )
    }

    @Test
    fun `bluetooth on leaves every existing label untouched`() {
        // Guards the default argument: the new parameter must not change a
        // single thing for callers that do not pass it.
        for (state in PetBleRepository.State.entries) {
            for (paired in listOf(true, false)) {
                assertEquals(
                    PetStatusText.connectionLabel(state, paired),
                    PetStatusText.connectionLabel(state, paired, bluetoothOn = true)
                )
            }
        }
    }

    // ---- switched off (DESIGN.md §5.3/§5.4, added 2026-08-05) --------------
    //
    // The stop is now persisted, so the app MUST admit the pet is off. An off
    // switch that survives reopening the app is only an improvement if the app
    // says so; otherwise it is a pet that never comes back and never explains.

    @Test
    fun `a pet the user switched off says so`() {
        assertEquals(
            "Your pet is off",
            PetStatusText.connectionLabel(
                PetBleRepository.State.IDLE, paired = true, switchedOff = true
            )
        )
    }

    @Test
    fun `switched off outranks bluetooth being off`() {
        // Blaming the radio for a decision the user made is accurate about the
        // radio and wrong about the pet.
        assertEquals(
            "Your pet is off",
            PetStatusText.connectionLabel(
                PetBleRepository.State.IDLE, paired = true,
                bluetoothOn = false, switchedOff = true
            )
        )
    }

    @Test
    fun `switched off never reads as not connected`() {
        // The specific harm: "Not connected" invites you to go and find a pet
        // you switched off on purpose.
        val off = PetStatusText.connectionLabel(
            PetBleRepository.State.IDLE, paired = true, switchedOff = true
        )
        val away = PetStatusText.connectionLabel(
            PetBleRepository.State.IDLE, paired = true, switchedOff = false
        )
        assertEquals("Not connected", away)
        assertTrue(off != away)
    }

    @Test
    fun `switched off blanks the readings`() {
        // §5.0 rule 2 - never assert what we have not been told. A pet that is
        // off is telling us nothing, whatever the last state said.
        assertTrue(
            PetStatusText.readingsAreStale(
                PetBleRepository.State.READY, bluetoothOn = true, switchedOff = true
            )
        )
    }

    @Test
    fun `not switched off leaves every existing label untouched`() {
        // Guards the new defaulted parameter.
        for (state in PetBleRepository.State.entries) {
            for (paired in listOf(true, false)) {
                assertEquals(
                    PetStatusText.connectionLabel(state, paired),
                    PetStatusText.connectionLabel(state, paired, switchedOff = false)
                )
            }
        }
    }

    // ---- switched off, which outranks the rest of the card -----------------

    @Test
    fun `a switched-off pet is not described as one we are waiting to hear from`() {
        // The bug this replaces: the chip said "Your pet is off" while the card
        // beneath it said "Waiting to hear from your pet…" — the screen blaming
        // a silence on the pet and admitting the user caused it, at once, and
        // waiting for a message that by construction never arrives.
        assertEquals(
            "You switched your pet off. It is living on without you.",
            PetStatusText.headline(null, switchedOff = true),
        )
        assertEquals("Waiting to hear from your pet…", PetStatusText.headline(null))
    }

    @Test
    fun `off outranks every condition the pet last reported`() {
        // Readings are stale the moment the link is dropped, so a card showing
        // them would be asserting what we have not been told (§5.0 rule 2) —
        // and none of them is the reason nothing is happening.
        listOf(
            condition(satiety = 0, happiness = 0),
            condition(sick = true),
            condition(dead = true),
        ).forEach { c ->
            assertEquals(
                "You switched your pet off. It is living on without you.",
                PetStatusText.headline(c, switchedOff = true),
            )
        }
    }

    @Test
    fun `off says the pet lives on, because it does`() {
        // DESIGN.md §1 decision 1: the simulation is on the device and its clock
        // keeps running whatever the phone does. What stopped is the watching.
        // Wording that implied the pet had been paused would be false, and would
        // make turning it back on feel like restarting rather than resuming.
        val text = PetStatusText.headline(null, switchedOff = true)
        assertTrue(text.contains("living on"))
        assertFalse(text.contains("Waiting"))
    }

    @Test
    fun `not being switched off changes nothing`() {
        // The parameter defaults to false, so every existing caller keeps its
        // behaviour — the discreet lock-screen path included.
        assertEquals(
            PetStatusText.headline(condition(sick = true)),
            PetStatusText.headline(condition(sick = true), switchedOff = false),
        )
        assertEquals(
            "Not feeling well.",
            PetStatusText.headline(condition(sick = true), discreet = true),
        )
    }

    // ---- the scan block ------------------------------------------------------

    @Test
    fun `a result requires a search`() {
        // THE BUG THIS FIXES. The old line was drawn whenever the discovered
        // list was empty, which it is before any scan has ever run — so an
        // untouched page announced "No other pets found yet. Make sure the pet
        // is powered on." underneath a card reading Connected. It reported the
        // outcome of a search nobody had performed.
        assertNull(PetStatusText.scanHint(scanning = false, hasScanned = false, found = 0))
    }

    @Test
    fun `after a fruitless scan it says so`() {
        val hint = PetStatusText.scanHint(scanning = false, hasScanned = true, found = 0)
        assertTrue("$hint", hint != null && hint.contains("No pets found"))
        // Tells you what to check, since the two causes are the pet being off
        // and the pet being too far away.
        assertTrue(hint!!.contains("powered on") && hint.contains("nearby"))
    }

    @Test
    fun `while scanning it says only that`() {
        assertEquals("Looking…", PetStatusText.scanHint(scanning = true, hasScanned = true, found = 0))
        // Scanning wins even on the first tap, before hasScanned could matter.
        assertEquals("Looking…", PetStatusText.scanHint(scanning = true, hasScanned = false, found = 0))
    }

    @Test
    fun `finding something says nothing — the list is the answer`() {
        assertNull(PetStatusText.scanHint(scanning = false, hasScanned = true, found = 2))
    }

    @Test
    fun `the scan is offered only when nothing is paired`() {
        // This app remembers exactly ONE pet: PetBleRepository.connect sets
        // pairedAddress outright, so tapping a discovered device while paired
        // replaces your pet silently. A scan offered then is a swap dressed as a
        // search; Forget is the route, and it announces itself.
        assertTrue(PetStatusText.showScan(paired = false))
        assertTrue(!PetStatusText.showScan(paired = true))
    }
}
