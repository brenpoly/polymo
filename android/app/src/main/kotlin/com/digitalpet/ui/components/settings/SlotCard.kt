package com.digitalpet.ui.components.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.digitalpet.ui.components.core.Badge
import com.digitalpet.ui.components.core.BadgePill
import com.digitalpet.ui.components.core.Card
import com.digitalpet.ui.theme.PetRadius
import com.digitalpet.ui.theme.PetSize
import com.digitalpet.ui.theme.PetSpacing
import com.digitalpet.ui.theme.PetTextSize
import com.digitalpet.ui.theme.PetTheme

/**
 * One model slot: what is loaded, and — behind the pencil — what else there is.
 *
 * **Named by what it does, not by what it is**: Brain, Ears, Voice. The files
 * are `.gguf`, `.bin` and `.onnx`, and those names appear where they matter — on
 * [importLabel], so it is obvious which slot a downloaded file belongs to — but
 * a slot called "Whisper" tells you nothing about what breaks when it is empty.
 *
 * **Each slot owns its own import.** The panels this replaced had three import
 * buttons at three different scroll positions, all opening the same unfiltered
 * picker, so a loose `.gguf` could be handed to the voice slot and fail
 * somewhere far away.
 *
 * **A card at rest says what is loaded and nothing else**, because that is the
 * only thing that matters when nothing is wrong.
 */
@Composable
fun SlotCard(
    icon: ImageVector,
    title: String,
    badge: Badge,
    detail: String,
    importLabel: String,
    importing: Boolean,
    onImport: () -> Unit,
    othersLabel: String,
    alternatives: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card {
        /*
         * THE WHOLE HEADER TOGGLES, and the pencil is only its affordance.
         *
         * The pencil used to carry the `clickable` itself, which gave it
         * Material's 48dp minimum interactive size — so a 20dp icon laid out a
         * 48dp row and every card was 28dp taller than drawn before anything
         * else was counted. Measured: 167dp of card pitch against a design that
         * comes to about 76.
         *
         * Moving the gesture to the row fixes the height and improves the
         * target rather than trading one for the other: the row is a bigger
         * thing to hit than the icon ever was, and the icon still says what
         * will happen.
         */
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PetSpacing.s8),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = if (expanded) "Hide other files for $title"
                                   else "Show other files for $title",
                ) { expanded = !expanded }
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(PetSize.icon20)
            )
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                fontSize = PetTextSize.t13,
                color = MaterialTheme.colorScheme.onSurface,
            )
            BadgePill(badge)
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Default.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(PetSize.icon20)
            )
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            fontSize = PetTextSize.t11_5,
            /*
             * RED MEANS SOMETHING IS WRONG, and loading is not. This read
             * `badge is Badge.Loaded`, so a Brain part-way through loading
             * printed its own progress — "90%" — in error red, which says the
             * load has failed at the exact moment it is going fine.
             */
            color = if (badge is Badge.None || badge is Badge.Failed)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = PetSpacing.s6)
        )

        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.padding(top = PetSpacing.s12),
                verticalArrangement = Arrangement.spacedBy(PetSpacing.s8),
            ) {
                Text(
                    othersLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = PetTextSize.t11,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                alternatives()
                /*
                 * A ROW, NOT A TextButton. Material's text button carries 12dp
                 * of content padding and a 40dp minimum, which indented the
                 * import label past the rows above it and added height the
                 * design does not draw. This is the design's own 8/4, with
                 * heightIn keeping the 48dp target its §4 calls load-bearing —
                 * so both rules hold instead of one paying for the other.
                 */
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PetSpacing.s8),
                    modifier = Modifier
                        .clip(RoundedCornerShape(PetRadius.r12))
                        .clickable(enabled = !importing, onClick = onImport)
                        .heightIn(min = PetSize.touchMin)
                        .padding(horizontal = PetSpacing.s8, vertical = PetSpacing.s4)
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        tint = PetTheme.colors.accentText,
                        modifier = Modifier.size(PetSize.icon18)
                    )
                    Text(
                        if (importing) "Importing…" else importLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = PetTheme.colors.accentText,
                    )
                }
            }
        }
    }
}
