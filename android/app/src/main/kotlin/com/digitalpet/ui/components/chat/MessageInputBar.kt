package com.digitalpet.ui.components.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.digitalpet.ui.theme.PetSpacing
import com.digitalpet.ui.theme.PetTheme

/**
 * Type at the pet, or hold the microphone.
 *
 * **It is a fixed footer OUTSIDE the chat sheet**, and that placement is the
 * component's main constraint rather than a detail of where it is used: a
 * peeking sheet reveals the top of its content, so anything in the sheet
 * competes with the conversation for the peek — and this must be reachable in
 * every sheet position. See DESIGN.md §6.4a and the note on the `Surface` in
 * `PetHomeScreen`.
 *
 * **No `imePadding` here.** The keyboard is handled once, at the root of the
 * surface, so this rides up with the sheet rather than moving independently of
 * it. Padding it here as well was a real bug, twice over — the window resized
 * *and* the footer charged for the keyboard again.
 */
@Composable
fun MessageInputBar(
    isGenerating: Boolean,
    isRecording: Boolean,
    isTranscribing: Boolean,
    onSend: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PetSpacing.s16),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Say something...") },
            enabled = !isGenerating,
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PetTheme.colors.accentText,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )

        IconButton(
            onClick = {
                if (text.isNotBlank()) {
                    onSend(text)
                    text = ""
                }
            },
            enabled = !isGenerating && !isRecording && !isTranscribing && text.isNotBlank(),
            modifier = Modifier.padding(start = PetSpacing.s8)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                // `outline` is the palette's own "present but inactive"; an
                // invented alpha on onSurface is a literal wearing a token's
                // clothes, and it read darker than everything else disabled.
                tint = if (!isGenerating && !isRecording && !isTranscribing && text.isNotBlank())
                           PetTheme.colors.accentText
                       else MaterialTheme.colorScheme.outline
            )
        }

        IconButton(
            onClick = {
                if (isRecording) {
                    onStopRecording()
                } else {
                    onStartRecording()
                }
            },
            enabled = !isGenerating && !isTranscribing,
            modifier = Modifier.padding(start = PetSpacing.s8)
        ) {
            val tint = if (isRecording) MaterialTheme.colorScheme.error
                       else PetTheme.colors.accentText
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = if (isRecording) "Tap to stop recording"
                                     else "Tap to start recording",
                tint = if (isGenerating || isTranscribing) MaterialTheme.colorScheme.outline
                       else tint
            )
        }
    }
}
