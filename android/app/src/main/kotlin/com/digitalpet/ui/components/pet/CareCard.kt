package com.digitalpet.ui.components.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.digitalpet.ble.PetProtocol
import com.digitalpet.pet.PetReadiness
import com.digitalpet.pet.PetStatusText
import com.digitalpet.ui.theme.PetRadius
import com.digitalpet.ui.theme.PetSize
import com.digitalpet.ui.theme.PetSpacing
import com.digitalpet.ui.theme.PetTextSize
import com.digitalpet.ui.theme.PetTheme
import com.digitalpet.ui.components.core.PetButton

/**
 * How the pet is: one headline, then a meter each for satiety and happiness.
 *
 * The meters replace `PetStatusText.pips`' filled/empty circles with segments,
 * which is presentation only — the *rules* about what the numbers mean, when
 * they are stale and what outranks what stay in `PetStatusText` and
 * `PetReadiness` where they are tested.
 */
@Composable
fun CareCard(
    condition: PetProtocol.Condition?,
    readiness: PetReadiness,
    switchedOff: Boolean,
    onTurnBackOn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PetRadius.r24))
            .background(MaterialTheme.colorScheme.surface)
            .padding(PetSpacing.s16),
        verticalArrangement = Arrangement.spacedBy(PetSpacing.s14)
    ) {
        // Baseline, not top: a 15sp headline beside an 11.5sp reading sits
        // wrong on either edge and right on the line they share.
        Row {
            Text(
                /*
                 * BEING OFF OUTRANKS A MISSING MODEL, and the ranking is a
                 * judgement rather than an accident. Both can be true at once,
                 * but only one of them is the reason nothing is happening *and*
                 * the one the user did on purpose — and while the pet is off,
                 * which models are loaded changes nothing about what to do next.
                 */
                text = if (switchedOff) PetStatusText.headline(null, switchedOff = true)
                       else PetReadiness.headline(readiness) ?: PetStatusText.headline(condition),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontSize = PetTextSize.t15,
                color = if (!switchedOff && readiness is PetReadiness.Ready) PetTheme.colors.thriving
                        else MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f).alignByBaseline()
            )

            /*
             * STAGE AND BATTERY, moved here from under the app's name.
             *
             * Both are `null` when not known and **null renders as absent**, not
             * as a reading — the §5.0 rule that stops a disconnected pet looking
             * like a newborn on a flat battery. Absent means the whole group
             * disappears rather than showing a dash, because unlike the meters
             * there is no row here reserved for them.
             *
             * Age is still not shown: it is not on the wire, and the decision on
             * 2026-08-07 was to leave it there rather than bump the protocol.
             */
            // Null while the stage is unnamed, which collapses this line to the
            // battery alone — the row already handled a stage-less pet, because
            // one that has not reported yet has always had none. See
            // PetStatusText.stageIsNamed.
            val stage = PetStatusText.stageLabelOnSurface(condition?.stage)
            val battery = condition?.batteryPercent
            if (stage != null || battery != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PetSpacing.s5),
                    modifier = Modifier.padding(start = PetSpacing.s10).alignByBaseline()
                ) {
                    stage?.let {
                        Text(
                            if (battery != null) "$it ·" else it,
                            style = MaterialTheme.typography.labelMedium,
                            fontSize = PetTextSize.t11_5,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    battery?.let {
                        Icon(
                            Icons.Default.BatteryStd,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(PetSize.icon14)
                        )
                        Text(
                            "$it%",
                            style = MaterialTheme.typography.labelMedium,
                            fontSize = PetTextSize.t11_5,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        /*
         * THE WAY BACK ON, and the last genuinely missing component in
         * DESIGN.md §5.3/§5.4.
         *
         * "Off" was persisted so that it would survive reopening the app —
         * which was right, and made the state's only exit a two-tap trip to
         * Settings → Your pet → Reconnect that nothing on this screen pointed
         * at. §5.4's own words: an off switch that survives is only an
         * improvement if the app admits the pet is off *and* offers the way
         * back.
         *
         * It sits in the care card rather than beside the chip that reports the
         * state, because this card is already the one place that says how the
         * pet is and what to do about it. The chip is a reading; this is the
         * answer to it.
         */
        if (switchedOff) {
            PetButton(onClick = onTurnBackOn, modifier = Modifier.fillMaxWidth()) {
                Text("Turn it back on")
            }
        }

        Meter("satiety", condition?.satiety)
        Meter("happiness", condition?.happiness)
    }
}
