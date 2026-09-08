package com.digitalpet.ui.components.core

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.digitalpet.ui.theme.PetRadius
import com.digitalpet.ui.theme.PetSpacing

/**
 * The rounded surface the settings screens are built from.
 *
 * 20dp radius on `surface`, matching the screen-time rows and the design's cards
 * throughout — a wrapper rather than a fork, per DESIGN.md §6.2 lever 4, because
 * this is the third screen drawing the same box.
 *
 * **It shadows `androidx.compose.material3.Card`, and that is the design
 * system's name for it** (`components/core/Card.jsx`), not an accident. Nothing
 * in this app uses M3's card; a file that wanted both would have to say which,
 * which is the right amount of friction for a choice that ought to be
 * deliberate.
 */
@Composable
fun Card(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PetRadius.r20))
            .background(MaterialTheme.colorScheme.surface)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = PetSpacing.s14, vertical = PetSpacing.s12),
        content = content,
    )
}
