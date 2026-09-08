package com.digitalpet.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import com.digitalpet.pet.FirstRun
import com.digitalpet.pet.FirstRunState
import com.digitalpet.pet.FirstRunStep
import com.digitalpet.ui.components.pet.PetPanel
import com.digitalpet.ui.theme.PetRadius
import com.digitalpet.ui.theme.PetSize
import com.digitalpet.ui.theme.PetSpacing
import com.digitalpet.ui.theme.PetTextSize
import com.digitalpet.ui.theme.PetTheme
import com.digitalpet.ui.components.core.PetTextButton
import com.digitalpet.ui.components.core.PetButton

/**
 * First run — DESIGN.md §5.2, and the last unbuilt flow in §5.
 *
 * **The design system draws no screen for this**, and §7.4 records it as
 * undrawn. So it follows the frame the rest of the app already uses — the
 * screen margin, the type scale, the same pet panel the main surface leads with
 * — rather than inventing a look. That is the precedent Appearance set: a screen
 * the design does not cover copies the ones it does.
 *
 * **It adds no components**, deliberately. Under §7.2 the design system decides
 * what components exist, and it has no card for anything in here; inventing one
 * would put a twentieth entry in a roster `ComponentRosterTest` pins at
 * nineteen. Everything below is either a shared component or this screen's own
 * arrangement, which is §6.3's rule: a screen's own flow is not a component.
 *
 * ### What this screen actually does
 *
 * Very little, and that is the design. **Two of the five steps cannot be
 * completed here** — pairing happens on *Your pet* and models are imported on
 * *Local AI models* — so the flow hands off and you come back. Because
 * `FirstRun` derives the step from what is true, coming back with the work done
 * simply *is* progress; nothing reports completion, so nothing can forget to.
 *
 * ### Leaving is a first-class outcome
 *
 * §5.2: *a run that stops at step 2 has still achieved something.* There is a
 * way out of every step after the welcome, and taking it is not a failure state
 * — the main surface reports each of these conditions in the place that fixes
 * it, which it did before this screen existed.
 *
 * ### Two cases worth knowing about, both checked rather than assumed
 *
 * **An existing install sees the welcome once and nothing else.** The finished
 * flag is a new preference, so it is false for everyone on the update that
 * brings this; every step after the welcome is already satisfied, so
 * acknowledging it makes the step `null` and the flow ends on the same tap. That
 * was preferred to seeding the flag from "a pet is already paired": the
 * heuristic is one more thing that can be wrong, and what it saves is a single
 * tap on a screen that introduces something new.
 *
 * **A configured device does not flash the models step while its brain loads.**
 * `readiness` folds `Unloaded` to `Missing`, and a multi-gigabyte model takes
 * seconds — but `ModelRepository.restoreLlm` dispatches `loadModel` at
 * construction, so a remembered model reports `Loading` within a dispatch and
 * `Loading` satisfies the step. `Missing` therefore means what it says: no file.
 */
@Composable
fun FirstRunScreen(
    chatViewModel: PetChatViewModel,
    appUsageViewModel: AppUsageViewModel,
    onOpenPetSettings: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onOpenScreenTimeSettings: () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val paired by chatViewModel.petPairedAddress.collectAsState()
    val readiness by chatViewModel.readiness.collectAsState()
    val usageAccess by appUsageViewModel.hasUsageAccess.collectAsState()

    /*
     * PERMISSION STATE IS RE-READ, NOT REMEMBERED.
     *
     * Three of these are granted by leaving the app — for the system settings
     * screen, or a permission dialog — so a value captured once would be stale
     * exactly when it mattered. `bump` is incremented whenever we come back from
     * somewhere that could have changed one, which re-runs the reads below.
     *
     * The alternative, a lifecycle observer on ON_RESUME, is the tidier-looking
     * version of the same thing and would also fire on every unrelated resume.
     * This fires when something we asked for could have happened.
     */
    var bump by remember { mutableStateOf(0) }
    var welcomeAcknowledged by remember { mutableStateOf(false) }
    var skipped by remember { mutableStateOf(emptySet<FirstRunStep>()) }

    val bluetoothGranted = remember(bump) { hasBluetooth(context) }
    val notificationsGranted = remember(bump) { hasNotifications(context) }

    // Usage access is an appop rather than a permission, so it has its own read.
    LaunchedEffect(bump) { appUsageViewModel.refreshUsage() }

    val state = FirstRunState(
        welcomeAcknowledged = welcomeAcknowledged,
        bluetoothGranted = bluetoothGranted,
        petPaired = paired != null,
        readiness = readiness,
        usageAccess = usageAccess,
        notificationsGranted = notificationsGranted,
        skipped = skipped,
    )
    val step = FirstRun.currentStep(state)

    // Nothing left to do — including on a device that was already set up before
    // this screen existed, which is the upgrade case and gets no tour at all.
    LaunchedEffect(step) { if (step == null) onFinished() }
    if (step == null) return

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { bump++ }
    val settingsTrip = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { bump++ }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = PetSpacing.screenMargin),
    ) {
        StepProgress(step)

        Spacer(Modifier.height(PetSpacing.s24))

        /*
         * THE PET, at the same size the main surface draws it.
         *
         * The welcome's whole job is the one expectation the app cannot recover
         * from being wrong about — that there is a physical thing — and a
         * picture of the hardware says it faster than the sentence does. It
         * stays for the rest of the flow because the steps are about the pet,
         * and a wizard that drops its subject after the first screen reads as a
         * settings form.
         *
         * `null` condition on purpose: nothing has been heard from yet, and
         * §5.0 rule 2 says an unknown reading is never rendered as a value.
         */
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PetPanel(condition = null, listening = false, thinking = false)
        }

        Spacer(Modifier.height(PetSpacing.s24))

        Text(
            FirstRun.title(step),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            fontSize = PetTextSize.t22,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(PetSpacing.s12))
        Text(
            FirstRun.body(step, state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.weight(1f))

        PetButton(
            onClick = {
                when (step) {
                    FirstRunStep.WELCOME -> welcomeAcknowledged = true
                    FirstRunStep.PET -> {
                        // The permission first, then the place that uses it. If
                        // it is already granted the launcher returns at once and
                        // the trip to pairing is the only thing anyone sees.
                        permissions.launch(bluetoothPermissions())
                        onOpenPetSettings()
                    }
                    FirstRunStep.MODELS -> onOpenModelSettings()
                    FirstRunStep.SCREEN_TIME -> onOpenScreenTimeSettings()
                    FirstRunStep.NOTIFICATIONS -> {
                        /*
                         * TWO SEPARATE GRANTS, and only one of them is a
                         * permission. POST_NOTIFICATIONS is a runtime
                         * permission; reading what is waiting is a listener
                         * binding granted in a system settings screen, and
                         * there is no API to ask for it in a dialog.
                         */
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        }
                        settingsTrip.launch(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(PetSize.touchMin),
        ) {
            Text(FirstRun.action(step), fontWeight = FontWeight.Bold, fontSize = PetTextSize.t13)
        }

        /*
         * THE WAY OUT, and what it does depends on what declining costs.
         *
         * On a blocking step there is nothing to step over to — the product does
         * not work without it — so this ends the run. On an optional one it
         * steps past, and the step comes back: the main surface goes on
         * reporting it in the place that fixes it.
         */
        FirstRun.secondary(step)?.let { label ->
            PetTextButton(
                onClick = { if (step.optional) skipped = skipped + step else onFinished() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(label, fontSize = PetTextSize.t12_5)
            }
        }

        FirstRun.cost(step)?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                fontSize = PetTextSize.t11,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = PetSpacing.s8),
            )
        }

        Spacer(Modifier.height(PetSpacing.s16))
    }
}

/**
 * `2 of 5`, and a bar of five segments.
 *
 * Deliberately the meter's shape rather than a new one: four segments wholly
 * filled or wholly empty is how this app already draws a discrete score, and
 * five steps is the same kind of quantity. It is not `Meter` itself — that
 * component means *a reading about the pet*, and borrowing it for a wizard's
 * progress would make a reading out of something that is not one.
 */
@Composable
private fun StepProgress(step: FirstRunStep) {
    val position = FirstRun.position(step)
    Column(Modifier.padding(top = PetSpacing.s16)) {
        Text(
            "Setting up · $position of ${FirstRun.total}",
            style = MaterialTheme.typography.labelMedium,
            fontSize = PetTextSize.t11,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(PetSpacing.s8))
        Row(horizontalArrangement = Arrangement.spacedBy(PetSpacing.s5)) {
            repeat(FirstRun.total) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(PetSize.meterSegmentHeight)
                        .clip(PetRadius.pill)
                        .background(
                            if (i < position) MaterialTheme.colorScheme.primary
                            else PetTheme.colors.track
                        )
                )
            }
        }
    }
}

/**
 * The Bluetooth permissions, or none below API 31 where they are implicit.
 *
 * `minSdk` is 31 so the empty case cannot happen today; it is written this way
 * because `bluetoothPermissions()` and [hasBluetooth] must agree, and two
 * version checks written separately are two chances to disagree.
 */
private fun bluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        emptyArray()
    }

private fun hasBluetooth(context: Context): Boolean =
    bluetoothPermissions().all {
        ContextCompat.checkSelfPermission(context, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

/**
 * Both halves of "let it tell you things", and **it is granted only when both
 * are**.
 *
 * `POST_NOTIFICATIONS` lets the foreground notification be seen; the listener
 * binding lets the pet read what is waiting so it can summarise. Treating either
 * alone as done would leave the step looking finished while half the feature is
 * missing — and the half that is missing is the one with no dialog to ask for
 * it, so it is the half that gets skipped by accident.
 */
private fun hasNotifications(context: Context): Boolean {
    val post = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    // One definition, shared with the settings screen's recovery row — see
    // PetNotificationListener.isEnabled. This used to be a `contains` on the
    // whole setting string, which any package whose name merely contained ours
    // would have satisfied.
    return post && com.digitalpet.service.PetNotificationListener.isEnabled(context)
}
