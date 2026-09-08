package com.digitalpet.conversation

import com.digitalpet.conversation.PetAnnouncement.Item
import com.digitalpet.conversation.PetAnnouncement.Silence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the pet speaks about your phone, and what it says.
 *
 * **This is the part that decides whether the feature is delightful or the
 * reason someone unplugs the pet.** A creature that says "hey, there's one from
 * Gmail" is the product working; the same creature narrating a busy morning
 * continuously is a thing you switch off within a day. Every rule below exists
 * to keep it on the first side of that.
 */
class PetAnnouncementTest {

    private fun decide(
        items: List<Item>,
        now: Long = 100_000L,
        last: Long? = null,
        quiet: Boolean = false,
        dead: Boolean = false,
        enabled: Boolean = true,
    ) = PetAnnouncement.decide(items, now, last, quiet, dead, enabled)

    private fun gmail(n: Int) = List(n) { Item("Gmail", "gmail-$it") }

    // ---- what it says ------------------------------------------------------

    @Test
    fun `one from one app names the app`() {
        assertEquals(
            "Hey! There's one new notification from Gmail.",
            PetAnnouncement.line(gmail(1)),
        )
    }

    @Test
    fun `several from one app still names it, and counts in words`() {
        // Words rather than digits: this goes through a speech synthesiser, and
        // the pet has a voice rather than a display.
        assertEquals(
            "Hey! There are three new notifications from Gmail.",
            PetAnnouncement.line(gmail(3)),
        )
    }

    @Test
    fun `SEVERAL APPS ARE NOT LISTED ALOUD`() {
        /*
         * The privacy rule, and the reason it is a rule rather than a
         * preference: the pet SPEAKS, in a room, where other people are. "Three
         * new notifications from Gmail, Slack and Hinge" reads your life out to
         * whoever is present. The count alone is the same information minus the
         * part that is nobody else's business.
         */
        val mixed = listOf(Item("Gmail"), Item("Slack"), Item("Hinge"))
        val line = PetAnnouncement.line(mixed)
        assertEquals("Hey! There are three new notifications.", line)
        listOf("Gmail", "Slack", "Hinge").forEach {
            assertFalse("$it was spoken aloud", line.contains(it))
        }
    }

    @Test
    fun `a blank app label never becomes an empty name`() {
        // Some notifications have no resolvable label. "from " is worse than a
        // plain count, and it is the sort of thing that only shows up on a real
        // phone at an inconvenient moment.
        val line = PetAnnouncement.line(listOf(Item("")))
        assertFalse("spoke an empty app name", line.contains("from "))
    }

    @Test
    fun `AN UPDATED NOTIFICATION IS NOT A SECOND ONE`() {
        /*
         * The bug this exists for, seen on a real phone: the pet announced two
         * from Gmail while the phone showed one.
         *
         * Android fires onNotificationPosted again every time a notification is
         * UPDATED, with the SAME key. An app touching its own notification —
         * Gmail does, on sync — arrived here as a fresh one, and the count was
         * simply whatever the burst contained.
         */
        val sameTwice = listOf(Item("Gmail", "k1"), Item("Gmail", "k1"))
        assertEquals(
            "Hey! There's one new notification from Gmail.",
            PetAnnouncement.line(sameTwice),
        )
    }

    @Test
    fun `distinct keys still count separately`() {
        // The other half, and the reason this is dedupe rather than "announce
        // one at a time": two real emails are two.
        assertEquals(
            "Hey! There are two new notifications from Gmail.",
            PetAnnouncement.line(listOf(Item("Gmail", "k1"), Item("Gmail", "k2"))),
        )
    }

    @Test
    fun `an unkeyed item is counted on its own`() {
        // Nothing to tell them apart by, so collapsing them would UNDER-count —
        // which is the worse direction to be wrong in: a missed notification is
        // invisible, a doubled one is merely annoying.
        assertEquals(
            "Hey! There are two new notifications from Gmail.",
            PetAnnouncement.line(listOf(Item("Gmail", ""), Item("Gmail", ""))),
        )
    }

    // ---- when it stays quiet ------------------------------------------------

    @Test
    fun `THE PET RESPECTS ITS OWN QUIET HOURS`() {
        /*
         * QUIET HOURS, not dozing — and the difference reached the user. This
         * parameter was called `asleep` and the firmware filled it from a FIVE
         * MINUTE inactivity timer, so the phone held every announcement and
         * every spoken call around the clock while the pet beeped normally: it
         * asked for help audibly and silently at the same time.
         *
         * It comes from the pet over Condition, not from a second set of hours
         * invented on the phone. The pet owns when it is quiet.
         *
         * It could not be enforced pet-side instead: the pet cannot tell an
         * ANSWER from an ANNOUNCEMENT when the phone asks it to speak. Talk to
         * it at 3am and you want a reply — you do not want your email read out.
         */
        assertEquals(Silence.QUIET, decide(gmail(1), quiet = true).why)
    }

    @Test
    fun `A DEAD PET DOES NOT TALK`() {
        // The one state that cannot be undone. A corpse cheerfully reading out
        // your inbox would undo it.
        assertEquals(Silence.DEAD, decide(gmail(1), dead = true).why)
    }

    @Test
    fun `DEAD OUTRANKS QUIET, so the reason given is the true one`() {
        // Both silence it, but they are different facts and a future caller may
        // treat them differently — a sleeping pet will speak later, a dead one
        // will not. Ordering it the other way would report the recoverable
        // cause for the unrecoverable state.
        assertEquals(Silence.DEAD, decide(gmail(1), quiet = true, dead = true).why)
    }

    @Test
    fun `IT WILL NOT TALK OVER ITSELF`() {
        /*
         * The floor between announcements, and the single most important rule
         * here. Without it a busy morning is continuous narration, and the
         * first thing anyone does is turn the feature off — so this is what
         * keeps it switched on.
         */
        val last = 100_000L
        assertEquals(
            Silence.TOO_SOON,
            decide(gmail(1), now = last + PetAnnouncement.MIN_GAP_MS - 1, last = last).why,
        )
        assertTrue(
            decide(gmail(1), now = last + PetAnnouncement.MIN_GAP_MS, last = last).spoke,
        )
    }

    @Test
    fun `the first announcement is never too soon`() {
        // null means it has not spoken yet. Treating that as "0 ms ago" would
        // silence the very first one, which is the one most likely to be
        // noticed missing.
        assertTrue(decide(gmail(1), now = 0L, last = null).spoke)
    }

    @Test
    fun `off means off`() {
        assertEquals(Silence.DISABLED, decide(gmail(1), enabled = false).why)
    }

    @Test
    fun `nothing waiting is not an announcement`() {
        assertEquals(Silence.NOTHING_WAITING, decide(emptyList()).why)
    }

    @Test
    fun `EVERY SILENCE HAS A DISTINCT REASON`() {
        // "It did not speak" has five causes needing five different fixes —
        // grant access, wait, wake the pet, start a new pet, turn it on. A
        // single boolean would have made every one of them look like a bug.
        val reasons = listOf(
            decide(gmail(1), enabled = false).why,
            decide(emptyList()).why,
            decide(gmail(1), dead = true).why,
            decide(gmail(1), quiet = true).why,
            decide(gmail(1), now = 1L, last = 0L).why,
            decide(gmail(1)).why,
        )
        assertEquals("two paths report the same reason", reasons.size, reasons.toSet().size)
    }
}
