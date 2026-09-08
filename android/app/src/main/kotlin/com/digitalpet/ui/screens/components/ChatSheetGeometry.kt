package com.digitalpet.ui.screens.components

/**
 * Where the transcript sits inside the chat sheet — the whole thing, as one
 * expression.
 *
 * **THIS REPLACED THREE GUESSES AND A SIZE SWAP.** The sheet used to hold two
 * differently-sized copies of the transcript — a 112 dp "preview" while
 * collapsed and a full-height list while expanded — and cross-faded between them
 * when the sheet settled. Every one of the chat sheet's remaining faults came out
 * of that arrangement:
 *
 * - the **112 dp was a guess**, and a wrong one whenever the suggestion row
 *   appeared: the input footer is drawn *over* the sheet, so a taller footer ate
 *   the bottom of the preview and hid the newest message it existed to show;
 * - the **swap flashed**, because a cross-fade draws both copies at once and
 *   they are different sizes;
 * - a **long reply was clipped at the top**, because a `reverseLayout` list
 *   anchors its newest item to the bottom of its viewport, so an over-long bubble
 *   loses its beginning — you were shown the end of a sentence with no start.
 *
 * Nothing here resizes now. There is **one list, always the same height**, moved
 * by a `graphicsLayer` translation — a draw-time transform, so it costs no
 * measurement and can follow the drag continuously instead of waiting to settle.
 * DESIGN.md §6.4a rules 2 and 3 are satisfied structurally rather than by care.
 *
 * ### The two things the translation is trying to do
 *
 * Both are expressed as "how far up must the list move", both are negative, and
 * the answer is whichever moves it *less* — so the more urgent one wins and the
 * result is continuous where they cross.
 *
 * 1. **Keep the newest message off the footer.** The list's bottom edge is its
 *    newest message; that edge belongs immediately above the input footer, and
 *    it should stay there through the whole drag. This term is what makes the
 *    newest message hold still while history fills in above it.
 * 2. **Show a long reply from its top.** When the newest bubble is taller than
 *    the visible band, term 1 would push its beginning off the top of the sheet.
 *    This term instead pins the bubble's *top* to the top of the sheet content,
 *    letting its tail run under the footer until the sheet is dragged open far
 *    enough to reveal it.
 *
 * The band's height never appears here and does not need to: term 2 overtakes
 * term 1 at exactly the point where the bubble stops fitting, whatever that
 * height happens to be. That is the sense in which the guess is gone — the
 * numbers are measured at the moment they are used (the window, the sheet's own
 * position, the footer, and the newest bubble), not chosen in advance.
 *
 * All values are pixels, in window coordinates, y increasing downward.
 */
object ChatSheetGeometry {

    /**
     * How far up to shift the transcript, in pixels (negative shifts up).
     *
     * @param rootBottom the bottom edge of the window.
     * @param contentTop the top edge of the sheet's content — i.e. just under the
     *   drag handle. Measured, so it follows the drag and needs no knowledge of
     *   the sheet's anchors, its peek height, or the handle's size.
     * @param contentHeight the height the sheet content **actually got**, which
     *   is not always the height it asked for. `Modifier.height()` is a request
     *   and is coerced into the parent's constraints, so a sheet taller than the
     *   space available — which is what happens the moment the keyboard opens —
     *   is silently clamped.
     *
     *   **This used to be documented as the REQUESTED height and described as
     *   "constant by design", and that reading cost a real bug.** Passing the
     *   request made this lift the list by an overhang that had already been
     *   clamped away: with the keyboard up the newest message floated ~450 px
     *   above the footer it exists to rest on. Measured — the box asked for
     *   1763 px and got 1192.
     *
     *   §6.4a rule 2 is untouched by this and is a different claim. The rule is
     *   that content must not shrink **as a function of sheet state**, or there
     *   is nothing to expand into; the sheet's anchors still come from a
     *   constant requested height. What varies here is the WINDOW, which the
     *   platform imposes and no amount of design intent can hold still.
     * @param footer the height of the input footer, which is drawn over the
     *   sheet rather than inside it.
     * @param newest the measured height of the newest message, 0 when there is
     *   not one yet.
     */
    fun translation(
        rootBottom: Float,
        contentTop: Float,
        contentHeight: Float,
        footer: Float,
        newest: Float,
    ): Float {
        // The list's own bottom edge already sits `footer` above the content's
        // bottom, so this is exactly the part of the content hanging below the
        // window: lift by that much and the newest message rests on the footer.
        val keepOffFooter = rootBottom - contentTop - contentHeight
        // The newest bubble occupies [contentHeight - footer - newest, ...] in
        // content coordinates; lifting by that start puts its top at the top of
        // the sheet.
        val showFromItsTop = newest + footer - contentHeight
        // Never push the list DOWN past where it was laid out: at full expansion
        // both terms reach 0 and the transcript is simply itself.
        return maxOf(keepOffFooter, showFromItsTop).coerceAtMost(0f)
    }
}
