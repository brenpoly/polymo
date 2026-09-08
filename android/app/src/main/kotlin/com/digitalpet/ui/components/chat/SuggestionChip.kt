package com.digitalpet.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.digitalpet.ui.theme.PetRadius
import com.digitalpet.ui.theme.PetSpacing
import com.digitalpet.ui.theme.PetTextSize

/**
 * A tappable suggestion, shaped like something you might have said.
 *
 * It takes [MessageBubble]'s own corner — square at the bottom-right, the
 * speaker mark for *you* — because that is the claim it is making: tapping it
 * says this, in your voice. A chip shaped like the pet's bubble would be
 * offering to put words in the pet's mouth.
 *
 * [prominent] is not styling. Counts are instant and free — no model runs — so
 * that one leads in `primary`; a summary costs seconds of inference and is the
 * quieter second option.
 */
@Composable
fun SuggestionChip(text: String, prominent: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontSize = PetTextSize.t12,
        color = if (prominent) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(
                RoundedCornerShape(
                    PetRadius.r20, PetRadius.r20, PetRadius.bubbleTail, PetRadius.r20
                )
            )
            .background(
                if (prominent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = PetSpacing.s12, vertical = PetSpacing.s8)
    )
}
