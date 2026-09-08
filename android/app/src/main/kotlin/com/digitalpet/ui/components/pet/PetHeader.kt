package com.digitalpet.ui.components.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.digitalpet.ui.theme.PetRadius
import com.digitalpet.ui.theme.PetSize
import com.digitalpet.ui.theme.PetTextSize
import androidx.compose.material.icons.filled.Settings as SettingsGlyph

/**
 * Name, then the way into Settings.
 *
 * **The name is a placeholder** and the design's "Bramble" is too — see
 * DESIGN.md §4. Nothing in the app or the firmware names the pet yet, so this
 * shows the product name until naming is designed.
 *
 * **Stage and battery used to sit under it** and moved into the care card with
 * the 2026-08-07 design system: they are *readings about the pet*, and the care
 * card is where the pet's readings live. Under the app's name they read as a
 * subtitle for the app. That also retired a subtitle that said "not connected"
 * when there was nothing to report — a third place saying so, after the chip and
 * the card, and the least informative of the three.
 */
@Composable
fun PetHeader(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "PolyMO",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            fontSize = PetTextSize.t22,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .size(PetSize.headerButton)
                .clip(PetRadius.pill)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.SettingsGlyph,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
