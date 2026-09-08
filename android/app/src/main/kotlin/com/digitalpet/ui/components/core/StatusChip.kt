package com.digitalpet.ui.components.core

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.digitalpet.ui.theme.PetRadius
import com.digitalpet.ui.theme.PetSize
import com.digitalpet.ui.theme.PetSpacing
import com.digitalpet.ui.theme.PetTextSize
import com.digitalpet.ui.theme.PetTheme

/**
 * A 40dp chip that reports one fact and opens the place you fix it.
 *
 * **40dp is the design's number and it is pinned in both directions.**
 * `tokens/spacing.css` puts `--chip-height: 40px` in its load-bearing block,
 * `StatusChip.jsx` applies it as a `min-height`, `PetSize.chipHeight` mirrors it
 * and `TokenSyncTest` fails if the two ever disagree. A wrapped label does not
 * change it: two lines at `labelMedium`'s 16sp leading are 32dp, plus 8dp of
 * vertical padding, which is 39.1 — still under the 40 minimum.
 *
 * **An accessibility dump reports this chip as 48dp, and that is not its
 * height.** Compose derives a11y bounds from the *touch* box, which carries the
 * platform minimum touch target, so `uiautomator` shows 48 for something drawn
 * at 40. That is a general property of the instrument: it EXPANDS small touch
 * targets, so a 20dp icon reads as 48dp and looks like a layout fault that is
 * not there. It was briefly read here as an open question needing an
 * eye. It never did: the design says 40, the arithmetic says 40, and the 48 is
 * the instrument.
 *
 * That expansion is also why the 48dp minimum DESIGN.md §6 calls load-bearing is
 * satisfied by a 40dp pill rather than violated by one. There is no exception to
 * record here.
 *
 * [good] drives the colour rather than a separate "state" parameter, because
 * there are only two things a glance needs from these: fine, or not fine and
 * here is where to look.
 *
 * **Shared rather than duplicated as of 2026-08-07**, when the screen-time page
 * needed one for usage access. It was private to the main surface; the Claude
 * Design 2e note asks for "permission as a chip matching the main surface's
 * pair", and matching is a claim that is only true if it is the same component.
 * Two chips that merely look alike drift the first time one is touched.
 */
@Composable
fun StatusChip(
    icon: ImageVector,
    label: String,
    good: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = if (good) PetTheme.colors.thriving else MaterialTheme.colorScheme.error
    Row(
        modifier = modifier
            .heightIn(min = PetSize.chipHeight)
            .clip(PetRadius.pill)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = PetSpacing.s12, vertical = PetSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PetSpacing.s8)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(PetSize.icon20),
        )
        /*
         * THE LABEL TAKES THE REMAINING SPACE, AND THE CHEVRON SITS AT THE END.
         *
         * This read `Modifier.weight(1f, fill = false)` with a
         * `Spacer(Modifier.weight(1f))` after it — two children each claiming
         * weight 1, so Compose split the free space **50/50** and the label was
         * allocated half a chip. Two chips share the screen width, so that came
         * to about 38dp of text: `"Connected"` did not fit.
         *
         * **And it was CLIPPED, not ellipsised.** `Text`'s default overflow is
         * `Clip`, so it was cut mid-word with nothing to say it had been —
         * reported, correctly, as the chip saying `"Connect"`. A truncation that
         * announces itself would have looked like a layout problem; one that
         * does not looks like the wrong string, which is why this was hunted in
         * `PetStatusText` first.
         *
         * The design gives the label `min-width: 0` with `text-overflow:
         * ellipsis` and puts `flex: 1` on the spacer alone. In Compose that is
         * one weighted child that fills: the label's box takes what is left, its
         * glyphs sit at the start, and the chevron is pushed to the end.
         */
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            fontSize = PetTextSize.t12,
            color = content,
            /*
             * TWO LINES, THEN AN ELLIPSIS.
             *
             * One line was not a rendering default — `StatusChip.jsx` said
             * `white-space: nowrap` outright. It was never tested by the mock,
             * though: that canvas is 1360px wide, so a chip there gets several
             * times a phone's width and no label has ever had to fit. The
             * decision was made where it could not be felt.
             *
             * On a 426dp screen the label has ~110dp. `No pet paired yet` needs
             * ~116 and `Looking for your pet…` ~143, so the two states a new
             * user meets first were the two that truncated. Two lines clears
             * both; the ellipsis stays for anything past that.
             *
             * The height became a MINIMUM rather than a fixed 40dp. Two lines at
             * `labelMedium`'s 16sp leading come to 32dp, which fits inside 40
             * with the 4dp of vertical padding added above — so a wrapped chip
             * is the same height as an unwrapped one and the pair stays level
             * without either of them being told about the other. `heightIn` is
             * there so a larger system font size grows the chip rather than
             * clipping it.
             */
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(PetSize.icon20)
        )
    }
}
