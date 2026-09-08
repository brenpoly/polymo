package com.digitalpet.ui.nav

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import com.digitalpet.data.ScreenTimeDisplay
import com.digitalpet.pet.PermissionInventory
import com.digitalpet.pet.PetDeviceText
import com.digitalpet.ui.settings.PermissionsScreen
import com.digitalpet.ui.components.settings.SettingsRow
import com.digitalpet.ui.components.settings.SettingsScaffold
import com.digitalpet.ui.screens.AppUsageViewModel
import com.digitalpet.ui.screens.components.ScreenTimeScreen
import com.digitalpet.ui.settings.ModelSettingsScreen
import com.digitalpet.ui.settings.PetDeviceScreen
import com.digitalpet.ui.theme.PetSpacing
import com.digitalpet.ui.theme.PetTheme
import com.digitalpet.ui.theme.ThemeMode
import androidx.compose.material3.MaterialTheme

/**
 * Settings as a small tree — Claude Design 2c/2d/2e.
 *
 * **This is what deletes `EmbeddedPanel`.** The single-page Settings built on
 * 2026-08-04 had to bound each panel's height, because three of them contain a
 * `LazyColumn` and a scrollable inside a scrolling page gets infinite
 * constraints. Giving each panel its own screen removes the nesting entirely,
 * which is what DESIGN.md §6.5 step 2 said would happen.
 *
 * The index exists for the gear. The two chips on the main surface skip it and
 * open their section directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenPet: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenScreenTime: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAppearance: () -> Unit,
) {
    SettingsScaffold(title = "Settings", onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            // Ordered by what breaks first without it, which is §5.2's order for
            // first run and for the same reason: no pet means nothing works at
            // all, no models means a pet that listens and cannot answer, no
            // screen-time access means the sickness mechanic silently does
            // nothing.
            SettingsRow(
                icon = Icons.Default.Bluetooth,
                title = "Your pet",
                subtitle = PetDeviceText.SUBTITLE,
                onClick = onOpenPet,
            )
            /*
             * "LOCAL AI MODELS", not "Voice and language" — renamed with the
             * 2026-08-07 design system. The old name listed two of the three
             * faculties behind this page and left out the one people actually
             * come looking for; the new one names the thing itself, three model
             * files that run on the phone. `memory` reads as on-device silicon
             * and does not collide with `psychology`, which already means the
             * Brain slot *inside* the page.
             */
            SettingsRow(
                icon = Icons.Default.Memory,
                title = "Local AI models",
                subtitle = "The three models the pet needs to hear you, think, and speak",
                onClick = onOpenModels,
            )
            SettingsRow(
                icon = Icons.Default.PhoneAndroid,
                title = "Screen time",
                subtitle = ScreenTimeDisplay.SUBTITLE,
                onClick = onOpenScreenTime,
            )
            /*
             * ABOVE Appearance and below the three features, because it is a
             * prerequisite for all of them rather than a fourth feature. It is
             * also the page somebody arrives at when something has silently
             * stopped working, which is an argument for it being findable rather
             * than tidy — and the subtitle says what it is for rather than what
             * it contains, because "permissions" alone reads as chrome.
             */
            SettingsRow(
                icon = Icons.Default.Lock,
                title = "Permissions",
                subtitle = PermissionInventory.SUBTITLE,
                onClick = onOpenPermissions,
            )
            // LAST, and the ordering rule is why. The four above are things the
            // product does not work without; this one changes nothing about what
            // the pet does. Putting a preference above a prerequisite would say
            // the two are the same kind of thing.
            SettingsRow(
                icon = Icons.Default.Contrast,
                title = "Appearance",
                subtitle = "Light or dark, or follow the phone",
                onClick = onOpenAppearance,
            )
        }
    }
}

/**
 * Light, dark, or the phone's own setting — Claude Design has no screen for
 * this, so it follows the frame the other sub-pages already use.
 *
 * **A radio group, not a switch.** There are three states and only two of them
 * are "a theme"; the third is a decision not to have an opinion. A two-position
 * switch would have to either drop that or smuggle it in as a second control,
 * and "follow the system" is the default, so it cannot be the one that is hard
 * to express.
 *
 * The whole row is the target rather than just the button — `selectableGroup`
 * plus `selectable`, with the `RadioButton` given a null `onClick` so it draws
 * the state without competing for the tap.
 *
 * > **This was very nearly rewritten as a hand-rolled `Row` over a wrong
 * > reading, and the reading is the part worth keeping.** Dumping the
 * > accessibility tree appeared to show every option reporting `selected=false`
 * > — no announced selection, a real defect. It was the instrument. A selectable
 * > row emits **two** nodes at the same bounds: the `RadioButton`-classed one,
 * > which carries no state, and a sibling `View` that carries it — and
 * > `Role.RadioButton` maps a Compose `selected` onto the platform's
 * > **`checked`**, not its `selected`. Reading the first node's `selected`
 * > attribute is therefore false for every option including the chosen one. The
 * > real tree has `checkable,checked` on exactly one row. Filter an a11y dump by
 * > bounds, not by class name.
 */
@OptIn(ExperimentalMaterial3Api::class)
/**
 * The Permissions page in the frame every other sub-page uses.
 *
 * Follows the existing scaffold rather than inventing a look, which is the
 * precedent Appearance and first run both set — and it adds no components, so
 * the design system's roster is untouched by a page the design system does not
 * yet name.
 */
@Composable
fun SettingsPermissionsRoute(onBack: () -> Unit) {
    // NO SUBTITLE. The six rows each name a capability in full, and the summary
    // line under them already counts what is missing — a sentence restating that
    // in the header was the one line on the page saying nothing new.
    SettingsScaffold(
        title = "Permissions",
        onBack = onBack,
    ) { padding -> PermissionsScreen(padding) }
}

@Composable
fun SettingsAppearanceRoute(
    mode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    SettingsScaffold(
        title = "Appearance",
        subtitle = "Light or dark, or follow the phone",
        onBack = onBack,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).selectableGroup()) {
            ThemeMode.entries.forEach { option ->
                val selected = option == mode
                ListItem(
                    // Same as the top bar: a row is not a card, and ListItem
                    // defaults its container to `surface`.
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(option.label) },
                    leadingContent = {
                        // null onClick: the row owns the tap, so a second target
                        // here would be a second thing to announce and a smaller
                        // thing to miss.
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            // A selected radio is a UI component, not a fill, so
                            // it needs the readable gold too.
                            colors = RadioButtonDefaults.colors(
                                selectedColor = PetTheme.colors.accentText
                            ),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onSelect(option) },
                        )
                        .padding(vertical = PetSpacing.s2)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPetRoute(onBack: () -> Unit) {
    // No page subtitle, as on Permissions and Screen time: the first section
    // heading is "Device", which says where you are more precisely than a
    // sentence repeating the title.
    SettingsScaffold(
        title = "Your pet",
        onBack = onBack,
    ) { padding ->
        PetDeviceScreen(padding = padding)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsModelsRoute(onBack: () -> Unit) {
    SettingsScaffold(
        title = "Local AI models",
        subtitle = "The three models the pet needs to hear you, think, and speak",
        onBack = onBack,
    ) { padding ->
        ModelSettingsScreen(padding = padding)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenTimeRoute(
    appUsageViewModel: AppUsageViewModel,
    onBack: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Whatever the user did over there, re-ask on the way back: this is the
        // one moment usage access can have changed.
        appUsageViewModel.refreshUsage()
    }

    // NO SUBTITLE, for the same reason Permissions has none: the rule is the
    // first line of the page and said it better. Two sentences stacked, both
    // saying "choose apps and set a limit", was the header repeating the body.
    // SUBTITLE survives for the settings index row, where it is a teaser rather
    // than a duplicate of the text below it.
    SettingsScaffold(
        title = "Screen time",
        onBack = onBack,
    ) { padding ->
        ScreenTimeScreen(
            viewModel = appUsageViewModel,
            padding = padding,
            onOpenUsageSettings = {
                launcher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            },
        )
    }
}
