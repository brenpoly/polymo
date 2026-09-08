package com.digitalpet.ui.screens.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.digitalpet.data.ScreenTimeDisplay
import com.digitalpet.ui.components.core.SearchField
import com.digitalpet.ui.components.core.StatusChip
import com.digitalpet.ui.components.core.StepButton
import com.digitalpet.ui.components.settings.TrackedAppCard
import com.digitalpet.ui.screens.AppUsageViewModel
import com.digitalpet.ui.theme.PetRadius
import com.digitalpet.ui.theme.PetSize
import com.digitalpet.ui.theme.PetSpacing
import com.digitalpet.ui.theme.PetTextSize
import com.digitalpet.ui.components.core.PetButton

/**
 * Screen time — Claude Design 2e. Set the rules, and see what they did.
 *
 * **The allowance is per sitting**, which is what `ScreenTime.overusingPackage`
 * measures and what lets the pet recover when you put the phone down. **So the
 * row shows THIS SESSION**: `1 / 10 min`, from the same scan the simulation
 * uses. The day's total sits underneath as a fact in its own words.
 *
 * It moves every time you use the app, and it *falls* when a short session
 * follows a long one — verified on the device, 100 s reading `1 / 10 min` and a
 * fresh 40 s session dropping it to `0 / 10 min`. Closing the app resets the
 * allowance and the pet recovers; the reading holds what that session came to.
 *
 * It did not always. From 2026-08-07 the row divided *today* by the per-sitting
 * allowance under a heading explaining that the two were measured differently —
 * which read well in a mock where the example was `2 / 5`, and failed the first
 * time it met a real day. The numerator passes the denominator before lunch and
 * never comes down, so the bar was full and red every afternoon with **no way to
 * reset it**; and red is supposed to mean the thing that makes the pet ill,
 * while the pet was frequently perfectly well. `ScreenTimeDisplay` has the whole
 * three-round history.
 *
 * Three things from the design's own note, kept:
 *
 * - **Destructive actions live inside the expanded row.** Stop tracking is one
 *   deliberate tap away from the pencil, never one mis-tap away from the list.
 *   See `TrackedAppCard`, which carries that rule.
 * - **Add is a FAB**, so it survives a long list of tracked apps.
 * - **Red means the allowance is gone**, and the bar is the only thing that
 *   changes colour by itself — see `UsageBar`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTimeScreen(
    viewModel: AppUsageViewModel,
    padding: PaddingValues,
    onOpenUsageSettings: () -> Unit,
) {
    val monitored by viewModel.monitoredApps.collectAsState()
    val usageToday by viewModel.usageToday.collectAsState()
    val session by viewModel.latestSittingMs.collectAsState()
    val hasAccess by viewModel.hasUsageAccess.collectAsState()
    val isAdding by viewModel.isAddAppView.collectAsState()

    // Both on appearance: access is granted by leaving for system settings and
    // coming back, and today's totals have moved on by then anyway.
    LaunchedEffect(Unit) {
        viewModel.loadInstalledApps()
        viewModel.refreshUsage()
    }

    var query by remember { mutableStateOf("") }
    val rows = monitored.keys
        .map { it to viewModel.appName(it) }
        .filter { (_, name) -> name.contains(query, ignoreCase = true) }
        .sortedBy { (_, name) -> name.lowercase() }

    Box(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxSize()) {
            StatusChip(
                icon = if (hasAccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                label = if (hasAccess) "Usage access on" else "Usage access off",
                good = hasAccess,
                onClick = onOpenUsageSettings,
                modifier = Modifier.padding(
                    horizontal = PetSpacing.screenMargin, vertical = PetSpacing.s4
                )
            )

            /*
             * THE RULE, ABOVE EVERYTHING, AND ALWAYS. This page configures a
             * mechanic that could not be learned from it: every line used to
             * describe the pet's condition rather than what the user does or
             * what it costs them. Four sentences, and the fourth is the one it
             * would be flattering to omit — see ScreenTimeDisplay.
             */
            Text(
                text = ScreenTimeDisplay.HOW_IT_WORKS,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = PetSpacing.screenMargin,
                    end = PetSpacing.screenMargin,
                    top = PetSpacing.s10,
                )
            )

            /*
             * SAID PLAINLY, because this is the state in which the whole
             * mechanic silently does nothing. Without access the pet cannot be
             * made ill by anything, and every row below would read zero — which
             * looks like restraint rather than like a missing permission.
             */
            if (!hasAccess) {
                Text(
                    text = ScreenTimeDisplay.NO_ACCESS,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = PetSpacing.screenMargin,
                        end = PetSpacing.screenMargin,
                        top = PetSpacing.s6,
                    )
                )
            }

            /*
             * NO LEGEND BESIDE "Tracked apps", as of 2026-08-09.
             *
             * The design draws one — "used / allowed" — and it earned its place
             * for as long as the row's number was ambiguous. It said WHICH
             * quantity was being divided by the allowance, which mattered
             * enormously while that quantity was the day's total and the
             * fraction was two different measurements sharing a slash.
             *
             * The row says "Last session:" itself now, so the legend was the
             * same sentence a second time and further from the number it
             * described. **A label on the thing beats a legend above the
             * column**: it survives scrolling, it cannot be read against the
             * wrong row, and it leaves nothing to reconcile.
             *
             * Worth keeping the history straight — the legend was not wrong and
             * is not being overruled. It was load-bearing for three revisions of
             * this row and stopped being so when the row started labelling
             * itself. See ScreenTimeDisplay for all four readings.
             */
            Text(
                "Tracked apps",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                fontSize = PetTextSize.t12_5,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(
                    start = PetSpacing.screenMargin,
                    end = PetSpacing.screenMargin,
                    top = PetSpacing.s16,
                )
            )

            if (monitored.isNotEmpty()) {
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search tracked apps",
                    modifier = Modifier.padding(
                        horizontal = PetSpacing.screenMargin, vertical = PetSpacing.s10
                    )
                )
            }

            if (monitored.isEmpty()) {
                Text(
                    text = ScreenTimeDisplay.NOTHING_TRACKED,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = PetSpacing.screenMargin, vertical = PetSpacing.s24
                    )
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(
                    start = PetSpacing.screenMargin,
                    end = PetSpacing.screenMargin,
                    bottom = PetSpacing.scrollBottomUnderFab,
                ),
                verticalArrangement = Arrangement.spacedBy(PetSpacing.s8)
            ) {
                items(rows, key = { it.first }) { (pkg, name) ->
                    TrackedAppCard(
                        name = name,
                        sittingMs = session[pkg] ?: 0L,
                        todayMs = usageToday[pkg] ?: 0L,
                        allowanceMs = monitored[pkg] ?: 0L,
                        onStep = { up -> viewModel.stepAppThreshold(pkg, monitored[pkg] ?: 0L, up) },
                        onStopTracking = { viewModel.removeTrackedApp(pkg) },
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { viewModel.setIsAddAppView(true) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(PetRadius.r20),
            /*
             * FLAT, because the design system says so and Material disagreed
             * silently: "there are no drop shadows anywhere in the app — depth
             * comes from the tonal ladder and from the bottom sheet physically
             * overlapping the content" (`readme.md`, Visual foundations).
             *
             * `FloatingActionButton` defaults to 6dp of elevation, which is a
             * real cast shadow, and this was the only one in the app. Nothing
             * here asked for it: it is §7.7's third cause of drift — Material as
             * an unacknowledged third source of truth — and it took reading the
             * design system's prose to see it, because a shadow nobody wrote
             * down looks exactly like a shadow somebody chose.
             */
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = PetSize.flat,
                pressedElevation = PetSize.flat,
                focusedElevation = PetSize.flat,
                hoveredElevation = PetSize.flat,
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(PetSpacing.s20)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add apps to track")
        }

        if (isAdding) {
            AddAppsSheet(viewModel = viewModel)
        }
    }
}

/**
 * Picking apps to track, with one allowance for the whole selection.
 *
 * A sheet rather than a second screen, per DESIGN.md §6's "bottom sheets over
 * dialogs" and because this is a side trip: you come back to the list you were
 * looking at.
 *
 * Private, and it should stay that way — it is this screen's own flow rather
 * than a component. The design system lists it nowhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAppsSheet(viewModel: AppUsageViewModel) {
    val filtered by viewModel.filteredInstalledApps.collectAsState()
    val selected by viewModel.selectedAppsToAdd.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val allowance by viewModel.pendingAllowanceMs.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { viewModel.setIsAddAppView(false) },
        sheetState = sheetState,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = PetSpacing.screenMargin)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Add apps to track",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = PetTextSize.t17,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(PetSize.icon24)
                        .clickable { viewModel.setIsAddAppView(false) }
                )
            }

            SearchField(
                value = query,
                onValueChange = viewModel::setSearchQuery,
                placeholder = "Search apps",
                modifier = Modifier.padding(vertical = PetSpacing.s10)
            )

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false)
                    .heightIn(max = PetSize.appListMaxHeight),
                verticalArrangement = Arrangement.spacedBy(PetSpacing.s6)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    val isSelected = app.packageName in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(PetSize.appRowHeight)
                            .clip(RoundedCornerShape(PetRadius.r18))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { viewModel.toggleAppSelection(app.packageName) }
                            .padding(horizontal = PetSpacing.s14),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PetSpacing.s12)
                    ) {
                        Box(
                            Modifier
                                .size(PetSize.checkbox)
                                .clip(RoundedCornerShape(PetRadius.r7))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(PetSize.icon16)
                                )
                            }
                        }
                        Text(
                            app.appName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = PetTextSize.t12_5,
                            maxLines = 1,
                            // Installed app names are arbitrary and some are
                            // long. Clipping one mid-word reads as a bad name
                            // rather than as a narrow row.
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = PetSpacing.s12),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PetSpacing.s12)
            ) {
                Text(
                    /*
                     * The design says "Allowance for both", which is only right
                     * for exactly two. Counting is not enough on its own either:
                     * the first attempt said "Allowance for all 2" with NOTHING
                     * selected, because it clamped the count instead of asking
                     * whether there was a plural to describe.
                     */
                    if (selected.size >= 2) "Allowance for all ${selected.size}"
                    else "Allowance per sitting",
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = PetTextSize.t11_5,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                StepButton(Icons.Default.Remove, "Less") { viewModel.stepPendingAllowance(false) }
                Text(
                    ScreenTimeDisplay.allowance(allowance),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    fontSize = PetTextSize.t13,
                    color = MaterialTheme.colorScheme.onSurface,
                    // CENTRED IN ITS BOX, not start-aligned in it. The box is
                    // held wider than the reading so the two buttons do not
                    // shuffle under a finger that is still tapping — and
                    // without this the slack all fell on the right, so `5
                    // min` sat against the minus button instead of between
                    // the two. More visible since the default went 25 -> 5,
                    // because a shorter reading leaves more slack.
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = PetSize.allowanceWidth)
                )
                StepButton(Icons.Default.Add, "More") { viewModel.stepPendingAllowance(true) }
            }

            PetButton(
                onClick = { viewModel.confirmAddApps() },
                enabled = selected.isNotEmpty(),
                // The explicit r24 shape came out with the role: on a 48dp
                // button that IS Material's fully-rounded default, so it was a
                // line restating what would have happened anyway.
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (selected.size == 1) "Track 1 app" else "Track ${selected.size} apps",
                    fontWeight = FontWeight.Bold,
                    fontSize = PetTextSize.t13
                )
            }
            Spacer(Modifier.height(PetSpacing.s20).navigationBarsPadding())
        }
    }
}
