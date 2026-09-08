package com.digitalpet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.digitalpet.data.AppearanceRepository
import com.digitalpet.service.PetForegroundService
import com.digitalpet.ui.nav.PetNavHost
import com.digitalpet.ui.theme.DigitalPetTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @javax.inject.Inject
    lateinit var petBle: com.digitalpet.ble.PetBleRepository

    /** Light or dark, and whether that is ours to decide. See ThemeMode. */
    @javax.inject.Inject
    lateinit var appearance: AppearanceRepository

    /** Whether setup has been walked through. See FirstRunRepository. */
    @javax.inject.Inject
    lateinit var firstRun: com.digitalpet.data.FirstRunRepository

    /**
     * Reconnect the remembered pet whenever the app comes to the foreground.
     *
     * onStart rather than onCreate, so this covers returning to an app that was
     * already running as well as a cold start — stopping the pet leaves this
     * Activity alive, so onCreate would not run again on the way back.
     *
     * The service is not started here. It follows the link: PetBleRepository
     * brings it up once the pet is actually connected, which keeps "the pet is
     * connected" as the single condition everything hangs off.
     *
     * connectRemembered() is a no-op when there is no paired device or the link
     * is already up, so calling it on every foreground is cheap.
     */
    override fun onStart() {
        super.onStart()
        petBle.connectRemembered()
    }

    /*
     * NO PERMISSIONS ARE REQUESTED HERE, AND THAT IS THE POINT.
     *
     * This used to fire RECORD_AUDIO, BLUETOOTH_CONNECT, BLUETOOTH_SCAN and
     * POST_NOTIFICATIONS in one `RequestMultiplePermissions` call at the top of
     * onCreate — four system dialogs stacked in front of an app the user had not
     * seen yet, on every cold start until each was answered.
     *
     * DESIGN.md §5.2 forbids exactly that: **permissions are asked for one at a
     * time, at the step that needs them, never as an opening barrage.** Building
     * first run without removing this would have left the barrage in front of
     * the flow designed to replace it.
     *
     * Where they went:
     *
     * | Permission | Now asked |
     * |---|---|
     * | `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` | first run step 2, beside the pairing screen that needs them |
     * | `POST_NOTIFICATIONS` | first run step 5, with the listener binding it is half of |
     * | `RECORD_AUDIO` | when the microphone is tapped — see `PetHomeScreen` |
     *
     * `RECORD_AUDIO` is the one §5.2 does not list, because it belongs to the
     * phone-mic *fallback* rather than to setup: the pet's own microphone is the
     * primary path and needs nothing from this device. Asking for it at the tap
     * is the same rule applied to a step the flow does not have.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            /*
             * THE THEME IS DECIDED HERE AND NOWHERE ELSE.
             *
             * `DigitalPetTheme` used to default to `isSystemInDarkTheme()` and
             * nothing could say otherwise. The mode comes from a repository
             * rather than from `rememberSaveable` because it has to survive the
             * process, and it is resolved against the system's answer in one
             * place — see ThemeMode.isDark, which is a pure function so that
             * adding a fourth mode is a compile error rather than a silent
             * fall-through to light.
             */
            val themeMode by appearance.mode.collectAsState()
            val firstRunFinished by firstRun.finished.collectAsState()

            DigitalPetTheme(darkTheme = themeMode.isDark(isSystemInDarkTheme())) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    /*
                     * WAS A HARDCODED `DarkBackground`, which is exactly the
                     * literal §6.2 rule 3 warns about — a theme bypassed at the
                     * one place the theme is applied. It survived because the
                     * screens all paint their own backgrounds over it, so the
                     * app was only ever dark underneath. Choosing light
                     * deliberately is what would have made it visible.
                     */
                    color = MaterialTheme.colorScheme.background
                ) {
                    PetNavHost(
                        themeMode = themeMode,
                        onThemeMode = appearance::setMode,
                        firstRunFinished = firstRunFinished,
                        onFirstRunFinished = firstRun::markFinished,
                    )
                }
            }
        }
    }
}
