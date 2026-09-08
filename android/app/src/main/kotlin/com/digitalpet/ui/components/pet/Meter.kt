package com.digitalpet.ui.components.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.digitalpet.ble.PetProtocol
import com.digitalpet.ui.theme.PetRadius
import com.digitalpet.ui.theme.PetSize
import com.digitalpet.ui.theme.PetSpacing
import com.digitalpet.ui.theme.PetTextSize
import com.digitalpet.ui.theme.PetTheme

/**
 * A score as four segments, each wholly filled or wholly empty.
 *
 * **NEVER PARTIAL** — DESIGN.md §1. The scores are integers 0–4 in the firmware,
 * integers 0–4 on the wire, and they move a whole level at a time, so a fraction
 * of a segment would be showing precision the system does not have. The design
 * justified meters as being able to show "a partial level the pips can't"; that
 * reason does not hold and its own markup draws discrete segments anyway. Do not
 * make this continuous without making the simulation carry fractions first.
 */
@Composable
fun Meter(label: String, value: Int?) {
    val max = PetProtocol.Condition.MAX_SCORE
    Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.s6)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontSize = PetTextSize.t11_5,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                // Null is "not known yet", never 0 — the rule PetStatusText
                // follows everywhere else, and the one that stops a disconnected
                // pet reading as a starving one.
                text = value?.let { "$it / $max" } ?: "—",
                style = MaterialTheme.typography.labelMedium,
                fontSize = PetTextSize.t11_5,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PetSpacing.s5)) {
            repeat(max) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(PetSize.meterSegmentHeight)
                        /*
                         * A pill, and it always was one. This read
                         * `RoundedCornerShape(5.dp)` on a 9dp-high segment —
                         * Compose scales corners down when they exceed the box,
                         * so 5 rendered as 4.5 and the segment has been fully
                         * rounded since it was written. Saying `pill` is the
                         * same pixels with the arithmetic removed.
                         */
                        .clip(PetRadius.pill)
                        .background(
                            when {
                                value == null -> PetTheme.colors.unknown
                                i < value -> MaterialTheme.colorScheme.primary
                                /*
                                 * The SAME track the usage bar uses, as of
                                 * 2026-08-09. This was `surfaceVariant`, on
                                 * which the gold fill measured 1.72:1 against
                                 * the 3:1 a non-text indicator wants — the two
                                 * bars in this app had drifted onto different
                                 * track colours and only one of them had been
                                 * argued for. See PetColors.track for why the
                                 * light one has to be dark.
                                 */
                                else -> PetTheme.colors.track
                            }
                        )
                )
            }
        }
    }
}
