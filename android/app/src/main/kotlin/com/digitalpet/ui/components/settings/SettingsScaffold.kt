package com.digitalpet.ui.components.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.digitalpet.ui.theme.PetSize
import com.digitalpet.ui.theme.PetSpacing

/**
 * The frame every settings screen shares: a back arrow, a title, and one line
 * saying what the section is for.
 *
 * **A Row that grows, not a `TopAppBar`.** M3's app bar is a fixed 64dp box, and
 * with Baloo 2 the content stopped fitting: `titleLarge` renders **35.1dp**
 * against the 28dp line its style specifies — a display face carrying Devanagari
 * has generous vertical metrics — and the subtitle wraps to two lines at 28.9dp.
 * 35.1 + 28.9 is 64.0 exactly, so the header was full to the millimetre and
 * clipping, which is what "the text is wrong on this page" looked like.
 *
 * Sizing a bar to its content is also simply what the design draws: a header
 * with `14px 16px 18px` of padding and no height of its own. It grows when a
 * subtitle wraps and shrinks when there is no subtitle, and neither case needs a
 * number chosen in advance.
 *
 * It stays inside `Scaffold` so the window insets and the container colour keep
 * working; only the bar is ours. `statusBarsPadding` is now this header's job,
 * because that is what `TopAppBar` was quietly doing.
 */
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        // Stated rather than inherited — `background` is the page, `surface` is a
        // card, and a default that picks the wrong one of those is invisible.
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    // start is 4 rather than 16 because the 48dp touch target
                    // around a 24dp icon already carries 12 of its own; this
                    // lands the glyph on the design's 16dp margin.
                    .padding(
                        start = PetSpacing.s4,
                        end = PetSpacing.s16,
                        top = PetSpacing.s14,
                        bottom = PetSpacing.s18,
                    ),
                verticalAlignment = Alignment.Top,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(PetSize.icon24),
                    )
                }
                Column(Modifier.weight(1f).padding(top = PetSpacing.s10)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        content = content
    )
}
