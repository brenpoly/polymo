package com.digitalpet.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.digitalpet.pet.Grants
import com.digitalpet.pet.PermissionInventory
import com.digitalpet.ui.components.core.Badge
import com.digitalpet.ui.components.core.BadgePill
import com.digitalpet.ui.components.core.Card
import com.digitalpet.ui.theme.PetSize
import com.digitalpet.ui.theme.PetSpacing
import com.digitalpet.ui.theme.PetTextSize
import com.digitalpet.ui.components.core.PetButtonSecondary

/**
 * Everything the pet has been allowed to do, and everything it has not.
 *
 * **The general answer to a shape that bit three times in one day.** First run
 * asks for each grant at the step that needs it, which DESIGN.md §5.2 requires
 * and which is right — but first run finishes once, and until this page it was
 * the only place any of them was ever requested. A step skipped meant a grant
 * lost, permanently, with nothing anywhere admitting it. Two recovery rows had
 * been built by hand, one per feature, and a third feature was still broken.
 *
 * **It reports rather than nags**, so it does not become the barrage §5.2
 * forbids: nothing here asks for anything until it is tapped.
 *
 * **Rows, not switches, and that is a platform fact rather than a preference.**
 * Android has no way for an app to hand a permission back —
 * `revokeSelfPermissionOnKill` applies by killing the process, and the listener
 * binding and usage appop have no revoke at all. A switch that will not go back,
 * or that snaps back after being tapped, is the same lie as a *Loaded* badge
 * over a model that failed. [PermissionInventory] has the reasoning.
 *
 * **It re-reads on resume**, because two of the six are granted in another app
 * entirely and the page would otherwise contradict a change just made.
 */
@Composable
fun PermissionsScreen(padding: PaddingValues) {
    val context = LocalContext.current
    var held by remember { mutableStateOf(Grants.all(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) held = Grants.all(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // One launcher for every dialog-grantable permission. The result is ignored
    // deliberately: `held` is re-read from the platform instead, because the
    // callback's boolean answers only the permission just asked for while the
    // page shows six.
    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { held = Grants.all(context) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PetSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(PetSpacing.s10),
    ) {
        Text(
            PermissionInventory.summary(held),
            style = MaterialTheme.typography.bodyMedium,
            color = if (PermissionInventory.missingCount(held) == 0)
                MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = PetSpacing.s6),
        )
        // Said once at the top rather than hedged into six rows, and it is a
        // fact about the manifest rather than a promise — see NOTHING_LEAVES.
        Text(
            PermissionInventory.NOTHING_LEAVES,
            style = MaterialTheme.typography.bodySmall,
            fontSize = PetTextSize.t11_5,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PermissionInventory.rows(held).forEach { row ->
            /*
             * KEYED ON THE GRANT AND ON WHETHER IT IS HELD.
             *
             * Without the second key, granting something would leave the row
             * open on the state it was opened for — and re-reading the same
             * `remember` after the answer changed is the shape of the bug that
             * left the notification's face stale in the shade. Granting one
             * closes it; losing one opens it.
             */
            var expanded by remember(row.grant, row.held) {
                mutableStateOf(PermissionInventory.startsExpanded(row.held))
            }
            Card {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PetSpacing.s8),
                    // THE WHOLE HEADER TOGGLES and the chevron is only its
                    // affordance — SlotCard's lesson, where putting the gesture
                    // on the icon gave it Material's 48dp minimum and made every
                    // card taller than drawn.
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            onClickLabel = if (expanded) "Hide what ${row.grant.title} allows"
                                           else "Show what ${row.grant.title} allows",
                        ) { expanded = !expanded },
                ) {
                    Text(
                        row.grant.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = PetTextSize.t13,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // Granted/Missing, NOT the model slots' Loaded/None. They
                    // are not one idea in two words: a model is a file read into
                    // memory, a permission is something the owner allowed. This
                    // shipped as "loaded" for one build and read as a category
                    // error the moment it was on a screen.
                    BadgePill(if (row.held) Badge.Granted else Badge.Missing)
                    Spacer(Modifier.weight(1f))
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(PetSize.icon20),
                    )
                }
                /*
                 * WHAT YOU ARE GRANTING, ALWAYS — this is the half somebody
                 * needs in order to decide, and it does not stop mattering once
                 * the answer is yes. The first version showed only what BREAKS
                 * without each grant, which is the readiness question rather
                 * than the consent one: it told a person who had already granted
                 * notification access nothing whatsoever about what they had
                 * allowed.
                 */
                AnimatedVisibility(visible = expanded) {
                    Column {
                        Text(
                            row.grant.allows + " " + row.grant.use,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = PetTextSize.t11_5,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = PetSpacing.s6),
                        )
                        // What is lost, and ONLY while it is lost. On a granted
                        // row this would describe a problem that does not exist.
                        if (!row.held) {
                            Text(
                                row.grant.consequence,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = PetTextSize.t11_5,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = PetSpacing.s6),
                            )
                        }
                    }
                }
                /*
                 * OUTSIDE the collapsible body on purpose. A missing grant's
                 * button is the reason somebody came to this page, and burying
                 * it behind a chevron on a row that is closed would be a fix you
                 * have to go looking for. Missing rows start open anyway, so
                 * this only matters after someone collapses one by hand.
                 *
                 * Absent on a held grant rather than disabled — there is nothing
                 * this app can do to a permission it already has.
                 */
                row.action?.let { label ->
                    PetButtonSecondary(
                        onClick = { act(row.grant, request::launch, context::startActivity) },
                        modifier = Modifier
                            .padding(top = PetSpacing.s8)
                            .heightIn(min = PetSize.touchMin),
                    ) { Text(label) }
                }
            }
        }
    }
}

/**
 * Where a row's button goes.
 *
 * Split out so the destinations are readable as a list: three of the six are
 * ordinary permission requests, and the three settings trips each land on a
 * DIFFERENT screen. Sending all of them to the app's settings page — the
 * obvious shortcut — would leave two of them nowhere near the toggle they need.
 */
private fun act(
    grant: PermissionInventory.Grant,
    request: (Array<String>) -> Unit,
    startActivity: (Intent) -> Unit,
) = when (grant) {
    PermissionInventory.Grant.BLUETOOTH_CONNECT, PermissionInventory.Grant.BLUETOOTH_SCAN ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            request(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        } else Unit

    PermissionInventory.Grant.RECORD_AUDIO ->
        request(arrayOf(Manifest.permission.RECORD_AUDIO))

    PermissionInventory.Grant.POST_NOTIFICATIONS ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            request(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        } else Unit

    PermissionInventory.Grant.NOTIFICATION_ACCESS ->
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    PermissionInventory.Grant.USAGE_ACCESS ->
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
}
