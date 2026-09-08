package com.digitalpet.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.digitalpet.ui.screens.AppUsageViewModel
import com.digitalpet.ui.screens.FirstRunScreen
import com.digitalpet.ui.screens.PetChatViewModel
import com.digitalpet.ui.screens.PetHomeScreen
import com.digitalpet.ui.theme.ThemeMode
import com.digitalpet.ble.PetBleRepository
import com.digitalpet.pet.FaceSetAvailability
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState

/**
 * The app's shell.
 *
 * **NO BOTTOM BAR AND NO SCAFFOLD.** Both existed to hold a navigation bar and
 * an input row above it; the Claude Design surface has neither — the transcript
 * and its input live in a sheet on the main screen, so the shell's only job is
 * the back stack. That also removes the window-inset problem the previous
 * arrangement was working around.
 *
 * **THE VIEWMODELS ARE HOISTED HERE**, and this matters more now than it did
 * with two tabs. Calling `hiltViewModel()` inside each `composable { }` scopes
 * the instance to that back-stack entry, so every settings sub-page would get
 * its *own* `PetChatViewModel` — a fresh set of flow subscriptions each time
 * somebody opened a chip. Taking them once here gives every destination the same
 * instance. The conversation itself was never at risk (it lives in
 * `PetConversationEngine` on a process scope), which is exactly why the waste
 * would have been invisible.
 */
@Composable
fun PetNavHost(
    /*
     * THE THEME IS PASSED IN, NOT LOOKED UP HERE.
     *
     * Everything else on this screen is state some ViewModel owns; the theme is
     * owned by whoever applies it, which is MainActivity — it wraps the whole
     * app in DigitalPetTheme, so a mode read anywhere further down would be read
     * after the decision it feeds had already been made. Threading it through is
     * two parameters and no ambiguity about who decides.
     */
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    /** Whether setup has been walked through. Read ONCE — see [start]. */
    firstRunFinished: Boolean,
    onFirstRunFinished: () -> Unit,
    chatViewModel: PetChatViewModel = hiltViewModel(),
    appUsageViewModel: AppUsageViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()

    /*
     * REMEMBERED, so that finishing first run does not rebuild the graph.
     *
     * `startDestination` is read when the NavHost is composed, and the flag it
     * comes from is a live `StateFlow`. Passing it straight through would change
     * the start destination the moment setup finished — which recreates the
     * whole host and drops the back stack underneath whatever screen the user
     * had just been sent to. Capturing it once makes the start destination a
     * decision taken at launch, which is what it actually is.
     */
    val start = remember {
        if (firstRunFinished) PetDestination.Pet.route else PetDestination.FirstRun.route
    }

    NavHost(
        navController = navController,
        startDestination = start,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(PetDestination.FirstRun.route) {
            FirstRunScreen(
                chatViewModel = chatViewModel,
                appUsageViewModel = appUsageViewModel,
                // The two steps first run cannot finish itself hand off to the
                // real pages rather than to a copy of them: one place to pair,
                // one place to import a model, and coming back IS the progress.
                onOpenPetSettings = { navController.navigate(PetDestination.SettingsPet.route) },
                onOpenModelSettings = { navController.navigate(PetDestination.SettingsModels.route) },
                onOpenScreenTimeSettings = {
                    navController.navigate(PetDestination.SettingsScreenTime.route)
                },
                onFinished = {
                    onFirstRunFinished()
                    // inclusive: there is no going back to setup with the system
                    // back gesture, which would land on a flow that has already
                    // decided it is done and would immediately finish again.
                    navController.navigate(PetDestination.Pet.route) {
                        popUpTo(PetDestination.FirstRun.route) { inclusive = true }
                    }
                },
            )
        }
        composable(PetDestination.Pet.route) {
            PetHomeScreen(
                chatViewModel = chatViewModel,
                onOpenSettings = { navController.navigate(PetDestination.Settings.route) },
                // The chips go straight to the section that fixes them rather
                // than to the index — the fastest route to a repair is from the
                // thing that just told you it was broken.
                onOpenPetSettings = { navController.navigate(PetDestination.SettingsPet.route) },
                onOpenModelSettings = { navController.navigate(PetDestination.SettingsModels.route) },
            )
        }
        composable(PetDestination.Settings.route) {
            SettingsRoute(
                onBack = { navController.popBackStack() },
                onOpenPet = { navController.navigate(PetDestination.SettingsPet.route) },
                onOpenModels = { navController.navigate(PetDestination.SettingsModels.route) },
                onOpenScreenTime = { navController.navigate(PetDestination.SettingsScreenTime.route) },
                onOpenPermissions = { navController.navigate(PetDestination.SettingsPermissions.route) },
                onOpenAppearance = { navController.navigate(PetDestination.SettingsAppearance.route) },
            )
        }
        composable(PetDestination.SettingsPermissions.route) {
            SettingsPermissionsRoute(onBack = { navController.popBackStack() })
        }
        composable(PetDestination.SettingsAppearance.route) {
            SettingsAppearanceRoute(
                mode = themeMode,
                onSelect = onThemeMode,
                onBack = { navController.popBackStack() },
            )
        }
        composable(PetDestination.SettingsPet.route) {
            SettingsPetRoute(onBack = { navController.popBackStack() })
        }
        composable(PetDestination.SettingsModels.route) {
            SettingsModelsRoute(onBack = { navController.popBackStack() })
        }
        composable(PetDestination.SettingsScreenTime.route) {
            SettingsScreenTimeRoute(
                appUsageViewModel = appUsageViewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
