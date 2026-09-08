package com.digitalpet.pet

import com.digitalpet.pet.PermissionInventory.Grant
import com.digitalpet.pet.PermissionInventory.How
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Permissions page, minus the platform.
 *
 * The page exists because grants were going missing unnoticed, so the things
 * worth pinning are the ones that would let that happen again: a held grant
 * drawing an action it cannot perform, a missing one drawing none, a settings
 * trip disguised as a dialog, and the order in which someone reads the list.
 */
class PermissionInventoryTest {

    private fun allHeld(value: Boolean) = Grant.entries.associateWith { value }

    @Test
    fun `a held grant has no action, because there is nothing the app can do to it`() {
        // Android gives an app no way to hand a permission back, so a button on
        // a granted row would either do nothing or throw the user out to a
        // settings screen they did not ask for.
        PermissionInventory.rows(allHeld(true)).forEach {
            assertTrue("expected held: ${it.grant}", it.held)
            assertNull("held grant offered an action: ${it.grant}", it.action)
        }
    }

    @Test
    fun `every missing grant has a way to fix it`() {
        // The whole failure this page answers: a grant that is missing with no
        // route back. A null action here IS that bug.
        PermissionInventory.rows(allHeld(false)).forEach {
            assertTrue("no way back for ${it.grant}", it.action != null)
        }
    }

    @Test
    fun `a grant absent from the map counts as missing, never as held`() {
        // Same rule as the Condition parser and the stage label: unknown is not
        // a default. Reading absence as "granted" would hide exactly the state
        // this page is for.
        val rows = PermissionInventory.rows(emptyMap())
        assertTrue(rows.none { it.held })
        assertEquals(Grant.entries.size, PermissionInventory.missingCount(emptyMap()))
    }

    @Test
    fun `the action says whether it leaves the app`() {
        // A settings trip worded like a dialog reads as a bug the first time it
        // throws you out of the app mid-tap.
        Grant.entries.forEach { g ->
            val expected = if (g.how == How.DIALOG) "Grant" else "Open settings"
            assertEquals("wrong action for $g", expected, PermissionInventory.actionFor(g))
        }
    }

    @Test
    fun `the two that cannot be asked for are the two that go missing quietly`() {
        // Named explicitly rather than derived, because getting this wrong sends
        // someone to a settings screen for a permission that has a dialog, or
        // offers a dialog for a binding that has none — and the second failure
        // is a button that does nothing.
        assertEquals(How.SETTINGS_TRIP, Grant.NOTIFICATION_ACCESS.how)
        assertEquals(How.SETTINGS_TRIP, Grant.USAGE_ACCESS.how)
        assertEquals(How.DIALOG, Grant.BLUETOOTH_CONNECT.how)
        assertEquals(How.DIALOG, Grant.POST_NOTIFICATIONS.how)
        assertEquals(How.DIALOG, Grant.RECORD_AUDIO.how)
    }

    @Test
    fun `ordered by how badly the pet is broken without it`() {
        // The same rule as PetFaculty and the settings index. Bluetooth first
        // because nothing works at all without it; the microphone last because
        // the pet has its own and this is only the phone fallback.
        assertEquals(Grant.BLUETOOTH_CONNECT, Grant.entries.first())
        assertEquals(Grant.RECORD_AUDIO, Grant.entries.last())
        // Screen time outranks both notification grants: it is the mechanic the
        // product is about, and without it the pet cannot fall ill at all.
        assertTrue(Grant.USAGE_ACCESS.ordinal < Grant.POST_NOTIFICATIONS.ordinal)
        assertTrue(Grant.USAGE_ACCESS.ordinal < Grant.NOTIFICATION_ACCESS.ordinal)
    }

    @Test
    fun `rows come back in that order regardless of what is held`() {
        // A list that reorders itself as grants land would move the row someone
        // is reaching for.
        val mixed = mapOf(Grant.BLUETOOTH_CONNECT to false, Grant.RECORD_AUDIO to true)
        assertEquals(Grant.entries.toList(), PermissionInventory.rows(mixed).map { it.grant })
    }

    @Test
    fun `the summary counts rather than saying some are missing`() {
        assertEquals(
            "Everything the pet needs has been granted.",
            PermissionInventory.summary(allHeld(true)),
        )
        val one = allHeld(true) + (Grant.USAGE_ACCESS to false)
        assertTrue(PermissionInventory.summary(one), PermissionInventory.summary(one).startsWith("One thing"))
        val two = one + (Grant.RECORD_AUDIO to false)
        assertTrue(PermissionInventory.summary(two), PermissionInventory.summary(two).startsWith("2 things"))
    }

    @Test
    fun `every grant says what breaks, in the owner's terms`() {
        // "Permission denied" is our word for it, not theirs. Each line has to
        // survive being read by somebody deciding whether they care.
        Grant.entries.forEach { g ->
            assertTrue("${g.name} has no consequence", g.consequence.isNotBlank())
            assertTrue("${g.name} says 'permission'", !g.consequence.contains("permission", true))
            assertTrue("${g.name} has no title", g.title.isNotBlank())
        }
    }
}
