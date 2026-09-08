package com.digitalpet.ui.components.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.digitalpet.data.ScreenTimeDisplay
import com.digitalpet.ui.components.core.StepButton
import com.digitalpet.ui.theme.PetRadius
import com.digitalpet.ui.theme.PetSize
import com.digitalpet.ui.theme.PetSpacing
import com.digitalpet.ui.theme.PetTextSize

/**
 * One tracked app: how long it has been open **right now**, what it did today,
 * and — once you ask — what it is allowed.
 *
 * **The reading and the bar are the sitting, not the day.** The allowance is a
 * limit on one sitting, so the sitting is the only thing it can be divided by;
 * the day's total is stated underneath as a fact in its own words. Until
 * 2026-08-09 the row divided the day by the allowance, which filled the bar
 * before lunch and left it red until midnight with nothing able to reset it.
 *
 * **The allowance and Stop tracking are behind the pencil.** A destructive
 * control on a row you scroll past is one mis-tap from untracking an app, and
 * the untracking is silent: the pet simply stops being able to fall ill from it.
 */
@Composable
fun TrackedAppCard(
    name: String,
    /**
     * The **longest unbroken stretch** in this app today — not the live sitting,
     * which is always zero by the time this page is on screen. See
     * `ScreenTime.longestSitting`.
     */
    sittingMs: Long,
    /** The day's total, stated as a fact and never divided by the allowance. */
    todayMs: Long,
    allowanceMs: Long,
    onStep: (Boolean) -> Unit,
    onStopTracking: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val spent = ScreenTimeDisplay.hasSpentAllowance(sittingMs, allowanceMs)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PetRadius.r20))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = PetSpacing.s14, vertical = PetSpacing.s11),
        verticalArrangement = Arrangement.spacedBy(PetSpacing.s8)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                fontSize = PetTextSize.t12_5,
                maxLines = 1,
                // The design ellipsises this; a squeezed label that clips has
                // no way to say it was squeezed. See StatusChip, where exactly
                // that read as the wrong string.
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                /*
                 * THE SITTING, which is the thing the allowance governs.
                 *
                 * This read the day's total over the per-sitting allowance,
                 * which is two quantities sharing a slash: the numerator only
                 * grows, so it passed the denominator before lunch and stayed
                 * there. Reported as the tracking being broken, and it was —
                 * nothing about a day total can reset.
                 */
                text = ScreenTimeDisplay.sessionReading(sittingMs, allowanceMs),
                style = MaterialTheme.typography.labelMedium,
                fontSize = PetTextSize.t11,
                // Red means what it means on the bar and what it means to the
                // pet: this sitting has spent the allowance, and closing the app
                // is what clears all three.
                color = if (spent) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = PetSpacing.s10)
            )
            Icon(
                Icons.Default.Edit,
                contentDescription = if (expanded) "Hide settings for $name"
                                     else "Settings for $name",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .size(PetSize.icon20)
                    .clickable { expanded = !expanded }
            )
        }

        UsageBar(usedMs = sittingMs, allowanceMs = allowanceMs)

        /*
         * THE DAY, AS A FACT. It carries its own word — "1 h 12 m today" — so it
         * cannot be read as belonging to the allowance above it. Stating it was
         * always right; dividing by it was the mistake.
         */
        Text(
            ScreenTimeDisplay.today(todayMs),
            style = MaterialTheme.typography.labelSmall,
            fontSize = PetTextSize.t10_5,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.s10)) {
                Row(
                    Modifier.fillMaxWidth().padding(top = PetSpacing.s10),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PetSpacing.s8)
                ) {
                    Text(
                        "Allowance per sitting",
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = PetTextSize.t11_5,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    StepButton(Icons.Default.Remove, "Less") { onStep(false) }
                    Text(
                        ScreenTimeDisplay.allowance(allowanceMs),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = PetTextSize.t13,
                        color = MaterialTheme.colorScheme.onSurface,
                        // CENTRED IN ITS BOX, not start-aligned in it. The box is
                        // held wider than the reading so the two buttons do not
                        // shuffle under a finger that is still tapping — and
                        // without this the slack all fell on the right, so `5
                        // min` sat against the minus button instead of between
                        // the two. More visible since the default went 25 -> 5,
                        // because a shorter reading leaves more slack.
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = PetSize.allowanceWidth)
                    )
                    StepButton(Icons.Default.Add, "More") { onStep(true) }
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(PetRadius.r18))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable(onClick = onStopTracking)
                        .padding(horizontal = PetSpacing.s13, vertical = PetSpacing.s8),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PetSpacing.s7)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(PetSize.icon18)
                    )
                    Text(
                        "Stop tracking",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        fontSize = PetTextSize.t11_5,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}
