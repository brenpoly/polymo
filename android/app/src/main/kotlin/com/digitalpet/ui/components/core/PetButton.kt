package com.digitalpet.ui.components.core

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.digitalpet.ui.theme.PetSize
import com.digitalpet.ui.theme.PetTheme

/**
 * The app's buttons, in three roles, with the numbers stated once.
 *
 * **Why this exists.** The design system names no button at all — `StepButton`
 * is the only one on the roster — so every call site was reaching for Material
 * directly and choosing for itself. Twenty of them had accumulated four
 * different answers: filled, outlined, text, and text-with-an-overridden-colour,
 * with `fillMaxWidth` on some and not others.
 *
 * **And they were the wrong HEIGHT, which is not a matter of taste.** Material's
 * `Button` has a 40 dp minimum. `DESIGN.md` §7.5 lists **48 dp** among the
 * numbers it calls load-bearing — the ones where moving them changes behaviour
 * rather than appearance. Two of the twenty set a height; the rest were 8 dp
 * under the app's own stated minimum, and nothing said so because a 40 dp button
 * looks perfectly reasonable next to another 40 dp button.
 *
 * **Roles, not variants.** A caller says what the button IS for — the action a
 * screen exists to perform, an alternative to it, or a low-emphasis choice in a
 * dialog — and this file decides what that looks like. Picking `OutlinedButton`
 * at a call site is how "Disconnect" and "Grant" ended up looking like different
 * kinds of thing for no reason.
 */
/**
 * The action a screen exists to perform. Filled, and there should be **one** on
 * a screen — a second filled button is two things claiming to be the point.
 */
@Composable
fun PetButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = Button(
    onClick = onClick,
    enabled = enabled,
    // heightIn, not height: a label that wraps must be allowed to grow. Fixing
    // the height is how a two-line button clips instead of getting taller.
    modifier = modifier.heightIn(min = PetSize.touchMin),
    content = content,
)

/**
 * An alternative to the primary action, or one of several peers — *Disconnect*
 * and *Forget*, the quiet-hours times, *Grant*. Outlined so a row of them reads
 * as a set of choices rather than a row of competing calls to action.
 */
@Composable
fun PetButtonSecondary(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(
    onClick = onClick,
    enabled = enabled,
    /*
     * THE ACCENT IS HERE, because it was at four of the five call sites and
     * missing from the fifth. Disconnect, Forget and the quiet-hours times each
     * passed `outlinedButtonColors(contentColor = accentText)` by hand; the
     * Grant button on the Permissions page did not, and so came out in
     * Material's `primary` — which is the mismatch that started this. One of
     * them had to be wrong and the majority was right.
     */
    colors = ButtonDefaults.outlinedButtonColors(contentColor = PetTheme.colors.accentText),
    modifier = modifier.heightIn(min = PetSize.touchMin),
    content = content,
)

/**
 * Low emphasis: dialog actions, and skipping a step.
 *
 * **The accent colour is here rather than at the call site.** Four dialogs were
 * each passing `ButtonDefaults.textButtonColors(contentColor = accentText)` by
 * hand, which is four chances to forget — and forgetting renders Material's
 * `primary`, which on this palette is close enough to the accent to look
 * deliberate.
 */
@Composable
fun PetTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = TextButton(
    onClick = onClick,
    enabled = enabled,
    colors = ButtonDefaults.textButtonColors(contentColor = PetTheme.colors.accentText),
    modifier = modifier.heightIn(min = PetSize.touchMin),
    content = content,
)
