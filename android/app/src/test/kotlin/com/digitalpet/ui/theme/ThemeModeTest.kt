package com.digitalpet.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which scheme to show, and whether that is our decision.
 *
 * Small enough to look correct by reading, which is exactly why it is worth
 * pinning: the two overriding modes have to ignore the system, and "ignore" is
 * the kind of thing that quietly stops being true when a default is added or a
 * branch is reordered. The whole state space is six cases and they are all here.
 */
class ThemeModeTest {

    // ---- the two that must NOT listen to the phone --------------------------

    @Test
    fun `light stays light even when the phone is dark`() {
        assertFalse(ThemeMode.LIGHT.isDark(systemInDark = true))
        assertFalse(ThemeMode.LIGHT.isDark(systemInDark = false))
    }

    @Test
    fun `dark stays dark even when the phone is light`() {
        assertTrue(ThemeMode.DARK.isDark(systemInDark = false))
        assertTrue(ThemeMode.DARK.isDark(systemInDark = true))
    }

    // ---- the one that must ------------------------------------------------

    @Test
    fun `system is exactly what the phone says`() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemInDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemInDark = false))
    }

    // ---- what survives a restart -------------------------------------------

    @Test
    fun `every mode round-trips through storage`() {
        // Persisted by name, so this is the test that fails if a constant is
        // renamed without a migration — which would otherwise show up as
        // somebody's chosen theme silently reverting after an update.
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromStored(mode.name))
        }
    }

    @Test
    fun `an unset or unrecognised preference follows the phone`() {
        // Never a fixed scheme: a first run, a cleared preference and a value
        // left behind by an older build should all land on the setting the user
        // most likely wants, and that is the phone's.
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored("AUTOMATIC"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored("light"))
    }

    // ---- the list the screen draws -----------------------------------------

    @Test
    fun `following the phone is offered first and is the default`() {
        // Order is the screen's order. The default belongs at the top because it
        // is the one most people should leave alone.
        assertEquals(ThemeMode.SYSTEM, ThemeMode.entries.first())
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(null))
    }

    @Test
    fun `every mode has its own label`() {
        val labels = ThemeMode.entries.map { it.label }
        assertEquals(ThemeMode.entries.size, labels.toSet().size)
        assertTrue(labels.none { it.isBlank() })
        // The system option has to say it is deferring, or it reads as a third
        // colour scheme sitting beside Light and Dark.
        assertEquals("Use system setting", ThemeMode.SYSTEM.label)
    }
}
