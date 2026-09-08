package com.digitalpet.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.digitalpet.llm.Message
import com.digitalpet.llm.MessageRole
import com.digitalpet.ui.theme.PetRadius
import com.digitalpet.ui.theme.PetSize
import com.digitalpet.ui.theme.PetSpacing

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One message.
 *
 * **IT DOES NOT ANIMATE ITSELF IN, and must not start doing so again.** It used
 * to: an `AnimatedVisibility` whose `visible` began false and was set true from a
 * `LaunchedEffect`, sliding the bubble in from its own side. That is fine for a
 * message that has just arrived and wrong for every other reason a bubble gets
 * composed — and in a `LazyColumn` most compositions are the other reasons.
 *
 * Two of them, both reported as the sheet misbehaving rather than as anything to
 * do with a bubble:
 *
 * - **Scrolling.** A lazy list disposes items that leave the viewport and
 *   composes them again on the way back, so every bubble scrolled past slid in
 *   afresh, as though the conversation were arriving while being read.
 * - **A new message.** The transcript is keyed by position, and a new message
 *   goes at the front, so *every* item's slot moved and the whole visible list
 *   re-entered at once.
 *
 * The jumping came from the same place. `AnimatedVisibility` occupies **no
 * space** while invisible, so each of those re-entries measured as zero height
 * for a frame and then grew — inside a list that positions everything else
 * relative to it. An animation that only ever looked right on a message's first
 * appearance was, in practice, running mostly at the wrong times.
 */
@Composable
fun MessageBubble(message: Message) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val isUser = message.role == MessageRole.USER

    Row(
        modifier = Modifier
            .fillMaxWidth()
            /*
             * VERTICAL ONLY. The 16dp gutter belongs to the transcript, not to
             * the bubble — the design puts `0 16px` on the list and `4px 0` on
             * the bubble. Having both meant 32dp of margin and bubbles about
             * 32dp narrower than drawn, which is most of a word per line.
             */
            .padding(vertical = PetSpacing.s4),
        verticalAlignment = Alignment.Bottom
    ) {
        if (isUser) {
            Spacer(modifier = Modifier.weight(1f))
        }

        /*
         * THE ROLES WERE THE WRONG WAY ROUND, corrected 2026-08-07 against the
         * Claude Design main surface: YOU are gold and the pet is the quiet
         * cream, not the reverse. Worth stating because it looks like a colour
         * swap and is not — a gold bubble is the loud one, and having the pet
         * shout every line while the person murmurs made the transcript read as
         * the pet talking at you.
         *
         * The asymmetric corner is what marks the speaker: square at the
         * bottom-right for you, bottom-left for the pet. That does the job a
         * gradient was doing, without a gradient.
         */
        Box(
            modifier = Modifier
                .widthIn(max = PetSize.bubbleMaxWidth)
                .background(
                    color = if (isUser) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                    shape = if (isUser)
                        RoundedCornerShape(
                            PetRadius.r20, PetRadius.r20, PetRadius.bubbleTail, PetRadius.r20
                        )
                    else
                        RoundedCornerShape(
                            PetRadius.r20, PetRadius.r20, PetRadius.r20, PetRadius.bubbleTail
                        )
                )
                .padding(horizontal = PetSpacing.s14, vertical = PetSpacing.s10)
        ) {
            Column {
                Text(
                    text = message.content,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge
                )
                val time = timeFormat.format(Date(message.timestamp))
                Text(
                    text = time,
                    color = (if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }

        if (!isUser) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
