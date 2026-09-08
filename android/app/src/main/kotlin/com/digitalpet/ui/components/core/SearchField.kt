package com.digitalpet.ui.components.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.digitalpet.ui.theme.PetRadius
import com.digitalpet.ui.theme.PetSize
import com.digitalpet.ui.theme.PetSpacing
import com.digitalpet.ui.theme.PetTextSize

/** The design's search pill: an icon and a field, no box. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(PetSize.chipHeight)
            .clip(PetRadius.pill)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = PetSpacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PetSpacing.s8)
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(PetSize.icon20)
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = PetTextSize.t12,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = PetTextSize.t12,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                /*
                 * The placeholder is DRAWN, not labelled — it is a sibling Text
                 * that disappears the moment anything is typed, so a screen
                 * reader met an unlabelled edit box. Measured: the a11y tree had
                 * an EditText with no text and no description at all. This is
                 * the field's name rather than its contents, so it stays correct
                 * once the box has something in it.
                 */
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = placeholder }
            )
        }
    }
}
