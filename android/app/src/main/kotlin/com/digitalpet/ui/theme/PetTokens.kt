package com.digitalpet.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * THE NUMBERS, IN ONE PLACE — the fourth style layer DESIGN.md §6.1 was missing.
 *
 * §6.1 named three layers a value may come from (`ColorScheme`, `PetColors`,
 * `Typography`/`Shapes`) and said reaching past them for a literal is the
 * mistake it exists to prevent. It had no layer for *space*, so every dp in this
 * app was a literal at its point of use — 277 of them — and DESIGN.md §7.7 is
 * the write-up of what that cost: nothing could tell an 8 that should be a token
 * from a 9 that was a typo, and nothing stopped one value being applied twice.
 * The transcript's double 16dp gutter is the worked example.
 *
 * **This file mirrors `tokens/spacing.css` and `tokens/shape.css` from the
 * Claude Design system** (DESIGN.md §7.1 has how to read
 * it). Both were generated from THIS code, so nothing here is new — it is the
 * same numbers, named, in one file that can be diffed against the design's.
 *
 * ### The division is the CSS's own, and it is load-bearing
 *
 * `spacing.css` has two blocks: a numeric scale with a comment admitting *"no
 * scale was ever chosen; these are the numbers that happened"*, and a second
 * block headed *"load-bearing geometry — moving these changes behaviour, not
 * appearance"*. That is exactly the distinction a reader needs, so it is kept:
 * [PetSpacing] is the ladder, [PetSize] is the geometry, and the doc comment on
 * a [PetSize] value says what breaks if it moves.
 *
 * ### Why the ladder is numbers and not names
 *
 * Naming every value for its use — `cardPaddingX`, `chipGap` — makes each name a
 * claim about where the value appears, and those claims go stale the first time
 * a second component uses one. The CSS chose numbers with comments and it was
 * right to. What a numeric ladder buys is the thing §7.7 actually asked for:
 * **a value that is not on it cannot be written without adding it here**, which
 * is a visible act in a diff rather than an invisible one at a call site.
 * `PetLiteralsTest` is what makes that true rather than merely intended.
 *
 * The one exception is [PetSpacing.screenMargin]. 18 is the dominant number in
 * this app and it means one thing everywhere it appears, so it gets the name as
 * well as the rung.
 */

/**
 * Gaps and padding — `tokens/spacing.css`, first block.
 *
 * **Not a designed scale.** It is the set of values this app already used,
 * collected. It is close to a 2dp grid and is not one; 3, 5, 7, 9 and 11 all
 * appear, and pretending otherwise by rounding them would be a redesign smuggled
 * in as a refactor. If a genuine scale is ever chosen, this is the file that
 * changes and the call sites do not.
 */
object PetSpacing {
    val s2 = 2.dp
    val s3 = 3.dp
    val s4 = 4.dp
    val s5 = 5.dp
    val s6 = 6.dp
    val s7 = 7.dp
    val s8 = 8.dp
    val s10 = 10.dp
    val s11 = 11.dp
    val s12 = 12.dp
    val s13 = 13.dp
    val s14 = 14.dp
    val s16 = 16.dp
    val s18 = 18.dp
    val s20 = 20.dp
    val s24 = 24.dp
    val s32 = 32.dp

    /**
     * **The screen margin**, and the one value here that earns a name.
     *
     * Every page in this app is inset by this and the design system's own
     * spacing file calls it out in capitals for the same reason. A screen that
     * uses anything else is either a bug or a decision that needs writing down.
     */
    val screenMargin = s18

    /**
     * How far the last item in a scrolling page clears the bottom edge.
     *
     * Two values rather than one because the screen-time list has a FAB over it
     * and the settings pages do not, and a list that stops under a floating
     * button is the same fault as one that stops under the gesture bar.
     */
    val scrollBottom = s24
    val scrollBottomUnderFab = 100.dp
}

/**
 * Corner radii — `tokens/shape.css`.
 *
 * `MaterialTheme.shapes` still holds `small`/`medium`/`large` (8/16/24) and is
 * still what an M3 component reads; this covers the radii the screens draw
 * directly, which Material has no opinion about.
 */
object PetRadius {
    val r4 = 4.dp
    val r6 = 6.dp
    val r7 = 7.dp
    val r8 = 8.dp
    val r11 = 11.dp
    val r12 = 12.dp
    val r14 = 14.dp
    val r16 = 16.dp
    val r18 = 18.dp
    val r20 = 20.dp
    val r24 = 24.dp
    val r30 = 30.dp
    val r34 = 34.dp
    val r40 = 40.dp

    /** The asymmetric corner that marks who is speaking. See `MessageBubble`. */
    val bubbleTail = r6

    /** The chat sheet's top edge. */
    val sheetTop = r30

    /** The pet panel. Paired with [PetSize.petPanelWidth] — see there. */
    val petPanel = r34

    /**
     * Fully rounded: chips, steppers, the settings button, the state pill.
     *
     * **Preferred over "half the height" wherever the height is fixed**, because
     * a written-down half has to be kept in step with the height and this cannot
     * come apart. Where it replaced a number the two render identically — a 40dp
     * chip at radius 20 and the same chip fully rounded are the same shape — so
     * this is a durability change, not a visual one.
     *
     * It is deliberately NOT applied to the pet's eye, which is 22dp wide and
     * animates its height down to 4: a pill there would round the wrong axis at
     * the bottom of the blink.
     */
    val pill: Shape = RoundedCornerShape(percent = 50)
}

/**
 * Fixed dimensions — `tokens/spacing.css`, second block, plus the control sizes
 * the components draw.
 *
 * **Moving one of these changes behaviour, not appearance.** Each says what
 * breaks. This is the block to read before adjusting a component's size, and the
 * reason it is separated from [PetSpacing]: a padding is a matter of taste and
 * most of these are not.
 */
object PetSize {
    /**
     * **The minimum touch target, everywhere** — DESIGN.md §6 and §7.5.
     *
     * Where a component's drawn size and this conflict, **this wins**, and the
     * component draws smaller than it is tappable. The recorded exception is the
     * state pill on a model row; see `AlternativeRow`.
     */
    val touchMin = 48.dp

    /**
     * Icons, by the sizes this app actually uses. §5a of the design system says
     * 18–24; [icon14] and [icon16] are the two that sit *inside* a line of text
     * rather than beside it — the battery glyph in the care card and the tick in
     * a selection box — and are sized to the type, not to the icon scale.
     */
    val icon14 = 14.dp
    val icon16 = 16.dp
    val icon18 = 18.dp
    val icon20 = 20.dp
    val icon24 = 24.dp

    /** The round settings button in the header. Its radius is [PetRadius.pill]. */
    val headerButton = 48.dp

    /** A status chip, and the search field that sits at the same height. */
    val chipHeight = 40.dp

    /** A stepper's round nudge. Both allowance steppers use it. */
    val stepButton = 36.dp

    /** One row in the add-apps list. */
    val appRowHeight = 52.dp

    /** The selection checkbox in that list. */
    val checkbox = 22.dp

    /**
     * How wide the allowance reading is held, as a MINIMUM.
     *
     * The point is that stepping does not move the two buttons either side of
     * it under a finger that is still tapping. The reading is centred in this
     * box rather than start-aligned in it — without that the slack all falls on
     * one side and a short reading sits against the minus button, which is what
     * `5 min` did once the default stopped being `25 min`.
     *
     * **This used to say "Fixed", and the code has always been `widthIn(min =)`.**
     * It is a floor, not a width: a reading wider than this grows the box and
     * does move the buttons. Nothing in the current range does — `120 min` is
     * the longest and fits — so the promise holds today by measurement rather
     * than by construction. Raising [ScreenTimeDisplay.MAX_MINUTES] into four
     * digits is what would break it.
     */
    val allowanceWidth = 56.dp

    /** The add-apps list, bounded so the sheet cannot grow past the screen. */
    val appListMaxHeight = 360.dp

    /**
     * A meter segment's height, and the gap between segments.
     *
     * Four segments, wholly filled or wholly empty — **never partial**, because
     * the scores are integers 0–4 in the firmware and on the wire. DESIGN.md §1.
     */
    val meterSegmentHeight = 9.dp

    /** The usage bar. Its track and fill are the same box at the same height. */
    val usageBarHeight = 8.dp

    /** One dot of the typing indicator. Three of them, staggered. */
    val typingDot = 8.dp

    /**
     * **No elevation.** The design system is categorical: *"there are no drop
     * shadows anywhere in the app — depth comes from the tonal ladder and from
     * the bottom sheet physically overlapping the content."*
     *
     * A token rather than a bare zero because it is a *decision* that has to be
     * stated at every Material component that would otherwise cast one, and a
     * `0.dp` at a call site reads like a value someone tuned to nothing. Only
     * the FAB needs it today; the next component that does will find this.
     */
    val flat = 0.dp

    /**
     * The ring that marks the loaded model, and the only border width here.
     *
     * A ring rather than a fill, so the loaded row stays the same *kind* of row
     * as its neighbours with one fact added. 1.5 rather than 1 because at 1 it
     * reads as a divider on the cream surfaces.
     */
    val ringStroke = 1.5.dp

    /**
     * The widest a message bubble may get.
     *
     * A line length rather than a proportion: the transcript's width changes with
     * the sheet's insets and a percentage would make long lines longer on a
     * tablet, which is the opposite of what a max width is for.
     */
    val bubbleMaxWidth = 280.dp

    /**
     * The pet panel — **240×200 at [PetRadius.petPanel], the Waveshare's
     * proportion as the pet shows it: 448×368.**
     *
     * This said "368×448" until the firmware rotation landed, and that was
     * backwards rather than approximate. The panel is wired portrait and the
     * firmware turns it 90°, so the screen is landscape: 1.22, against this
     * panel's 1.20. Portrait would be 0.82 — 46% out — so the mocks had clearly
     * always been drawn for the rotated device, and the comment was quoting the
     * hardware instead of the product. `PetFaceTest` now checks the ratio.
     *
     * It is a picture of the physical thing, so the three numbers move together
     * or not at all. See DESIGN.md §7.5.
     */
    val petPanelWidth = 240.dp
    val petPanelHeight = 200.dp

    /** The pet's face, at the panel's scale. */
    val eyeWidth = 22.dp
    val eyeGap = 34.dp
    val eyeOpen = 32.dp
    val eyeClosed = 4.dp
    val faceGap = 14.dp
    val mouthWidth = 52.dp
    val mouthHeight = 22.dp
    val mouthDeadHeight = 4.dp
    val mouthStroke = 4.dp

    /**
     * **The chat sheet's peek, and it is bounded by the care card above it —
     * never by what the sheet contains.**
     *
     * **308 is 260 plus 24 twice, and the 24s are the BAND's, not the peek's.**
     * What anyone actually sees is the band — this, less the drag handle and
     * less the input footer drawn over the sheet — so a request for 24dp more
     * glimpse is a request for 24dp more peek, and the odd-looking total is the
     * honest translation of a round intention. The band is **147.6dp**, or
     * 123.6dp with a notification suggestion row in the footer.
     *
     * **Arrived at by thumb, in 24s, and that is the only way it could have
     * been.** It began at 260 (a 99.6dp band, about one bubble, too mean), went
     * to 320 as a single jump, and 320 was rejected on sight. Note where it has
     * landed: 12dp short of the rejected one. **The two are not the same
     * proposal** — 320 was the transcript asking for room on the grounds that it
     * could use it; this is the band being sized to hold one exchange, which is
     * what a glance at a conversation is. DESIGN.md §6.4a-iii.
     *
     * It is chosen rather than measured, deliberately. Computing it to fit the
     * newest message was tried on paper and rejected: that message grows a token
     * at a time while the pet answers, so the sheet would re-anchor on every
     * token. Where the message *sits* inside this is `ChatSheetGeometry`'s job.
     *
     * **The check when changing it is what the sheet is pushing into, never what
     * it holds.** At 308 the care card's last meter is ~112dp clear of the
     * sheet's top edge; it was ~100dp at 320.
     */
    val sheetPeek = 308.dp

    /**
     * **The sheet's content height, and it MUST be constant.**
     *
     * A bottom sheet's expanded height *is* its content height, so content that
     * shrank when collapsed would leave nothing to expand into — a drag handle
     * that correctly does nothing. It must also exceed [sheetPeek], for the same
     * reason. DESIGN.md §6.4a rule 2.
     *
     * Fixed rather than filling because there is nothing to fill against: a
     * sheet sizes itself to its content, and an unbounded lazy list inside one
     * is the infinite-constraint crash this project has already paid for twice.
     *
     * **The two numbers that used to live beside this one are gone** — a 112dp
     * preview height and a 96dp footer clearance, both guesses, both now
     * measured at the moment they are used. See `ChatSheetGeometry`.
     */
    val sheetContent = 560.dp
}

/**
 * The font sizes the screens override the type scale with.
 *
 * **This is a second type ladder and it should not exist.** `Type.kt` holds
 * Material's 15 styles and DESIGN.md §4 records the *scale* as deliberately
 * unwritten — so screens have been setting `fontSize` locally in half-point
 * steps on top of a style, which is a component reaching past the layer §6.1
 * points it at. Ten distinct sizes across four screens.
 *
 * **Collected rather than fixed, on purpose.** Folding these into `Type.kt`
 * means either moving Material's scale or adding styles beside it, and both are
 * typography decisions rather than a refactor — the same reason the palette
 * decision in §7.7 was asked rather than taken. Gathering them here makes the
 * question askable for the first time: these ten values are now visible as a
 * ladder, in one file, next to the one they are overriding.
 *
 * Until then this at least stops the ladder growing an eleventh rung by
 * accident, which is what a bare `12.5.sp` at a call site could always do.
 */
object PetTextSize {
    val t10 = 10.sp
    val t10_5 = 10.5.sp
    val t11 = 11.sp
    val t11_5 = 11.5.sp
    val t12 = 12.sp
    val t12_5 = 12.5.sp
    val t13 = 13.sp
    val t15 = 15.sp
    val t17 = 17.sp
    val t22 = 22.sp
}
