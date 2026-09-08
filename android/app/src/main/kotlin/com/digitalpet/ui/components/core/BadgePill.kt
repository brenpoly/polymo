package com.digitalpet.ui.components.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.digitalpet.ui.theme.PetRadius
import com.digitalpet.ui.theme.PetSpacing
import com.digitalpet.ui.theme.PetTextSize
import com.digitalpet.ui.theme.PetTheme

/**
 * What a badge beside a slot's name says.
 *
 * A sealed type rather than a string so that the colour and the word cannot come
 * apart: [BadgePill] is one exhaustive `when`, and a fifth state would be a
 * compile error rather than a badge that renders in whatever the `else` branch
 * happened to be.
 */
sealed interface Badge {
    data object Loaded : Badge
    data object None : Badge
    data object Failed : Badge
    data class Working(val label: String) : Badge

    /*
     * A PERMISSION IS GRANTED, NOT LOADED, and the two are not one state
     * wearing two words. The Permissions page first shipped reusing [Loaded] on
     * the argument that one vocabulary is better than two — which is right when
     * it IS one idea, and wrong here: a model is a file that has been read into
     * memory, a permission is something the owner allowed. "loaded" over a
     * Bluetooth permission reads as a category error, and it did on the device.
     *
     * Added as states rather than as a `Working("granted")` because the whole
     * point of this being sealed is that the colour and the word cannot come
     * apart — see the class doc above.
     */
    data object Granted : Badge
    data object Missing : Badge
}

/**
 * A small tinted pill: what state a model slot is in, in one word.
 *
 * The container is the label's own colour at 15%, so the pill cannot disagree
 * with the word inside it — there is only one colour and it is chosen once.
 */
@Composable
fun BadgePill(badge: Badge, modifier: Modifier = Modifier) {
    val (label, colour) = when (badge) {
        Badge.Loaded -> "loaded" to PetTheme.colors.thriving
        Badge.None -> "none" to MaterialTheme.colorScheme.error
        Badge.Granted -> "granted" to PetTheme.colors.thriving
        Badge.Missing -> "missing" to MaterialTheme.colorScheme.error
        Badge.Failed -> "failed" to MaterialTheme.colorScheme.error
        is Badge.Working -> badge.label to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        fontSize = PetTextSize.t10,
        color = colour,
        modifier = modifier
            .clip(RoundedCornerShape(PetRadius.r8))
            .background(colour.copy(alpha = 0.15f))
            .padding(horizontal = PetSpacing.s7, vertical = PetSpacing.s4)
    )
}
