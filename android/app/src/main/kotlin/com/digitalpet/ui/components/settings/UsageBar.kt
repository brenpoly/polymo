package com.digitalpet.ui.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.digitalpet.data.ScreenTimeDisplay
import com.digitalpet.ui.theme.PetRadius
import com.digitalpet.ui.theme.PetSize
import com.digitalpet.ui.theme.PetTheme

/**
 * The day against one sitting's allowance: one bar, one meaning.
 *
 * **Length is how far through the allowance you are, and nothing else.** It used
 * to be scaled to whichever of the two numbers was larger so that the part past
 * the limit could be drawn in red inside it — which meant going *further* over
 * made the amber portion shrink. Reported as difficult to interpret, and it was:
 * the same bar length meant two different things depending on which side of the
 * limit you were on.
 *
 * So: a **grey track** it can be read against, and a fill that is primary until
 * the bar is full and red once it is. Length says how far through the allowance
 * you are; colour says whether it ran out. *How far* past is the fraction's job,
 * which is the one place that can say it without running out of width.
 *
 * **Red arrives when the bar completely fills, which is at the allowance and not
 * past it** — the same `>=` `ScreenTime.overusingPackage` uses, so the bar turns
 * red at the moment the pet actually starts being made ill rather than a
 * millisecond after. [ScreenTimeDisplay.hasSpentAllowance] derives that from the
 * fill itself, so "full" and "red" cannot come apart.
 *
 * This keeps the design's "the over-limit portion is the only place a bar goes
 * red" in spirit while dropping its mechanism: the split it described is what
 * made the bar unreadable.
 */
@Composable
fun UsageBar(usedMs: Long, allowanceMs: Long) {
    val fraction = ScreenTimeDisplay.barFraction(usedMs, allowanceMs)
    val spent = ScreenTimeDisplay.hasSpentAllowance(usedMs, allowanceMs)
    Box(
        Modifier
            .fillMaxWidth()
            .height(PetSize.usageBarHeight)
            // A pill, and it always was one: this drew a 4dp radius on an 8dp
            // box, which is half its height. Saying `pill` removes the arithmetic
            // rather than the rounding.
            .clip(PetRadius.pill)
            /*
             * A track a shade off its own background is not a track — the card
             * is already `surface`. That argument was right and the colour it
             * picked, `outlineVariant`, still only reached **1.38:1** against
             * the gold fill in light: both are pale, and "darker than the card"
             * is not the same requirement as "the fill can be read against it".
             *
             * `PetColors.track` is the requirement stated properly, and the
             * meter now shares it — see there for why the light one is dark.
             */
            .background(PetTheme.colors.track)
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxSize()
                    .clip(PetRadius.pill)
                    .background(
                        if (spent) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
            )
        }
    }
}
