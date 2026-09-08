package com.digitalpet.ui.screens.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.digitalpet.llm.Message
import com.digitalpet.llm.MessageRole
import com.digitalpet.ui.components.chat.MessageBubble
import com.digitalpet.ui.components.chat.TypingIndicator
import com.digitalpet.ui.theme.PetSpacing

@Composable
fun PetChatSection(
    messages: List<Message>,
    currentStreamingResponse: String,
    isGenerating: Boolean,
    modifier: Modifier = Modifier,
    /**
     * Hoisted so the sheet can both read it — the newest message's measured
     * height decides where the transcript sits, see [ChatSheetGeometry] — and
     * return it to the newest message when the sheet closes.
     */
    state: LazyListState = rememberLazyListState(),
    /**
     * False while the sheet is closed. The collapsed sheet shows a band a
     * message or two tall, and scrolling that band moves it off the newest
     * message — the exact thing the band exists to show.
     */
    userScrollEnabled: Boolean = true,
) {
    /*
     * NEWEST AT THE BOTTOM — the ordinary chat order, and the ordering is not
     * what makes the peek work.
     *
     * This flipped twice, both times to fix a peek problem, and both times
     * wrongly. A peeking sheet reveals the TOP of its content, so the newest
     * message appeared to belong at the top — but no ordering satisfies both a
     * glance and a history, because they want the same message in two different
     * places. What resolves it is *position*, not order: the list keeps the
     * ordinary chat order at a constant height, and the sheet translates it so
     * the newest message lands wherever it is currently needed. See
     * ChatSheetGeometry, which is where that arithmetic lives and is tested.
     */
    LazyColumn(
        modifier = modifier,
        state = state,
        reverseLayout = true,
        userScrollEnabled = userScrollEnabled,
    ) {
        /*
         * EVERY ITEM IS KEYED, and the keys are what stop the list churning.
         *
         * Without them a lazy list identifies items by position — and the newest
         * message goes at the FRONT of this one, so every arrival shifted every
         * index and Compose treated the entire visible transcript as new
         * content. Items were disposed and composed again, losing anything they
         * remembered, one message at a time, for the whole conversation.
         *
         * That was invisible until it wasn't: MessageBubble used to animate
         * itself in, so a churn nobody could see became the whole list sliding
         * about. The animation is gone (see MessageBubble) and the keys are here
         * because the churn was real either way — the animation only reported it.
         */
        if (isGenerating && currentStreamingResponse.isEmpty()) {
            item(key = "typing") {
                TypingIndicator()
            }
        }

        if (currentStreamingResponse.isNotEmpty()) {
            item(key = "streaming") {
                MessageBubble(
                    message = Message(
                        role = MessageRole.ASSISTANT,
                        content = currentStreamingResponse
                    )
                )
            }
        }

        /*
         * The key is the message's position in the UNREVERSED history, which is
         * stable precisely because that list is append-only — a new message
         * lands at its end, so nothing already in it moves. Only the reversed
         * view shifts, and that is the view being drawn.
         *
         * A timestamp would read better and is not safe: `Message` drops the
         * Room row id, so nothing in it is guaranteed unique, and a duplicate
         * key in a lazy list is a crash rather than a glitch.
         */
        itemsIndexed(
            items = messages.reversed(),
            key = { index, _ -> messages.lastIndex - index },
        ) { _, message ->
            MessageBubble(message = message)
        }

        if (messages.isEmpty() && !isGenerating) {
            item(key = "empty") {
                Text(
                    text = "Say hi to your pet! 👋",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(PetSpacing.s32)
                )
            }
        }
    }
}
