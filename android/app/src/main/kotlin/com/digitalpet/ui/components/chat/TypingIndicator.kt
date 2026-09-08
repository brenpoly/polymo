package com.digitalpet.ui.components.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.MaterialTheme
import com.digitalpet.ui.theme.PetSize
import com.digitalpet.ui.theme.PetSpacing
import kotlinx.coroutines.delay

@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val dots = listOf(
        remember { Animatable(0f) },
        remember { Animatable(0f) },
        remember { Animatable(0f) }
    )

    dots.forEachIndexed { index, animatable ->
        LaunchedEffect(animatable) {
            delay(index * 150L)
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1200
                        0.0f at 0 with LinearOutSlowInEasing
                        1.0f at 300 with LinearOutSlowInEasing
                        0.0f at 600 with LinearOutSlowInEasing
                        0.0f at 1200 with LinearOutSlowInEasing
                    },
                    repeatMode = RepeatMode.Restart
                )
            )
        }
    }

    Row(
        // Vertical only, for the same reason as MessageBubble: it lives inside
        // the transcript's gutter and must not add a second one.
        modifier = modifier.padding(vertical = PetSpacing.s8),
        verticalAlignment = Alignment.CenterVertically
    ) {
        dots.forEachIndexed { index, animatable ->
            Box(
                modifier = Modifier
                    .size(PetSize.typingDot)
                    .graphicsLayer {
                        translationY = -animatable.value * 12f
                    }
                    .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape)
            )
            if (index < dots.size - 1) {
                Spacer(modifier = Modifier.width(PetSpacing.s4))
            }
        }
    }
}
