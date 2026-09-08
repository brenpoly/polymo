package com.digitalpet.pet

import com.digitalpet.pet.NotificationAccess.Next
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether the recovery button does anything.
 *
 * `shouldShowRequestPermissionRationale` is false in two OPPOSITE cases —
 * never asked, and permanently blocked — so every case below is a real device
 * state that the naive "just call launch()" version gets wrong on exactly the
 * phone that needs it.
 */
class NotificationAccessTest {

    @Test
    fun `a held permission draws no row at all`() {
        assertEquals(Next.NOTHING, NotificationAccess.next(granted = true, rationale = false, asked = false))
        assertEquals(Next.NOTHING, NotificationAccess.next(granted = true, rationale = true, asked = true))
        // No label means no row. That is how the card knows not to draw.
        assertNull(NotificationAccess.action(Next.NOTHING))
    }

    @Test
    fun `never asked — rationale false — still offers the dialog`() {
        // THE CASE FOUND ON THE DEVICE: the permission had never been answered,
        // so rationale was false. Reading that as "blocked" would send someone
        // to a settings screen when a dialog was one tap away.
        assertEquals(Next.ASK, NotificationAccess.next(granted = false, rationale = false, asked = false))
    }

    @Test
    fun `declined once — rationale true — offers the dialog again`() {
        // Android promises another dialog in exactly this state, so asking is
        // the honest offer.
        assertEquals(Next.ASK, NotificationAccess.next(granted = false, rationale = true, asked = false))
        assertEquals(Next.ASK, NotificationAccess.next(granted = false, rationale = true, asked = true))
    }

    @Test
    fun `asked, no dialog, no rationale — the only route left is settings`() {
        // Blocked. The escalation is the whole point: without it this is a
        // button that visibly does nothing, which teaches that the fix is
        // broken rather than that the permission is.
        assertEquals(
            Next.OPEN_SETTINGS,
            NotificationAccess.next(granted = false, rationale = false, asked = true),
        )
    }

    @Test
    fun `the label says where the tap goes`() {
        // Leaving the app without warning reads as a bug the first time.
        assertEquals("Turn notifications on", NotificationAccess.action(Next.ASK))
        assertEquals("Open notification settings", NotificationAccess.action(Next.OPEN_SETTINGS))
    }

    @Test
    fun `the copy names what is lost, not what is missing`() {
        // "A permission is not granted" is a fact about the phone; the pet
        // going unseen is the thing somebody would mind.
        assertNotNull(NotificationAccess.TITLE)
        assert(NotificationAccess.TITLE.contains("pet"))
        assert(NotificationAccess.BODY.contains("condition"))
        // It has been running all along, and saying so is what stops this
        // reading as "the pet was broken".
        assert(NotificationAccess.BODY.contains("running"))
    }

    @Test
    fun `every reachable state produces exactly one next step`() {
        // A truth table rather than four assertions: a fifth state added later
        // without a rule falls through to whatever `else` says, and this is
        // what notices.
        val seen = listOf(false, true).flatMap { granted ->
            listOf(false, true).flatMap { rationale ->
                listOf(false, true).map { asked ->
                    NotificationAccess.next(granted, rationale, asked)
                }
            }
        }
        assertEquals(8, seen.size)
        assertEquals(4, seen.count { it == Next.NOTHING })
        assertEquals(3, seen.count { it == Next.ASK })
        assertEquals(1, seen.count { it == Next.OPEN_SETTINGS })
    }

    // ---- the other half: notification access, which has no dialog -----------

    @Test
    fun `a held listener binding draws nothing`() {
        assertNull(NotificationAccess.listenerAction(bound = true))
    }

    @Test
    fun `a missing listener binding offers the only route there is`() {
        // No escalation to get wrong here, unlike the permission: there is no
        // API to request this, so settings is the first and only answer.
        assertEquals("Open notification access", NotificationAccess.listenerAction(bound = false))
    }

    @Test
    fun `the copy says WHY this one goes missing quietly`() {
        // The distinguishing fact, and the reason it was skipped: there is no
        // dialog for it. Someone who has just granted the permission above will
        // otherwise assume this is the same thing and that it worked.
        assertTrue(NotificationAccess.LISTENER_BODY, NotificationAccess.LISTENER_BODY.contains("no dialog"))
        assertTrue(NotificationAccess.LISTENER_TITLE.contains("pet"))
    }

    @Test
    fun `the announce switch is not drawn while it cannot work`() {
        // It read from a preference alone and sat ON above a feature that could
        // not physically run — the Ears card's bug in another slot. Absent
        // rather than drawn refusing, per the loaded-row rule.
        assertTrue(!NotificationAccess.announceSwitchIsUseful(bound = false))
        assertTrue(NotificationAccess.announceSwitchIsUseful(bound = true))
    }

    @Test
    fun `the two halves are independent and say different things`() {
        // They are separate grants of different KINDS. Collapsing them into one
        // message would send someone to a settings screen for a permission that
        // has a dialog, or offer a dialog for a binding that has none.
        assertTrue(NotificationAccess.TITLE != NotificationAccess.LISTENER_TITLE)
        assertTrue(NotificationAccess.BODY != NotificationAccess.LISTENER_BODY)
        assertTrue(
            "the two actions must not read the same",
            NotificationAccess.action(Next.OPEN_SETTINGS) !=
                NotificationAccess.listenerAction(bound = false),
        )
    }
}
