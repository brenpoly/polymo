package com.digitalpet.ui.components.core

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.digitalpet.ui.theme.PetRadius
import com.digitalpet.ui.theme.PetSize

/**
 * A round nudge, used by both allowance steppers.
 *
 * **Drawn at 36dp and not at the 48dp DESIGN.md §6 asks for**, which is a
 * recorded exception rather than an oversight: two of these sit in a row beside
 * a reading, and growing them to 48 would set the row's height for the sake of
 * a control that is already the largest thing in it. If it proves fiddly the fix
 * is to space the pair further apart, not to make one bigger than the other.
 */
@Composable
fun StepButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(PetSize.stepButton)
            .clip(PetRadius.pill)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(PetSize.icon20)
        )
    }
}
