package com.digitalpet.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.digitalpet.ble.PetBleRepository
import com.digitalpet.pet.PetReadiness
import com.digitalpet.pet.PetStatusText
import com.digitalpet.ui.components.chat.MessageInputBar
import com.digitalpet.ui.components.core.StatusChip
import com.digitalpet.ui.components.pet.CareCard
import com.digitalpet.ui.components.pet.PetHeader
import com.digitalpet.pet.PetFaceSets
import com.digitalpet.ui.components.pet.PetPanel
import com.digitalpet.ui.screens.components.ChatSheetGeometry
import com.digitalpet.ui.screens.components.PetChatSection
import com.digitalpet.ui.theme.PetRadius
import com.digitalpet.ui.theme.PetSize
import com.digitalpet.ui.theme.PetSpacing

/**
 * The main surface — Claude Design `Digital Pet - Main Surface`, screens 2a/2b.
 *
 * **ONE SURFACE, NOT TWO.** The transcript is a sheet at the bottom of this
 * screen rather than a destination of its own, which supersedes DESIGN.md §5.1's
 * "two destinations and a settings route" and removes the bottom navigation
 * added on 2026-08-05. The design's own title is "One main surface", and the
 * reasoning §5.1 gave for separating them still holds — a conversation normally
 * runs between the pet's microphone and speaker with the phone in a pocket — it
 * simply argues for the transcript being *secondary* rather than *elsewhere*.
 *
 * Reading order down the screen is the order of the questions someone actually
 * asks: who is this and is it alright (header), can it reach me and can it think
 * (chips), what does it look like (panel), how is it (care), what did it say
 * (sheet).
 *
 * **2a and 2b are the same composable.** The dark screen differs only by colour
 * scheme plus the mic becoming a stop button while recording, so there is one
 * implementation and the theme does the rest.
 *
 * **This file composes; it does not draw.** Every box on it — the header, the
 * chips, the panel, the care card, the input, the suggestions — is a component
 * in `ui/components/`, named as the design system names it. It was not always
 * so: five of them were `private fun`s here, which is one of the four causes of
 * drift DESIGN.md §7.7 lists. What is left in this file is the arrangement and
 * the sheet's arithmetic, which is genuinely this screen's own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PetHomeScreen(
    chatViewModel: PetChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenPetSettings: () -> Unit,
    onOpenModelSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isGenerating by chatViewModel.isGenerating.collectAsState()
    val isRecording by chatViewModel.isRecording.collectAsState()
    val isTranscribing by chatViewModel.isTranscribing.collectAsState()
    val petCondition by chatViewModel.petCondition.collectAsState()
    val petConnection by chatViewModel.petConnection.collectAsState()
    val readiness by chatViewModel.readiness.collectAsState()
    val petPaired by chatViewModel.petPairedAddress.collectAsState()
    val switchedOff by chatViewModel.petSwitchedOff.collectAsState()
    val faceSetId by chatViewModel.petFaceSetId.collectAsState()

    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) chatViewModel.startRecording() }

    val bluetoothOn = chatViewModel.isBluetoothOn()
    val stale = PetStatusText.readingsAreStale(petConnection, bluetoothOn, switchedOff)
    val shown = petCondition.takeIf { !stale }

    /*
     * A REAL MATERIAL BOTTOM SHEET, not a hand-rolled one.
     *
     * The first attempt animated a height between two values and snapped on drag
     * intent. It built, it ran, and it felt wrong — no continuous drag, no
     * settling, no nested scroll, and a handle that was mine rather than the
     * platform's. Standard bottom sheets are a solved problem and M3 ships the
     * solution; reimplementing the easy 20% of it produced something that looked
     * right in a screenshot and not under a thumb.
     *
     * **THE PEEK SHOWS THE TOP OF THE SHEET**, and that single fact decides the
     * whole arrangement. The transcript runs newest-first so the collapsed sheet
     * opens on the latest exchange rather than on history, and the input is a
     * fixed footer OUTSIDE the sheet so it stays reachable in every sheet
     * position instead of competing for the peek. See PetChatSection.
     */
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            // The pet must never be left with no way to talk to it, so the sheet
            // has no hidden state to get stuck in.
            skipHiddenState = true,
        )
    )

    /*
     * THE NUMBERS THE SHEET USED TO GUESS, MEASURED INSTEAD.
     *
     * All three are read from the layout rather than written down, and the
     * footer is the one that shows why it matters: it is not a fixed size,
     * because the notification suggestion row appears and disappears inside it.
     * The old fixed 112dp preview was sized against a footer with no suggestions
     * in it, so whenever one was showing the footer covered the bottom of the
     * very message the preview existed to show. See ChatSheetGeometry.
     *
     * These are in the scaffold's own coordinate space — which is this Box's,
     * since the scaffold fills it — so that they can be used alongside the
     * sheet's offset without a second frame of reference.
     */
    var rootBottomPx by remember { mutableFloatStateOf(0f) }
    var footerPx by remember { mutableFloatStateOf(0f) }
    var handlePx by remember { mutableFloatStateOf(0f) }

    /*
     * HOW FAR THE SHEET MAY OPEN: to the bottom edge of the header, and no
     * further. Two measurements, and BOTH are deliberately taken outside the
     * IME inset.
     *
     * A sheet's expanded height IS its content height (DESIGN.md §6.4a rule 2),
     * so this number is the sheet's travel. It used to be a written-down 560dp,
     * which stopped short of the header by whatever the screen happened to be.
     *
     * **The keyboard is the trap here, and it is the reason `fullHeightPx`
     * exists separately from `rootBottomPx`.** The root carries `imePadding()`,
     * so the obvious measurement — the one already on this Box — shrinks by ~750
     * px the moment the keyboard opens. Sizing the content from that would
     * *resize the sheet's content while it is open*, which is the exact thing
     * rule 2 forbids and which the 112dp preview swap was deleted for. Measured
     * above the padding, it does not move.
     *
     * The header's bottom is read in the same space: the Box's top is the
     * window's top (imePadding only pads the bottom), so `boundsInRoot().bottom`
     * and this height are the same ruler.
     */
    var fullHeightPx by remember { mutableFloatStateOf(0f) }
    var headerBottomPx by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            /*
             * THE KEYBOARD IS HANDLED ONCE, HERE, FOR THE WHOLE SURFACE.
             *
             * It used to be handled on the input footer alone, and that was
             * wrong in both directions at once — the two faults reported
             * together on 2026-08-07:
             *
             * - **The input flew up the screen.** The window had no declared
             *   `windowSoftInputMode`, and the platform default resized it; a
             *   footer `imePadding()` then charged for the keyboard a second
             *   time. Measured: the window went 2142 -> 1391 px and the footer
             *   was pushed a further 841 px above that.
             * - **The sheet stayed put and the keyboard covered it.** With the
             *   window not resizing, padding only the footer leaves the sheet
             *   where it was — its collapsed peek runs 1557..2142 and the
             *   keyboard covers everything below ~1355, so the transcript was
             *   entirely behind it. That is why replies "only showed up once
             *   the sheet was expanded": expanding is what dragged the
             *   transcript out from behind the keyboard.
             *
             * Putting the inset on the root reproduces the good half of a window
             * resize — the scaffold, the sheet, its anchors and the footer all
             * move up together — without depending on a deprecated framework
             * path to do it. `onSizeChanged` sits INSIDE the padding on purpose,
             * so the height the sheet's arithmetic uses is the height the
             * surface actually has.
             */
            // ABOVE the IME inset on purpose — see fullHeightPx.
            .onSizeChanged { fullHeightPx = it.height.toFloat() }
            .imePadding()
            .onSizeChanged { rootBottomPx = it.height.toFloat() }
    ) {
    /*
     * Falls back to the old constant until both measurements have arrived, which
     * is one frame. `sheetPeek` is the floor: content shorter than the peek is
     * already fully visible, so there would be nothing to expand into and the
     * drag handle would correctly do nothing.
     */
    val sheetContentDp = with(LocalDensity.current) {
        if (fullHeightPx > 0f && headerBottomPx > 0f)
            /*
             * MINUS THE HANDLE, because the sheet is the handle PLUS this. The
             * first version left it out and the sheet opened 109px too far,
             * covering the bottom of the very header it was meant to stop under
             * — measured on the device at a top edge of 161 against a header
             * ending at 270, which is the handle almost exactly.
             */
            (fullHeightPx - headerBottomPx - handlePx).toDp()
                .coerceAtLeast(PetSize.sheetPeek)
        else PetSize.sheetContent
    }
    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = PetSize.sheetPeek,
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetShape = RoundedCornerShape(
            topStart = PetRadius.sheetTop, topEnd = PetRadius.sheetTop
        ),
        /*
         * The platform's own handle, wrapped only to measure it. The sheet's
         * content starts below it, and that offset has to come from somewhere —
         * measuring the real one beats writing down a number that Material is
         * free to change. It came out at 109px, 48.4dp, on a Pixel 9 Pro.
         */
        sheetDragHandle = {
            Box(Modifier.onSizeChanged { handlePx = it.height.toFloat() }) {
                BottomSheetDefaults.DragHandle()
            }
        },
        /*
         * CONSTANT HEIGHT, and it has to be.
         *
         * The previous attempt sized the transcript from the sheet's own state
         * so the input could sit at its bottom. That deadlocks: a sheet's height
         * IS its content height, so the content came to 258dp against a 300dp
         * peek — already fully visible, nothing to expand into, and a drag
         * handle that correctly did nothing. The content height depended on
         * being expanded, and expanding depended on the content height.
         *
         * So the sheet holds only the transcript, at a fixed height, and the
         * input left the sheet entirely — see the footer below.
         */
        sheetContent = {
            ChatSheetContent(
                chatViewModel = chatViewModel,
                sheetState = scaffoldState.bottomSheetState,
                rootBottomPx = rootBottomPx,
                footerPx = footerPx,
                handlePx = handlePx,
                contentHeight = sheetContentDp,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                /*
                 * The app is edge-to-edge (MainActivity calls enableEdgeToEdge),
                 * so without this the header draws UNDER the status bar and the
                 * settings button cannot be tapped — the OS gets the touch. The
                 * Scaffold this replaced was applying the inset for us, and
                 * removing it took the inset with it.
                 */
                .statusBarsPadding()
        ) {
            PetHeader(
                onOpenSettings = onOpenSettings,
                modifier = Modifier
                    .padding(
                        start = PetSpacing.screenMargin,
                        end = PetSpacing.s14,
                        top = PetSpacing.s4,
                    )
                    // The sheet opens to exactly here. Measured rather than
                    // derived from the status bar plus a title height, because
                    // Baloo 2 does not render at the size its style asks for —
                    // that is what clipped the settings header at 35dp against
                    // a 28dp line.
                    .onGloballyPositioned {
                        headerBottomPx = it.boundsInRoot().bottom
                    }
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(PetSpacing.s10),
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = PetSpacing.screenMargin, vertical = PetSpacing.s12
                )
            ) {
                StatusChip(
                    icon = if (bluetoothOn) Icons.Default.BluetoothConnected
                           else Icons.Default.BluetoothDisabled,
                    label = PetStatusText.connectionLabel(
                        petConnection, petPaired != null, bluetoothOn, switchedOff
                    ),
                    good = petConnection == PetBleRepository.State.READY &&
                           bluetoothOn && !switchedOff,
                    onClick = onOpenPetSettings,
                    modifier = Modifier.weight(1f)
                )
                StatusChip(
                    icon = Icons.Default.Psychology,
                    label = when (readiness) {
                        is PetReadiness.Ready -> "Models loaded"
                        is PetReadiness.Loading -> "Loading…"
                        is PetReadiness.Failed -> "Model failed"
                        is PetReadiness.Missing -> "Models missing"
                    },
                    good = readiness is PetReadiness.Ready,
                    onClick = onOpenModelSettings,
                    modifier = Modifier.weight(1f)
                )
            }

            Box(
                modifier = Modifier.fillMaxWidth().padding(top = PetSpacing.s4),
                contentAlignment = Alignment.Center
            ) {
                PetPanel(
                    condition = shown,
                    listening = isRecording,
                    thinking = isGenerating,
                    // The pet's own set, or the one the firmware falls back to.
                    set = PetFaceSets.byId(faceSetId) ?: PetFaceSets.default,
                )
            }

            CareCard(
                condition = shown,
                readiness = readiness,
                switchedOff = switchedOff,
                onTurnBackOn = { chatViewModel.petReconnect() },
                modifier = Modifier.padding(
                    start = PetSpacing.screenMargin,
                    end = PetSpacing.screenMargin,
                    top = PetSpacing.s16,
                )
            )
        }
    }

    /*
     * THE INPUT, pinned to the window and OUTSIDE the sheet.
     *
     * The strongest reading of "fixed footer": it cannot move in any sheet
     * state, because it is not in the sheet. Drawn after the scaffold so it
     * sits over the sheet's lower edge in the sheet's own colour, with the
     * sheet's content padded clear of it.
     *
     * **NO imePadding HERE, AND THAT IS NOT AN OVERSIGHT.** The keyboard is
     * handled once, on the root Box, so this footer rides up with the sheet
     * rather than moving independently of it. Padding it here as well was the
     * bug: see the note on the root.
     */
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
        // NOT navigationBarsPadding() here — see the Column below.
    ) {
        /*
         * THE INSET GOES ON THE CONTENT, NOT THE CONTAINER.
         *
         * With navigationBarsPadding() on the Surface, the surface itself
         * stopped above the gesture bar and the bottom sheet showed through the
         * strip underneath — a transparent-looking band containing the system's
         * own drag handle. Padding the Column instead lets the surface paint all
         * the way to the bottom edge while the input still sits clear of the
         * gesture bar.
         *
         * That is the general rule, and it has now bitten twice on this one
         * screen: a background must extend THROUGH a system inset, and only the
         * touchable content should be pushed clear of it. The status-bar version
         * of the same mistake hid the settings button.
         */
        /*
         * MEASURED, because it is not a fixed height: the suggestion row comes
         * and goes. onSizeChanged sits OUTSIDE navigationBarsPadding so the
         * reported height includes the inset — what the sheet needs to know is
         * how much of itself this footer covers, and the padding is part of
         * that. The keyboard is not part of it: the window resizes instead, so
         * this height is the same whether the keyboard is up or down.
         */
        Column(
            Modifier
                .onSizeChanged { footerPx = it.height.toFloat() }
                .navigationBarsPadding()
        ) {
            MessageInputBar(
                isGenerating = isGenerating,
                isRecording = isRecording,
                isTranscribing = isTranscribing,
                onSend = { chatViewModel.sendMessage(it) },
                /*
                 * THE MICROPHONE PERMISSION IS ASKED FOR HERE, AT THE TAP.
                 *
                 * It used to be one of four fired at the top of `onCreate`
                 * before the app had drawn anything — the opening barrage
                 * DESIGN.md §5.2 forbids. It is not one of §5.2's five steps
                 * either, and rightly: this is the phone-mic *fallback* for a
                 * quiet room or a flat pet, and the pet's own microphone is the
                 * primary path that needs nothing from this device. So it is
                 * asked for by the only thing that needs it, at the moment it
                 * needs it.
                 *
                 * Recording starts in the launcher's callback rather than
                 * beside it: `launch` returns immediately, and starting there
                 * too would record with no permission on the first tap and
                 * twice on every tap after.
                 */
                onStartRecording = {
                    if (hasAudioPermission(context)) chatViewModel.startRecording()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                onStopRecording = { chatViewModel.stopRecording() }
            )
        }
    }
    }
}

/**
 * What goes inside the sheet.
 *
 * **This is what replaced the Talk tab.** The conversation is how the pet talks,
 * not the product (DESIGN.md §1), so it belongs underneath how the pet is rather
 * than beside it — and a sheet says "underneath" in a way a tab of equal weight
 * could not.
 *
 * THE SHEET HOLDS THE TRANSCRIPT AND NOTHING ELSE. The input is a fixed footer
 * outside it (see the Surface in PetHomeScreen), because a bottom sheet reveals
 * its content from the top and anything else in here would compete with the
 * conversation for that band — as well as moving with the sheet, when the whole
 * point of it is to be reachable in every sheet position.
 *
 * **NOTHING IN HERE CHANGES SIZE.** It used to hold two differently
 * sized copies of the transcript and cross-fade between them when the sheet
 * settled; the transcript is now one list at one height, *moved* by a
 * `graphicsLayer` translation. See [ChatSheetGeometry] for what the translation
 * is and why — it is where the sheet's last three faults were, and it is tested.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatSheetContent(
    chatViewModel: PetChatViewModel,
    sheetState: SheetState,
    rootBottomPx: Float,
    footerPx: Float,
    handlePx: Float,
    /**
     * The sheet's full travel — measured to the header's bottom edge, so the
     * sheet opens to just under it. Constant for a given window, which is what
     * §6.4a rule 2 asks for; it is not a constant in the source any more.
     */
    contentHeight: Dp,
) {
    val messages by chatViewModel.messages.collectAsState()
    val streaming by chatViewModel.currentStreamingResponse.collectAsState()
    val isGenerating by chatViewModel.isGenerating.collectAsState()

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val footerDp = with(density) { footerPx.toDp() }

    val expanded = sheetState.currentValue == SheetValue.Expanded

    /*
     * THE NEWEST MESSAGE'S HEIGHT, HELD ON TO — and it is a plain array rather
     * than state on purpose, because nothing should recompose or redraw when it
     * changes. It is a cache read in the same draw pass that fills it.
     *
     * The geometry needs the newest message's measured height to decide whether
     * to rest it on the footer or show it from its top. Once the band scrolls,
     * that item can leave the list's composed range entirely and the measurement
     * goes away — and falling back to zero would have moved a long reply by
     * ~200px mid-scroll, which is the jumping that was reported an hour ago
     * arriving by a different route. Remembering the last real value keeps the
     * offset fixed while the reader is away from the bottom, which is the whole
     * point: nothing under a moving finger should also be moving by itself.
     */
    val lastNewest = remember { FloatArray(1) }

    /*
     * WHAT THE CONTENT BOX ACTUALLY GOT, which is not always what it asked for.
     *
     * `Modifier.height()` is a REQUEST. It is coerced into the constraints the
     * parent hands down, and a bottom sheet's content is bounded by the space
     * the sheet has — so when the keyboard is up, a sheet that wants 1763 px
     * gets ~1192 and the request is silently reduced.
     *
     * That is the whole of the keyboard bug. `ChatSheetGeometry` lifts the list
     * by "the part of the content hanging below the window", computed from the
     * REQUESTED height, so it lifted by 571 px of overhang that had already been
     * clamped away — and the newest message floated ~450 px above the footer it
     * was supposed to rest on. Measured: the list's visible container was
     * 109..730 while the arithmetic assumed 109..1674.
     *
     * It was invisible at 560 dp because the old sheet only overshot by 68 px.
     * Making the sheet taller did not introduce this; it made an existing error
     * big enough to see.
     */
    var contentActualPx by remember { mutableFloatStateOf(0f) }
    val contentActualDp = with(density) { contentActualPx.toDp() }

    /*
     * A CLOSED SHEET GOES BACK TO THE NEWEST MESSAGE.
     *
     * Without this, reading history and then closing leaves the peek showing
     * whatever was under your thumb — and the peek's entire job is the latest
     * exchange.
     *
     * **CURRENT VALUE, NOT TARGET, and that was a real mistake rather than a
     * preference.** Doing it on the target fires the moment the sheet starts
     * closing, which is while it is still fully open — so a transcript scrolled
     * back through history visibly leapt to the bottom under an open sheet. On
     * the settled value the same reposition happens once only the ~100dp band is
     * showing. It cannot be removed altogether: something has to put the peek
     * back on the message it exists to show.
     */
    LaunchedEffect(sheetState, listState) {
        snapshotFlow { sheetState.currentValue }
            .collect { if (it != SheetValue.Expanded) listState.scrollToItem(0) }
    }

    /*
     * AND SO DOES A NEW MESSAGE — reported as "responses don't get displayed in
     * the peek, I only see them once I expand the sheet".
     *
     * A `reverseLayout` list anchors itself to the item it was already showing,
     * and the transcript grows at the end that item sits at — so an arriving
     * reply is laid out BEYOND the anchor, off the bottom of the viewport, and
     * the peek goes on showing the previous exchange. The list is not scrollable
     * while collapsed, so nothing could bring it back either.
     *
     * **The guard is what stops this being a yank.** While the sheet is open and
     * the reader has scrolled back into history, a new message must not drag
     * them to the bottom mid-sentence; `firstVisibleItemIndex == 0` means they
     * are already on the newest and following along, which is the case where
     * moving is what they want. Collapsed, there is no reading position to
     * protect — the peek has one job.
     */
    LaunchedEffect(messages.size, streaming.isEmpty(), isGenerating) {
        if (!expanded || listState.firstVisibleItemIndex == 0) listState.scrollToItem(0)
    }

    /*
     * The clip is load-bearing. The transcript is translated UP out of this box
     * while the sheet is closed, and without clipToBounds the older messages
     * would be drawn over the drag handle above it.
     */
    Box(
        Modifier
            .fillMaxWidth()
            .height(contentHeight)
            // INSIDE the height, so this reports what the constraints allowed
            // rather than what was asked for. See contentActualPx.
            .onSizeChanged { contentActualPx = it.height.toFloat() }
            .clipToBounds()
    ) {
        PetChatSection(
            messages = messages,
            currentStreamingResponse = streaming,
            isGenerating = isGenerating,
            state = listState,
            /*
             * THE BAND SCROLLS, INCLUDING WHILE THE SHEET IS CLOSED.
             *
             * It used to be false whenever the sheet was collapsed, on the
             * argument that scrolling a short viewport moves it off the newest
             * message — the one thing the peek is for. That argument is now
             * paid for elsewhere: an arriving reply pulls a collapsed band back
             * to the newest message, so scrolling away is never a state you get
             * stuck in.
             *
             * What is left is a plain gain. The sheet still wins the upward
             * drag — Material's nested scroll gives it the gesture first while
             * it is not expanded, so dragging up opens the sheet exactly as
             * before — and dragging DOWN, which previously did nothing at all
             * on a closed sheet, now reaches back through the conversation
             * without opening it.
             */
            userScrollEnabled = true,
            modifier = Modifier
                .fillMaxWidth()
                // The list stops short of the footer that is drawn over the
                // sheet, so its bottom edge — which is the newest message — is
                // the last thing visible above the input rather than under it.
                // From the MEASURED box, so the list genuinely ends `footer`
                // above the content's real bottom — which is the invariant the
                // whole translation is derived from. Sizing it from the request
                // let the list fill a clamped box completely, putting its newest
                // message behind the footer before the lift was even applied.
                .height((contentActualDp - footerDp).coerceAtLeast(0.dp))
                .padding(horizontal = PetSpacing.s16)
                /*
                 * READ IN THE DRAW PHASE, ON PURPOSE. Every value below is
                 * snapshot state, and reading it inside the graphicsLayer block
                 * rather than in composition means the sheet's movement
                 * re-runs *this lambda* and nothing else — no recomposition, no
                 * remeasure, and therefore none of the stutter that DESIGN.md
                 * §6.4a rule 3 was written about. That is what makes it safe to
                 * follow the drag continuously instead of waiting to settle.
                 */
                .graphicsLayer {
                    /*
                     * The sheet's own live offset, NOT a position captured from
                     * a layout callback. onGloballyPositioned fires after its
                     * node is placed, so a child reading it would trail the
                     * sheet by a frame all the way down a drag. This is the same
                     * value the sheet places itself with, so the transcript
                     * cannot fall behind it.
                     */
                    val sheetTop = try {
                        sheetState.requireOffset()
                    } catch (_: IllegalStateException) {
                        // Only before the first layout. Collapsed is the state
                        // the sheet starts in, so assume it for that one frame.
                        rootBottomPx - PetSize.sheetPeek.toPx()
                    }
                    listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == 0 }?.size?.toFloat()
                        ?.let { lastNewest[0] = it }
                    val newest = lastNewest[0]
                    translationY = ChatSheetGeometry.translation(
                        rootBottom = rootBottomPx,
                        contentTop = sheetTop + handlePx,
                        contentHeight = contentActualPx,
                        footer = footerPx,
                        newest = newest,
                    )
                }
        )
    }
}

/**
 * Whether the phone's own microphone may be used.
 *
 * The pet's microphone is the primary path and is unaffected by this — a denied
 * permission here costs the typing-and-talking fallback, not the conversation.
 */
private fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
