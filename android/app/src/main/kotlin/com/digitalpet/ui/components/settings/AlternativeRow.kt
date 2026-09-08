package com.digitalpet.ui.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.digitalpet.ui.theme.PetRadius
import com.digitalpet.ui.theme.PetSize
import com.digitalpet.ui.theme.PetSpacing

import com.digitalpet.ui.theme.PetTextSize

/** One file in a slot: what it is, load it, or delete it. */
@Composable
fun AlternativeRow(
    name: String,
    meta: String,
    enabled: Boolean,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    metaIsProblem: Boolean = false,
    loaded: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PetRadius.r14))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // A ring rather than a fill: the loaded row is the same row as its
            // neighbours with one fact added, and a filled highlight would make
            // it look like a different kind of thing.
            .then(
                if (loaded) Modifier.border(
                    PetSize.ringStroke,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(PetRadius.r14),
                ) else Modifier
            )
            .padding(horizontal = PetSpacing.s12, vertical = PetSpacing.s8),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = PetTextSize.t12,
                maxLines = 1,
                // GGUF names are long and Quicksand is wider than what this was
                // measured against; without this they were cut mid-glyph.
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                fontSize = PetTextSize.t10_5,
                color = if (metaIsProblem) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        /*
         * DELETE IS ABSENT ON THE LOADED ROW, not disabled. Deleting the file
         * the pet is currently thinking with is the one destructive act on this
         * screen that cannot be undone by re-importing in the moment, and a
         * greyed button invites a second tap to find out why.
         */
        if (!loaded) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete $name",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(PetSize.icon20).clickable(onClick = onDelete)
            )
        }
        /*
         * ONE PILL, THREE STATES — and the same box in all three.
         *
         * "Loaded" was a Text at the design's 7/16 padding while "Load" was an
         * M3 Button, which carries a 40dp minimum height. So a loaded row stood
         * ~10dp shorter than its neighbours and the list did not line up. The
         * design draws these as one element that changes colour, not as two
         * different controls, and that is the only way their heights can be
         * equal by construction rather than by matching numbers in two places.
         */
        StatePill(
            label = when {
                loaded -> "Loaded"
                else -> "Load"
            },
            container = when {
                loaded -> MaterialTheme.colorScheme.primaryContainer
                !enabled -> MaterialTheme.colorScheme.surfaceBright
                else -> MaterialTheme.colorScheme.primary
            },
            content = when {
                loaded -> MaterialTheme.colorScheme.onPrimaryContainer
                !enabled -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.onPrimary
            },
            // Neither a loaded model nor an unloadable one has anything to do.
            onClick = if (loaded || !enabled) null else onLoad,
        )
    }
}

/**
 * The trailing pill on an [AlternativeRow]: *Load*, *Loaded*, or unloadable.
 *
 * **Its geometry does not vary with its state**, which is the whole point — the
 * design's `7/16` padding on a fully rounded shape, whatever the colours say. A
 * row's height therefore cannot depend on whether its model happens to be the
 * loaded one.
 *
 * **The tap target is the design's 30dp rather than the [PetSize.touchMin] 48
 * that DESIGN.md §6 asks for**, and that is a deliberate exception rather than
 * an oversight: forcing 48 here would add 12dp to every row in the list to serve
 * one control, and the pill is wide and sits inside a 52dp row with nothing else
 * tappable near it. If it proves fiddly, the fix is to grow the row, not to make
 * the two states different shapes again.
 *
 * Private on purpose. It is not a component in its own right — it is this row's
 * trailing state, and the design system draws it inside `AlternativeRow.jsx`
 * rather than beside it.
 */
@Composable
private fun StatePill(
    label: String,
    container: Color,
    content: Color,
    onClick: (() -> Unit)?,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontSize = PetTextSize.t12,
        color = content,
        maxLines = 1,
        modifier = Modifier
            .padding(start = PetSpacing.s8)
            .clip(PetRadius.pill)
            .background(container)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = PetSpacing.s16, vertical = PetSpacing.s7)
    )
}
