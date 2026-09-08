package com.digitalpet.ui.screens.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat sheet's arithmetic, which is the part of it that was wrong.
 *
 * Nine rounds of correction on this sheet produced exactly one kind of finding:
 * a layout that was perfectly valid and simply wrong on a screen, found by a
 * thumb and by nothing else. Three of those rounds were *arithmetic* — a preview
 * height minus a footer clearance leaving 8dp of viewport, a guessed 112dp
 * against a footer that changes size — and arithmetic is the part that does not
 * need a phone to check.
 *
 * So this file is not a claim that the sheet feels right. It is the narrower
 * claim that the numbers it is placed with mean what they say, which is the half
 * that a build can be held to.
 *
 * Everything is in pixels with y increasing downward, and a phone-ish set of
 * values is used throughout so the assertions can be read as positions on a
 * screen rather than as algebra.
 */
class ChatSheetGeometryTest {

    private val root = 2000f       // window height
    private val content = 1400f    // the sheet's fixed content height
    private val footer = 240f      // input bar, no suggestion row
    private val handle = 120f

    /** Where the sheet's content starts when it is closed. */
    private val collapsedTop = root - 530f + handle   // peek 530, content below the handle

    /** Where it starts when fully open: content bottom on the window bottom. */
    private val expandedTop = root - content

    /** The band of sheet actually visible when closed — above the footer. */
    private val band = (root - footer) - collapsedTop

    private fun translation(newest: Float, contentTop: Float = collapsedTop) =
        ChatSheetGeometry.translation(
            rootBottom = root,
            contentTop = contentTop,
            contentHeight = content,
            footer = footer,
            newest = newest,
        )

    /**
     * Where the newest message's bottom edge lands on the screen, given the
     * translation. The list stops [footer] short of the content's bottom, so the
     * newest message's bottom edge is at `content - footer` in content
     * coordinates.
     */
    private fun newestBottomOnScreen(newest: Float, contentTop: Float = collapsedTop) =
        contentTop + (content - footer) + translation(newest, contentTop)

    private fun newestTopOnScreen(newest: Float, contentTop: Float = collapsedTop) =
        newestBottomOnScreen(newest, contentTop) - newest

    // ---- the two jobs ------------------------------------------------------

    @Test
    fun `a short reply rests on the footer rather than under it`() {
        // The failure this replaces: a fixed preview height sized against a
        // footer with no suggestion row in it, so the message it existed to show
        // was covered by the footer whenever there was a suggestion.
        val short = band / 2
        assertEquals(root - footer, newestBottomOnScreen(short), 0.01f)
    }

    @Test
    fun `a long reply is shown from its top, not its tail`() {
        // The reported fault: a reverseLayout list anchors its newest item to
        // the bottom of its viewport, so an over-long bubble lost its beginning
        // and you were shown the end of a sentence with no start.
        val long = band * 3
        assertEquals(collapsedTop, newestTopOnScreen(long), 0.01f)
    }

    @Test
    fun `a long reply runs under the footer instead of off the top`() {
        val long = band * 3
        assertTrue(
            "the tail should be below the footer line, waiting to be dragged up",
            newestBottomOnScreen(long) > root - footer
        )
        assertTrue(
            "nothing of it should be above the sheet",
            newestTopOnScreen(long) >= collapsedTop - 0.01f
        )
    }

    @Test
    fun `the two rules meet where the message stops fitting`() {
        // The band's height is nowhere in the implementation; it emerges from
        // which of the two terms is larger. This is that crossover, and it is
        // why there is no third number to keep in step.
        assertEquals(translation(band - 1f), translation(band), 0.01f)
        assertEquals(band, newestBottomOnScreen(band) - newestTopOnScreen(band), 0.01f)
        assertEquals(root - footer, newestBottomOnScreen(band), 0.01f)
        assertEquals(collapsedTop, newestTopOnScreen(band), 0.01f)
    }

    // ---- through the drag --------------------------------------------------

    @Test
    fun `a fully open sheet does not move its transcript at all`() {
        // Whatever else happens, an expanded sheet is just a list. If this is
        // ever non-zero, history is being clipped off the top of an open sheet.
        assertEquals(0f, translation(newest = 80f, contentTop = expandedTop), 0.01f)
        assertEquals(0f, translation(newest = 900f, contentTop = expandedTop), 0.01f)
    }

    @Test
    fun `the newest message holds still all the way up the drag`() {
        // The point of following the drag rather than swapping sizes when it
        // settles: the message you were reading stays where it is and history
        // fills in above it. A sheet dragged half way must not move it.
        val short = band / 2
        val half = (collapsedTop + expandedTop) / 2
        assertEquals(root - footer, newestBottomOnScreen(short, half), 0.01f)
        assertEquals(root - footer, newestBottomOnScreen(short, expandedTop), 0.01f)
    }

    @Test
    fun `a long reply reveals itself downward as the sheet opens`() {
        val long = band * 3
        // How much of the bubble is above the footer line, i.e. readable.
        val visible = { top: Float -> (root - footer) - newestTopOnScreen(long, top) }
        val closed = visible(collapsedTop)
        val part = visible(collapsedTop - (collapsedTop - expandedTop) / 4)
        val open = visible(expandedTop)
        assertEquals("closed, you get exactly the band", band, closed, 0.01f)
        assertTrue("dragging open must show more of it, not less", part > closed)
        assertTrue(open > part)
        assertEquals("and it stops once the whole reply is out", long, open, 0.01f)
    }

    @Test
    fun `the transcript is never pushed down past where it was laid out`() {
        // A sheet dragged BELOW its collapsed rest — which happens while it
        // settles, and on an over-scroll — must not open a gap at the top of the
        // sheet by translating the list downward into it.
        assertTrue(translation(newest = 80f, contentTop = collapsedTop + 400f) <= 0f)
        assertTrue(translation(newest = 80f, contentTop = expandedTop - 400f) <= 0f)
    }

    // ---- the states that are not a conversation ----------------------------

    @Test
    fun `an empty transcript is placed as if the newest message were nothing`() {
        // newest is 0 before the first message and for one frame after each new
        // one arrives, while the bubble's entry animation is still measuring. It
        // must fall back to resting on the footer, not to something arbitrary.
        assertEquals(root - footer, newestBottomOnScreen(0f), 0.01f)
    }

    @Test
    fun `a reply longer than the whole transcript does not break the open sheet`() {
        // THE KNOWN LIMIT, pinned deliberately. A bubble taller than the list's
        // own viewport cannot be shown from its top, because doing so means
        // translating DOWNWARD — which would leave that gap at the top of the
        // sheet in every position, expanded included, and put history out of
        // reach. The clamp chooses the open sheet over the peek. Such a reply
        // needs scrolling whatever we do: even fully expanded the list anchors
        // its newest item to the bottom and the beginning is off-screen.
        val huge = content * 2
        assertEquals(0f, translation(huge), 0.01f)
        assertEquals(0f, translation(huge, expandedTop), 0.01f)
    }

    @Test
    fun `a taller footer takes the space from the band, never from the message`() {
        // The suggestion row appearing makes the footer taller. The message must
        // move up with it rather than disappear behind it — this is the exact
        // case the old fixed 112dp preview got wrong, and it got it wrong
        // silently, by covering the one thing the peek is for.
        val tall = footer + 100f
        val bottom = collapsedTop + (content - tall) + ChatSheetGeometry.translation(
            rootBottom = root,
            contentTop = collapsedTop,
            contentHeight = content,
            footer = tall,
            newest = 60f,
        )
        assertEquals(root - tall, bottom, 0.01f)
    }
}
