package com.digitalpet.ui.components.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.digitalpet.ui.theme.PetSize

/**
 * One row of the settings index: an icon, what it is, and what it is for.
 *
 * **The container is stated, not inherited.** `ListItem` defaults its container
 * to `surface`, which on this palette is a visibly lighter cream than
 * `background` — so an index of these drew a card under every row and the page
 * read as one surface with gaps in it. The design draws plain rows on the page.
 * DESIGN.md §7.5a: *where the design shows page, say `background` explicitly*,
 * and transparent is how a row says it.
 *
 * **The leading icon lets the list be scanned rather than read**, which is what
 * the design system asked for and the reason it is a required parameter: a row
 * without one would be a different-shaped row in the same list.
 */
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(PetSize.icon24),
            )
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    )
}
