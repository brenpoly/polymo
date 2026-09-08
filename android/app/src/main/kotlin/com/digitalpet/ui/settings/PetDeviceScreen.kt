package com.digitalpet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import com.digitalpet.ble.PetBleRepository
import com.digitalpet.ui.components.core.Card
import com.digitalpet.ui.screens.PetChatViewModel
import com.digitalpet.ui.theme.PetSize
import com.digitalpet.ui.theme.PetSpacing
import com.digitalpet.ui.theme.PetTextSize
import com.digitalpet.ui.theme.PetTheme
import com.digitalpet.ui.components.pet.PetPanel
import com.digitalpet.pet.PetFaceSets
import com.digitalpet.pet.FaceSetAvailability
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.digitalpet.conversation.PetPersonas
import com.digitalpet.pet.NotificationAccess
import com.digitalpet.pet.PetDeviceText
import com.digitalpet.pet.PetStatusText
import com.digitalpet.service.PetForegroundService
import com.digitalpet.service.PetNotificationListener
import com.digitalpet.ui.components.core.PetButtonSecondary
import com.digitalpet.ui.components.core.PetTextButton
import com.digitalpet.ui.components.core.PetButton

/**
 * Your pet — Claude Design 2c. Pairing, and whether the link is up.
 *
 * **The same controls as the panel it replaces, minus the two that were ours.**
 * The design's own note asks for Disconnect, Forget and scan, and drops *Send
 * test* and the raw condition read-out: both are debug affordances, and the
 * condition is on the main surface already, said properly. Nothing that could
 * only be reached from here has gone.
 *
 * **Reset stays absent while the pet is alive**, unchanged and deliberate — see
 * the note on the dialog below. It is the one control on this screen that can
 * destroy something.
 */
@Composable
fun PetDeviceScreen(
    padding: PaddingValues,
    viewModel: PetChatViewModel = hiltViewModel(),
) {
    val state by viewModel.petConnection.collectAsState()
    val devices by viewModel.petDiscovered.collectAsState()
    /*
     * WHETHER A SCAN HAS BEEN RUN, this visit. Not persisted and not in the
     * repository, because the question it answers is "have I just looked" — the
     * discovered list is empty both before a search and after a fruitless one,
     * and only this tells them apart.
     */
    var hasScanned by remember { mutableStateOf(false) }
    val paired by viewModel.petPairedAddress.collectAsState()
    val info by viewModel.petInfo.collectAsState()
    val status by viewModel.petStatus.collectAsState()
    val condition by viewModel.petCondition.collectAsState()
    var confirmingReset by remember { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }
    val messages by viewModel.messages.collectAsState()
    val generating by viewModel.isGenerating.collectAsState()

    val connected = state == PetBleRepository.State.READY
    val scanning = state == PetBleRepository.State.SCANNING

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PetSpacing.screenMargin),
        // One rhythm rather than a top padding invented per child, which is what
        // the design draws: a 12dp gap between everything on this page.
        verticalArrangement = Arrangement.spacedBy(PetSpacing.s12)
    ) {
        // The pairing half of this page, and it is one thing: whether a pet
        // is connected, and how to find one. Splitting the status card from
        // the scan button put an unrelated preference between two halves of
        // the same question.
        Text(
            text = "Device",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (connected) Icons.Default.BluetoothConnected
                    else Icons.Default.BluetoothDisabled,
                    contentDescription = null,
                    tint = if (connected) PetTheme.colors.thriving
                           else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(PetSize.icon20)
                )
                Text(
                    text = when (state) {
                        PetBleRepository.State.IDLE ->
                            if (paired == null) "No pet paired" else "Not connected"
                        PetBleRepository.State.SCANNING -> "Scanning…"
                        PetBleRepository.State.CONNECTING -> "Connecting…"
                        PetBleRepository.State.READY -> "Connected"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    fontSize = PetTextSize.t13,
                    color = if (connected) PetTheme.colors.thriving
                            else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = PetSpacing.s8)
                )
            }

            // The name is the product's until naming is designed (DESIGN.md §4),
            // and the protocol version is the one fact here that says the two
            // halves actually agree about what they are speaking.
            Text(
                text = listOfNotNull(
                    "PolyMO".takeIf { paired != null },
                    info?.let { "firmware protocol v${it.version}" },
                ).joinToString(" · ").ifEmpty { "Nothing paired yet" },
                style = MaterialTheme.typography.bodySmall,
                fontSize = PetTextSize.t11_5,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = PetSpacing.s6)
            )

            paired?.let { address ->
                Text(
                    address,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = PetTextSize.t11,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = PetSpacing.s2)
                )
                Row(
                    Modifier.padding(top = PetSpacing.s12),
                    horizontalArrangement = Arrangement.spacedBy(PetSpacing.s8)
                ) {
                    if (connected) {
                        PetButtonSecondary(
                            onClick = { viewModel.petDisconnect() },
                        ) { Text("Disconnect") }
                    } else {
                        PetButton(onClick = { viewModel.petReconnect() }) { Text("Reconnect") }
                    }
                    PetButtonSecondary(
                        onClick = { viewModel.petForget() },
                    ) { Text("Forget") }
                }
            }
        }

        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        /*
         * THE WHOLE SCAN BLOCK IS ABSENT ONCE A PET IS PAIRED.
         *
         * This app remembers exactly one pet — `PetBleRepository.connect` sets
         * `pairedAddress` outright — so tapping a discovered device while paired
         * SILENTLY REPLACES your pet. A scan offered in that state is a swap
         * dressed as a search. *Forget* is directly above and says what it does,
         * which makes the destructive route the one that announces itself.
         */
        if (PetStatusText.showScan(paired != null)) {
        Row(
            // The one exception: the scan block sits a little further down, as
            // drawn — 12 from the rhythm plus 8 of its own.
            Modifier.fillMaxWidth().padding(top = PetSpacing.s8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PetSpacing.s12)
        ) {
            PetButton(onClick = {
                if (scanning) viewModel.petStopScan() else { hasScanned = true; viewModel.petStartScan() }
            }) {
                Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(PetSize.icon18))
                Text(if (scanning) "Stop" else "Scan for pets", Modifier.padding(start = PetSpacing.s8))
            }
            if (scanning) CircularProgressIndicator(Modifier.size(PetSize.icon20))
        }

        if (devices.isEmpty()) {
            // Null until a scan has actually run: a result needs a search.
            PetStatusText.scanHint(scanning, hasScanned, devices.size)?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.s8)) {
                devices.forEach { device ->
                    Card(onClick = { viewModel.petConnect(device.address) }) {
                        Text(
                            device.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            fontSize = PetTextSize.t12_5,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "${device.address}   ${device.rssi} dBm",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = PetTextSize.t11,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        } // end of the paired == null gate

        /*
         * SPEAKING UP ABOUT YOUR PHONE — off unless you ask for it.
         *
         * Notification access is granted for the conversation engine, not for
         * this, so a pet that started reading out arrivals the moment it had
         * the permission would be a surprise. Surprises that speak aloud in a
         * room are the worst kind.
         *
         * The pet says how many and, when they are all from one app, which app.
         * Several apps are counted but not named: it has a voice, and other
         * people are in the room.
         */
        Text(
            text = "Notifications",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        NotificationPermissionRow()
        val listenerBound = NotificationListenerRow()

        var announce by remember { mutableStateOf(viewModel.announcementsEnabled()) }
        /*
         * ABSENT WHEN IT CANNOT WORK, not drawn refusing — the same rule as
         * delete on a loaded model row. Without the binding this switch changed
         * a preference and nothing else, and it sat ON above a feature that
         * could not physically run. The row above says why, which is a better
         * answer than a control that lies.
         */
        if (listenerBound) Row(
            Modifier.fillMaxWidth().padding(vertical = PetSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Speak new notifications",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = PetDeviceText.ANNOUNCE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = announce,
                onCheckedChange = { announce = it; viewModel.setAnnouncements(it) },
            )
        }

        /*
         * QUIET HOURS — the PET's, configured from here.
         *
         * It keeps them in NVS and applies them with its own clock, so they
         * survive a reboot and a phone that never comes back (DESIGN.md §1
         * decision 1). What is shown is what the pet READ BACK, not what was
         * sent: it refuses a window leaving fewer than six waking hours,
         * because it ages by waking seconds and a 23-hour night would make it
         * immortal and the care mechanic pointless.
         */
        Text(
            text = "Quiet hours",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val quiet by viewModel.petQuietHours.collectAsState()
        val quietFrom = quiet?.first ?: (21 * 60)
        val quietTo = quiet?.second ?: (9 * 60)
        Text(
            text = PetDeviceText.QUIET_HOURS +
                if (quiet == null) " " + PetDeviceText.QUIET_HOURS_OFFLINE else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val ctx = LocalContext.current
        Row(
            Modifier.fillMaxWidth().padding(vertical = PetSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(true, false).forEach { isFrom ->
                val value = if (isFrom) quietFrom else quietTo
                PetButtonSecondary(
                    enabled = connected && quiet != null,
                    onClick = {
                        android.app.TimePickerDialog(
                            ctx,
                            { _, h, m ->
                                val picked = h * 60 + m
                                if (isFrom) viewModel.setQuietHours(picked, quietTo)
                                else viewModel.setQuietHours(quietFrom, picked)
                            },
                            value / 60, value % 60, true,
                        ).show()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = if (isFrom) PetSpacing.s8 else PetSpacing.s2),
                ) {
                    Text(
                        (if (isFrom) "From " else "To ") +
                            "%02d:%02d".format(value / 60, value % 60)
                    )
                }
            }
        }

        /*
         * PERSONALITY — the face and the voice as ONE choice.
         *
         * It had its own Settings entry for about an hour, which put a
         * preference about the PET a level away from everything else about the
         * pet — pairing, connection, its life. It belongs here, above the
         * reset: appearance is an ordinary thing to change, and the destructive
         * action stays last.
         *
         * NO DESCRIPTIONS ON THE ROWS. A sentence explaining what "Pixel" is
         * like is a worse answer than tapping it, which costs one tap and shows
         * the real thing on the device in front of you — and now changes how it
         * talks as well as how it looks.
         */
        val faceSetId by viewModel.petFaceSetId.collectAsState()
        val faceSupported by viewModel.petFaceSetSupported.collectAsState()
        val availability = FaceSetAvailability.of(
            connected = connected,
            characteristicPresent = faceSupported,
            petProtocol = info?.version,
        )

        Text(
            text = "Personality",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        /*
         * A real PetPanel, not a picture of one — the same drawing code the
         * home screen uses, on the same numbers the device draws from. It is
         * what makes cycling through the sets worth doing while the pet is out
         * of sight.
         */
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PetPanel(
                condition = null,
                listening = false,
                thinking = false,
                set = PetFaceSets.byId(faceSetId) ?: PetFaceSets.default,
            )
        }

        /*
         * SAYS WHY when nothing can be changed. This screen confirms nothing
         * until the pet does — the pet owns what it wears — so a request that
         * cannot land would otherwise be invisible. See [FaceSetAvailability];
         * that was not hypothetical.
         */
        availability.message?.let { why ->
            Text(
                text = why,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(Modifier.selectableGroup()) {
            PetFaceSets.all.forEach { option ->
                val selected = option.id == faceSetId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            enabled = availability.selectable,
                            role = Role.RadioButton,
                            onClick = {
                                /*
                                 * BOTH, voice first.
                                 *
                                 * The persona is phone-side and lands
                                 * immediately; the face is a request the pet
                                 * may refuse. Voice first means the two are
                                 * never left disagreeing while the write is in
                                 * flight.
                                 *
                                 * These rows stay gated on the pet being
                                 * reachable, so a personality cannot be chosen
                                 * with no pet at all — it is the PET's
                                 * personality, and the radio below shows what
                                 * the pet reports rather than what was tapped.
                                 */
                                option.personaId?.let(viewModel::selectPersona)
                                viewModel.selectFaceSet(option.id)
                            },
                        )
                        .padding(vertical = PetSpacing.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // null onClick: the row owns the tap, so a second target
                    // here would be a second thing to announce and a smaller
                    // thing to miss.
                    RadioButton(
                        selected = selected,
                        onClick = null,
                        enabled = availability.selectable,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = PetTheme.colors.accentText
                        ),
                    )
                    Text(
                        text = option.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = PetSpacing.s8),
                    )
                }
            }
        }

        /*
         * THE CONVERSATION, and it sits here rather than on a fifth settings
         * page because the transcript is the record of THIS PET talking — the
         * lines it says unprompted are in it too, not just replies.
         *
         * Above the reset, never below it: §5 rule 6 keeps the destructive
         * action last, and this one is recoverable in the sense that matters —
         * losing a chat log is not losing a pet.
         */
        if (messages.isNotEmpty()) {
        Text(
            text = "Conversation",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Text(
            // The block only renders when there IS a transcript, so there is no
            // empty-state wording to write. What this has to say instead is the
            // thing nobody can see: clearing it takes nothing away from the pet.
            text = PetDeviceText.CONVERSATION,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PetButtonSecondary(
            // Absent when there is nothing to clear, for the same reason the
            // readiness banner is: a control that does nothing teaches people
            // to stop reading controls. Disabled mid-reply rather than absent,
            // because that state lasts seconds and vanishing would look broken.
            onClick = { confirmingClear = true },
            enabled = !generating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (generating) "Wait for the reply to finish" else "Clear conversation")
        }
        }

        /*
         * Reset is offered ONLY when the pet is actually dead.
         *
         * DESIGN.md §1: death sets a flag rather than wiping the save so that
         * starting again stays an explicit user action — a pet that silently
         * respawns has no stakes. A button that could end a living pet at any
         * moment would give that away just as completely, so the button is not
         * merely disabled while the pet is alive, it is absent. The firmware
         * refuses the write too; neither check replaces the other.
         */
        if (condition?.dead == true) {
            PetButton(
                onClick = { confirmingReset = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Start a new pet") }
        }

        /* The scroll's own bottom, on the Column rather than on whichever child
         * happens to be last — it moved once already when the page was
         * reordered and silently left a gap in the middle. */
        Spacer(Modifier.height(PetSpacing.scrollBottom))

    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("Clear the conversation?") },
            // Names what is lost, like the reset dialog, and still forestalls
            // the question the count raises — the pet keeps its own screen.
            // What went was the middle clause about the pet's unprompted lines,
            // which is a description of the transcript rather than of the loss.
            text = { Text(PetDeviceText.clearConversation(messages.size)) },
            confirmButton = {
                PetTextButton(
                    onClick = {
                        confirmingClear = false
                        viewModel.clearConversation()
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                PetTextButton(
                    onClick = { confirmingClear = false },
                ) { Text("Keep it") }
            },
        )
    }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text("Start a new pet?") },
            // STILL NAMES WHAT IS LOST — that rule is the reason this dialog
            // exists, and the transcript going with the reset is named rather
            // than left to be discovered afterwards. What went is the opener,
            // which reported the pet's care record: not the question being
            // asked, and not the thing at risk. See PetDeviceText.
            text = { Text(PetDeviceText.startNewPet(messages.size)) },
            confirmButton = {
                PetTextButton(
                    onClick = {
                        confirmingReset = false
                        viewModel.petReset()
                    },
                ) { Text("Start a new pet") }
            },
            dismissButton = {
                PetTextButton(
                    onClick = { confirmingReset = false },
                ) { Text("Cancel") }
            }
        )
    }
}

/**
 * The way back into `POST_NOTIFICATIONS`, and the only place that admits it is
 * off.
 *
 * **Absent when the permission is held**, which is the same rule as delete on a
 * loaded model row: a control that has nothing to do is better not drawn than
 * drawn saying so. When it is granted the notification itself is the feedback,
 * sitting in the shade where it belongs.
 *
 * **It re-reads on resume**, because the fix for the blocked case happens in
 * another app entirely. Without that, coming back from the system settings
 * screen having just granted it would leave this row still insisting the pet
 * cannot reach you — the app contradicting a change the user had literally just
 * made, which is the exact failure the row exists to end.
 *
 * [NotificationAccess] has why a single `launch()` is not enough.
 */
@Composable
private fun NotificationPermissionRow() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    fun held() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(held()) }
    var asked by remember { mutableStateOf(false) }
    // `shouldShowRequestPermissionRationale` needs an Activity, not a Context —
    // and this screen is only ever hosted in one.
    val activity = context as? android.app.Activity
    var rationale by remember {
        mutableStateOf(
            activity?.shouldShowRequestPermissionRationale(
                Manifest.permission.POST_NOTIFICATIONS
            ) == true
        )
    }

    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        granted = allowed
        asked = true
        /*
         * ASK THE SERVICE TO POST AGAIN, or this button does nothing visible.
         *
         * The service dedupes on rendered text and a permission grant changes
         * none, so without this the notification stays missing until the pet's
         * condition happens to move — and the button that just said it would
         * turn notifications on would look exactly as broken as the bug it
         * fixes. Verified on device: permission green, shade still empty.
         */
        if (allowed) {
            context.startService(
                Intent(context, PetForegroundService::class.java)
                    .setAction(PetForegroundService.ACTION_RENOTIFY)
            )
        }
        // Re-read rather than assume: a decline flips this to true, and that is
        // what keeps the next tap offering a dialog instead of jumping to
        // settings the user does not need.
        rationale = activity?.shouldShowRequestPermissionRationale(
            Manifest.permission.POST_NOTIFICATIONS
        ) == true
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = held()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val next = NotificationAccess.next(granted, rationale, asked)
    val label = NotificationAccess.action(next) ?: return

    Card {
        Text(
            text = NotificationAccess.TITLE,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = NotificationAccess.BODY,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = PetSpacing.s6),
        )
        PetButton(
            onClick = {
                when (next) {
                    NotificationAccess.Next.ASK ->
                        request.launch(Manifest.permission.POST_NOTIFICATIONS)
                    // The app's own notification settings, not the whole
                    // system list — this is one toggle away from here.
                    NotificationAccess.Next.OPEN_SETTINGS -> context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                            Settings.EXTRA_APP_PACKAGE, context.packageName
                        )
                    )
                    NotificationAccess.Next.NOTHING -> Unit
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = PetSpacing.s8)
                .height(PetSize.touchMin),
        ) { Text(label, fontWeight = FontWeight.Bold, fontSize = PetTextSize.t13) }
    }
}

/**
 * The other half of first run step 5, and the half with no dialog.
 *
 * `POST_NOTIFICATIONS` can be asked for; **reading what has arrived cannot** —
 * Android grants a notification listener binding only in its own settings
 * screen. `FirstRunScreen.hasNotifications` has said in a comment since it was
 * written that this is "the half that gets skipped by accident", and on a real
 * device it was: the listener was absent from `enabled_notification_listeners`
 * and nothing in the app admitted it, while *Speak new notifications* sat ON.
 *
 * @return whether the binding is held, so the caller can drop the switch that
 *   depends on it rather than draw one that does nothing.
 */
@Composable
private fun NotificationListenerRow(): Boolean {
    val context = LocalContext.current
    var bound by remember { mutableStateOf(PetNotificationListener.isEnabled(context)) }

    // The grant happens in another app, so this is the only way back to the
    // truth — same reason the permission row above watches resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) bound = PetNotificationListener.isEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val label = NotificationAccess.listenerAction(bound) ?: return true

    Card {
        Text(
            text = NotificationAccess.LISTENER_TITLE,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = NotificationAccess.LISTENER_BODY,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = PetSpacing.s6),
        )
        PetButton(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = PetSpacing.s8)
                .height(PetSize.touchMin),
        ) { Text(label, fontWeight = FontWeight.Bold, fontSize = PetTextSize.t13) }
    }
    return false
}
