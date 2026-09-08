package com.digitalpet.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.digitalpet.text.PetText
import com.digitalpet.util.DiagnosticLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A pet device seen while scanning. */
data class PetDevice(val name: String, val address: String, val rssi: Int)

/**
 * Owns the link to the DigitalPet hardware.
 *
 * Pairing is **user-driven**: nothing connects on its own until the user has
 * picked a device once. After that the chosen address is remembered and we
 * reconnect to *that* device automatically — explicit choice first, convenience
 * afterwards.
 *
 * Threading: Android's GATT stack silently drops a request issued while another
 * is in flight, so every operation goes through [enqueue] and the next starts
 * only once the previous callback lands.
 *
 * Permissions are checked in [hasPermissions]; calls are annotated
 * [SuppressLint] rather than re-checked at every call site.
 */
@Singleton
@SuppressLint("MissingPermission")
class PetBleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: DiagnosticLogger
) {

    enum class State { IDLE, SCANNING, CONNECTING, READY }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Devices seen during the current scan. */
    private val _discovered = MutableStateFlow<List<PetDevice>>(emptyList())
    val discovered: StateFlow<List<PetDevice>> = _discovered.asStateFlow()

    /** Address the user chose, persisted. Null means "never paired". */
    private val _pairedAddress = MutableStateFlow(loadPaired())
    val pairedAddress: StateFlow<String?> = _pairedAddress.asStateFlow()

    /** Firmware version + capabilities, once connected. */
    private val _info = MutableStateFlow<PetProtocol.Info?>(null)
    val info: StateFlow<PetProtocol.Info?> = _info.asStateFlow()

    /** Events pushed up by the pet. */
    private val _events = MutableSharedFlow<PetProtocol.Event>(extraBufferCapacity = 16)
    val events: SharedFlow<PetProtocol.Event> = _events.asSharedFlow()

    /**
     * Opus frames arriving from the pet's mic, one per notification.
     *
     * Buffered generously: frames land at 50/s during an utterance and dropping
     * one here would punch a hole in the audio that no amount of concealment
     * downstream can fill, since unlike a lost notification we would not even
     * know it happened.
     */
    private val _audioFrames = MutableSharedFlow<PetProtocol.AudioFrame>(extraBufferCapacity = 256)
    val audioFrames: SharedFlow<PetProtocol.AudioFrame> = _audioFrames.asSharedFlow()

    /**
     * How the pet says it is — v4's `Condition`, pet to phone.
     *
     * A StateFlow, not a SharedFlow, and deliberately: this is *state*, not an
     * event. A late subscriber wants the pet's current condition, not to wait
     * for it to next change — and the pet only notifies on change, so a
     * replay-less flow would leave a newly-started reader blind for up to half
     * an hour.
     *
     * Null until the first read or notification, meaning "not known yet" rather
     * than "the pet is fine".
     */
    private val _condition = MutableStateFlow<PetProtocol.Condition?>(null)
    val condition: StateFlow<PetProtocol.Condition?> = _condition.asStateFlow()

    /**
     * v9. The face set the pet says it is wearing, or null if it has not said.
     *
     * NULL IS NOT "classic". The surfaces fall back to the default set for
     * drawing, but this stays null so the settings screen can show what is
     * actually known rather than asserting a choice the user never made — and so
     * a pet whose firmware predates v9 does not appear to have picked one.
     */
    /**
     * v10. The quiet-hours window the PET holds, as minutes past midnight, or
     * null until it has said. Null is not "21:00-09:00": the surfaces show the
     * default as a hint but must not claim it is what the pet is using.
     */
    private val _quietHours = MutableStateFlow<Pair<Int, Int>?>(null)
    val quietHours: StateFlow<Pair<Int, Int>?> = _quietHours.asStateFlow()

    private val _faceSetId = MutableStateFlow<String?>(null)
    val faceSetId: StateFlow<String?> = _faceSetId.asStateFlow()

    /**
     * Whether this pet can actually be told to change its face.
     *
     * FALSE FOR TWO VERY DIFFERENT REASONS, and the UI has to be able to tell
     * them apart: the pet's firmware predates v9, or — far more likely and much
     * more confusing — **Android is serving a cached GATT database from before
     * the characteristic existed.** Android caches service discovery per bonded
     * device, so adding a characteristic to the firmware is invisible to a phone
     * that has already paired, and it looks exactly like the feature not
     * working. [faceSetStaleCache] separates the two.
     */
    private val _faceSetSupported = MutableStateFlow(false)
    val faceSetSupported: StateFlow<Boolean> = _faceSetSupported.asStateFlow()

    /**
     * The pet SAYS it speaks v9 and the characteristic is still missing.
     *
     * That combination can only be the cached-database case, and it is worth
     * naming rather than reporting as "unsupported": the fix is re-pairing, and
     * a user told "your pet does not support this" would never find it.
     */
    val faceSetStaleCache: StateFlow<Boolean> = MutableStateFlow(false)

    private var statusChar: BluetoothGattCharacteristic? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var screenChar: BluetoothGattCharacteristic? = null
    private var clockChar: BluetoothGattCharacteristic? = null
    private var faceSetChar: BluetoothGattCharacteristic? = null
    private var quietChar: BluetoothGattCharacteristic? = null

    /** Last level sent, so [sendScreenTime] logs transitions and not every poll. */
    private var lastScreenOveruse: Boolean? = null

    /** Last error worth showing the user (e.g. permissions, Bluetooth off). */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    /**
     * Whether the radio is on, for anything that has to explain itself.
     *
     * **Read live rather than cached.** The user leaves the app to switch
     * Bluetooth on and comes straight back, so a remembered answer would be
     * wrong at exactly the moment it is looked at — the same reasoning as the
     * notification-access check on the Pet screen.
     *
     * `ready()` has consulted the adapter since the beginning and put the answer
     * in `status`, where nothing rendered it. DESIGN.md §5.3 wants "Bluetooth is
     * off" said plainly on the Pet screen, and that needs the fact rather than
     * an error string that may have been overwritten by a later one.
     */
    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    private var gatt: BluetoothGatt? = null
    private var textChar: BluetoothGattCharacteristic? = null
    private var moodChar: BluetoothGattCharacteristic? = null
    private var audioCtlChar: BluetoothGattCharacteristic? = null
    private var speakCtlChar: BluetoothGattCharacteristic? = null
    private var speakDatChar: BluetoothGattCharacteristic? = null

    private var wantConnection = false
    private var retryDelayMs = INITIAL_RETRY_MS

    /**
     * The user switched the pet off, and it must stay off.
     *
     * **PERSISTED, and that is the change.** "Off" used to live only in
     * `wantConnection`, which is process state — so every automatic reconnect
     * path undid it: `MainActivity.onStart` calls `connectRemembered()` on every
     * foreground, the boot receiver calls it after a restart, and the retry loop
     * would have too. Swiping the notification away therefore disconnected the
     * pet and then immediately went looking for it again, with the notification
     * reappearing seconds later — an off switch that visibly did not work.
     *
     * DESIGN.md §5.4 already asked for this: the app must *show* that the pet is
     * off and offer to turn it on, rather than reconnecting on launch as a side
     * effect that looks like magic. This is the first half of that.
     *
     * **Only an explicit user action clears it** — [reconnectByUser] or pairing a
     * device. Nothing automatic may, which is the entire point; if a new
     * reconnect path is added later it must go through one of those two or the
     * off switch quietly stops working again.
     */
    private var stoppedByUser: Boolean
        get() = prefs.getBoolean(KEY_STOPPED, false)
        set(value) { prefs.edit().putBoolean(KEY_STOPPED, value).apply() }

    /**
     * Seeded from the persisted flag, and that is the half that was missing.
     *
     * [stoppedByUser] survives the process and [connectRemembered] honours it,
     * so the link correctly stayed down after a restart — but this started
     * `false` every time, so the screen said **"Not connected"** rather than
     * "Your pet is off". The pet then never came back and never said why, which
     * is the exact failure DESIGN.md §5.4 introduced the persistence to prevent:
     * an off switch that survives reopening the app is only an improvement if
     * the app admits the pet is off.
     *
     * It hid for two days because the two halves disagree only across a process
     * boundary — switch the pet off and the flag is right, because the same
     * process set it. Everything looks correct until the app is killed, which is
     * the one case the persistence exists for. And the early return that keeps
     * the pet off logs a line nobody reads: the silent-skip shape again.
     */
    private val _switchedOff = MutableStateFlow(stoppedByUser)

    /** True while the user has switched the pet off. For the Pet screen (§5.3). */
    val switchedOff: StateFlow<Boolean> = _switchedOff.asStateFlow()

    /** Negotiated ATT MTU. One write carries at most MTU-3 bytes, so this caps
     *  how much text we can push without a (much fiddlier) long write. */
    private var mtu = DEFAULT_MTU

    // --- serialised GATT queue -------------------------------------------------

    private val pending = ArrayDeque<() -> Unit>()
    private var inFlight = false

    @Synchronized
    private fun enqueue(op: () -> Unit) {
        pending.addLast(op)
        if (!inFlight) dispatchNext()
    }

    @Synchronized
    private fun dispatchNext() {
        val next = pending.pollFirst()
        if (next == null) {
            inFlight = false
            return
        }
        inFlight = true
        runCatching { next() }.onFailure {
            logger.log(TAG, "GATT op failed: ${it.message}")
            dispatchNext()
        }
    }

    @Synchronized
    private fun opComplete() {
        inFlight = false
        dispatchNext()
    }

    // --- public API ------------------------------------------------------------

    /** Scan for nearby pets. Results land in [discovered]; nothing auto-connects. */
    fun startScan() {
        if (!ready()) return
        if (_state.value == State.SCANNING) return

        _discovered.value = emptyList()
        _state.value = State.SCANNING
        _status.value = null

        // Two filters, OR'd. The firmware cannot fit the 128-bit service UUID in
        // the 31-byte advertisement alongside flags + name, so it lives in the
        // scan response — and matching a scan-response UUID is unreliable across
        // Android versions. The device-name filter is the dependable path.
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(PetProtocol.SERVICE)).build(),
            ScanFilter.Builder().setDeviceName(PetProtocol.DEVICE_NAME).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        logger.log(TAG, "scan started")
        runCatching {
            adapter?.bluetoothLeScanner?.startScan(filters, settings, scanCallback)
        }.onFailure {
            logger.log(TAG, "startScan failed: ${it.message}")
            _status.value = "Scan failed: ${it.message}"
            _state.value = State.IDLE
        }

        // Scanning is power-hungry; stop on our own after a sensible window.
        scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (_state.value == State.SCANNING) stopScan()
        }
    }

    fun stopScan() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_state.value == State.SCANNING) _state.value = State.IDLE
        logger.log(TAG, "scan stopped (${_discovered.value.size} found)")
    }

    /** Connect to a device the user picked, and remember it for next time. */
    fun connect(address: String) {
        if (!ready()) return
        stopScan()
        savePaired(address)
        _pairedAddress.value = address
        // Choosing a pet is as explicit as it gets; it clears the off switch.
        stoppedByUser = false
        _switchedOff.value = false
        wantConnection = true
        retryDelayMs = INITIAL_RETRY_MS
        openGatt(address)
    }

    /** Reconnect to the remembered device, if there is one. Safe to call repeatedly. */
    fun connectRemembered() {
        /*
         * THE AUTOMATIC PATH, and it must respect the off switch.
         *
         * Called from MainActivity.onStart on every foreground and from the boot
         * receiver, so without this check "off" lasted only until the user next
         * looked at the app — or swiped the notification while the app was open,
         * which reconnected within seconds and put the notification straight
         * back. Use reconnectByUser() for anything a person actually asked for.
         */
        if (stoppedByUser) {
            logger.log(TAG, "not reconnecting: switched off by the user")
            return
        }
        val address = _pairedAddress.value ?: return
        if (gatt != null || _state.value == State.CONNECTING) return
        if (!ready()) return
        wantConnection = true
        openGatt(address)
    }

    /**
     * Switch the pet off and mean it — the notification's Stop action and its
     * delete intent both land here.
     *
     * Distinct from [disconnect] because a dropped link and a deliberate stop
     * are different facts. Only this one persists.
     */
    fun stopByUser() {
        stoppedByUser = true
        _switchedOff.value = true
        logger.log(TAG, "switched off by the user - staying off until asked")
        disconnect()
    }

    /**
     * The user asked for the pet back. Clears the off switch, then connects.
     *
     * The one door out of "off", so it must not be reachable by accident: it is
     * wired to Reconnect in Settings, and DESIGN.md §5.3 wants it on the Pet
     * screen too once the visual pass lands.
     */
    fun reconnectByUser() {
        stoppedByUser = false
        _switchedOff.value = false
        connectRemembered()
    }

    /** Drop the link but keep the pairing. */
    fun disconnect() {
        wantConnection = false
        closeGatt()
    }

    /** Drop the link and forget the device entirely. */
    fun forget() {
        wantConnection = false
        closeGatt()
        // No pet means "switched off" has nothing to describe, and leaving it
        // set would make the next pairing look off from the moment it succeeded.
        stoppedByUser = false
        _switchedOff.value = false
        prefs.edit().remove(KEY_ADDRESS).apply()
        _pairedAddress.value = null
        _status.value = null
    }

    /** Show [text] on the pet. Truncated to the firmware's limit. */
    fun sendText(text: String) {
        val c = textChar ?: run {
            logger.log(TAG, "sendText ignored — not connected")
            return
        }
        // One ATT write carries MTU-3 bytes; never ask for more than the link
        // actually negotiated, or the write is rejected.
        val limit = minOf(PetProtocol.TEXT_MAX, mtu - ATT_WRITE_OVERHEAD)
        val bytes = PetText.fitForDisplay(text, limit)
        logger.log(TAG, "sendText(${bytes.size}B of ${text.toByteArray().size}B, limit=$limit)")
        enqueue { write(c, bytes) }
    }

    /** Set the pet's expression. */
    /**
     * Tell the pet what the PHONE is doing — v4's `Status`.
     *
     * Distinct from [sendMood], which is the pet's *expression*. Until v4 both
     * went through the Mood byte, so "the model is thinking" and "the reply
     * contained a sleepy emoji" were the same message.
     */
    fun sendStatus(status: PetProtocol.Status) {
        val c = statusChar ?: return
        enqueue { write(c, byteArrayOf(status.value), noResponse = true) }
    }

    /**
     * Report the user's screen time to the pet — v5, DESIGN.md §1 decision 3.
     *
     * A **level**, sent on every usage poll rather than once when a session
     * crosses the threshold. The pet stops believing a report it has not heard
     * for [PetProtocol.SCREEN_STALE_SEC], so re-asserting is what keeps a long
     * scroll counted; it is also what stops a single dropped write leaving the
     * pet ill for good.
     *
     * With response, unlike the audio paths: this arrives once a minute rather
     * than fifty times a second, and a silently dropped report is exactly the
     * failure the level design is guarding against.
     *
     * No-ops when nothing is connected or the pet predates v5.
     */
    /**
     * Ask the pet to start a new life — v6, DESIGN.md §1 phase 6.
     *
     * **Destructive and irreversible.** The caller must have confirmed with the
     * user first; this does not ask. The pet independently refuses the write
     * unless it is actually dead, and that is not redundancy for its own sake —
     * the pet owns its own life, so "are you sure" is not something it should
     * have to take another device's word for.
     *
     * No-ops when nothing is connected or the pet predates v6, in which case the
     * BOOT-hold on the device is the way through.
     */
    /**
     * Ask the pet to wear [id].
     *
     * ASK, not set. The pet owns which face it wears (DESIGN.md §1 decision 1),
     * so nothing local is updated here — [faceSetId] changes only when the pet
     * notifies what it actually did. A pet that does not have this set ignores
     * the write and notifies its real one back, and the UI follows that rather
     * than showing a choice that did not take.
     */
    fun selectFaceSet(id: String) {
        val c = faceSetChar ?: run {
            logger.log(TAG, "selectFaceSet ignored - pet predates v9")
            return
        }
        logger.log(TAG, "selectFaceSet - asking for '$id'")
        enqueue { write(c, id.toByteArray(Charsets.UTF_8)) }
    }

    /**
     * Ask the pet to use this quiet-hours window, then read back what it kept.
     *
     * The re-read is not belt-and-braces: the pet refuses a window that would
     * leave it fewer than six waking hours, so without reading back the UI
     * would show a setting the pet is not using.
     */
    fun setQuietHours(fromMinutes: Int, toMinutes: Int) {
        val c = quietChar ?: run {
            logger.log(TAG, "setQuietHours ignored - pet predates v10")
            return
        }
        val f = fromMinutes.coerceIn(0, 1439)
        val t2 = toMinutes.coerceIn(0, 1439)
        logger.log(TAG, "setQuietHours - asking for $f..$t2 (minutes)")
        enqueue {
            write(c, byteArrayOf(
                (f and 0xFF).toByte(), ((f shr 8) and 0xFF).toByte(),
                (t2 and 0xFF).toByte(), ((t2 shr 8) and 0xFF).toByte(),
            ))
        }
        enqueue { gatt?.readCharacteristic(c) }
    }

    fun resetPet() {
        val c = commandChar ?: run {
            logger.log(TAG, "resetPet ignored - no command characteristic")
            return
        }
        logger.log(TAG, "resetPet - sending RESET")
        enqueue { write(c, byteArrayOf(PetProtocol.Command.RESET.value)) }
    }

    fun sendScreenTime(overusing: Boolean) {
        // Piggy-backed on the poll the service already runs, rather than a timer
        // of its own: one fewer thing with a lifetime to get wrong.
        sendClock()
        val c = screenChar ?: return
        val level = if (overusing) PetProtocol.ScreenTime.OVERUSE else PetProtocol.ScreenTime.OK
        // Logged only when it changes: this fires every minute, and a log line
        // per poll would bury the transitions that actually matter.
        if (overusing != lastScreenOveruse) {
            lastScreenOveruse = overusing
            logger.log(TAG, "screen time -> $level")
        }
        enqueue { write(c, byteArrayOf(level.value)) }
    }

    /**
     * Tell the pet what time it is locally — v8, for quiet hours.
     *
     * **Seconds since local midnight, not an epoch.** The pet needs to know when
     * it is 9pm and has no use for the date; sending one would invite setting its
     * RTC from it, which shifts every persisted timestamp at once and ages a
     * saved pet by months. See PetProtocol.CHAR_CLOCK.
     *
     * Sent on connect and on every usage poll. That is far more often than it
     * changes, and deliberately so: it costs four bytes on a link that already
     * carries a screen-time write every minute, and it means a DST change or RTC
     * drift corrects itself without anything having to detect it. The pet only
     * logs the value when it actually moves.
     */
    fun sendClock() {
        val c = clockChar ?: return
        val now = java.util.Calendar.getInstance()
        val secondsOfDay = now.get(java.util.Calendar.HOUR_OF_DAY) * 3600 +
            now.get(java.util.Calendar.MINUTE) * 60 +
            now.get(java.util.Calendar.SECOND)
        val v = secondsOfDay.toLong()
        val payload = byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte(),
        )
        enqueue { write(c, payload) }
    }

    fun sendMood(mood: PetProtocol.Mood) {
        val c = moodChar ?: return
        logger.log(TAG, "sendMood($mood)")
        enqueue { write(c, byteArrayOf(mood.value)) }
    }

    /**
     * Start or stop the pet's microphone.
     *
     * While listening the pet streams Opus frames on [audioFrames], bracketed
     * by [PetProtocol.Event.AudioState]. The firmware also stops on its own
     * after 30 s and on disconnect, so a missed stop cannot leave it capturing
     * indefinitely.
     */
    fun setPetListening(on: Boolean) {
        val c = audioCtlChar ?: run {
            logger.log(TAG, "setPetListening ignored - pet has no mic characteristic")
            return
        }
        logger.log(TAG, "setPetListening($on)")
        enqueue {
            write(c, byteArrayOf(if (on) PetProtocol.AUDIO_CTL_START else PetProtocol.AUDIO_CTL_STOP))
        }
    }

    /** True when the pet can play audio — a v1/v2 pet cannot. */
    fun petHasSpeaker(): Boolean = speakDatChar != null

    /** Open, close or abandon an utterance on the pet's speaker. */
    fun sendSpeakCtl(ctl: Byte) {
        val c = speakCtlChar ?: return
        enqueue { write(c, byteArrayOf(ctl)) }
    }

    /**
     * Send one Opus frame to the pet's speaker.
     *
     * Write-without-response: at 50 frames a second an acknowledged write per
     * frame would halve the achievable rate for no benefit, since a lost frame
     * is a click rather than a correctness problem.
     */
    fun sendSpeakFrame(seq: Int, packet: ByteArray) {
        val c = speakDatChar ?: return
        val payload = ByteArray(PetProtocol.AUDIO_HDR_LEN + packet.size)
        payload[0] = (seq and 0xFF).toByte()
        packet.copyInto(payload, PetProtocol.AUDIO_HDR_LEN)
        enqueue { write(c, payload, noResponse = true) }
    }

    // --- preconditions ---------------------------------------------------------

    private fun ready(): Boolean {
        if (!hasPermissions()) {
            _status.value = "Bluetooth permission not granted"
            return false
        }
        val a = adapter
        if (a == null || !a.isEnabled) {
            _status.value = "Bluetooth is off"
            return false
        }
        return true
    }

    /** SCAN + CONNECT are runtime permissions on API 31+, which is our minSdk. */
    fun hasPermissions(): Boolean =
        listOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT
        ).all {
            context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    // --- scanning --------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = runCatching { device.name }.getOrNull()
                ?: result.scanRecord?.deviceName
                ?: PetProtocol.DEVICE_NAME
            val found = PetDevice(name, device.address, result.rssi)
            _discovered.value = (_discovered.value.filterNot { it.address == found.address } + found)
                .sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            logger.log(TAG, "scan failed: $errorCode")
            _status.value = "Scan failed ($errorCode)"
            _state.value = State.IDLE
        }
    }

    // --- connection ------------------------------------------------------------

    private fun openGatt(address: String) {
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: run {
            _status.value = "Unknown device $address"
            return
        }
        _state.value = State.CONNECTING
        logger.log(TAG, "connecting to $address")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun closeGatt() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        textChar = null
        moodChar = null
        audioCtlChar = null
        speakCtlChar = null
        speakDatChar = null
        _info.value = null
        _faceSetSupported.value = false
        _quietHours.value = null
        (faceSetStaleCache as MutableStateFlow).value = false
        _state.value = State.IDLE
        synchronized(this) {
            pending.clear()
            inFlight = false
        }
    }

    private fun scheduleRetry() {
        if (!wantConnection) return
        val delayMs = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        scope.launch {
            delay(delayMs)
            if (wantConnection && gatt == null) connectRemembered()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                logger.log(TAG, "connected; requesting MTU")
                retryDelayMs = INITIAL_RETRY_MS
                // Ask for a bigger MTU before anything else: the default 23 caps
                // writes at 20 bytes, which would silently truncate pet text.
                g.requestMtu(REQUESTED_MTU)
            } else {
                // status 5 = insufficient authentication, i.e. a stale bond: the
                // phone holds pairing keys the pet no longer has.
                if (status == 5) {
                    _status.value = "Pairing out of sync — forget \"DigitalPet\" " +
                        "in Bluetooth settings, then reconnect"
                }
                logger.log(TAG, "disconnected (status=$status)")
                closeGatt()
                scheduleRetry()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            this@PetBleRepository.mtu = mtu
            refreshGattCache(g)
            logger.log(TAG, "MTU=$mtu; discovering services")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(PetProtocol.SERVICE)
            if (service == null) {
                logger.log(TAG, "pet service missing; disconnecting")
                _status.value = "Not a DigitalPet device"
                g.disconnect()
                return
            }
            textChar = service.getCharacteristic(PetProtocol.CHAR_TEXT)
            moodChar = service.getCharacteristic(PetProtocol.CHAR_MOOD)

            service.getCharacteristic(PetProtocol.CHAR_INFO)?.let { info ->
                enqueue { g.readCharacteristic(info) }
            }
            service.getCharacteristic(PetProtocol.CHAR_EVENT)?.let { evt ->
                enqueue { subscribe(g, evt) }
            }
            // Absent on a v1 pet; setPetListening() then no-ops rather than crashing.
            audioCtlChar = service.getCharacteristic(PetProtocol.CHAR_AUDIO_CTL)
            speakCtlChar = service.getCharacteristic(PetProtocol.CHAR_SPEAK_CTL)
            speakDatChar = service.getCharacteristic(PetProtocol.CHAR_SPEAK_DAT)
            service.getCharacteristic(PetProtocol.CHAR_AUDIO_DAT)?.let { dat ->
                enqueue { subscribe(g, dat) }
            }

            // v4. Absent on an older pet, in which case condition stays null and
            // sendStatus() no-ops — the chat path does not depend on either.
            statusChar = service.getCharacteristic(PetProtocol.CHAR_STATUS)
            val condChar = service.getCharacteristic(PetProtocol.CHAR_CONDITION)
            // v5. Absent on a v4 pet, in which case sendScreenTime() no-ops and
            // the pet simply never gets ill — screen time is the only input it
            // cannot observe for itself.
            screenChar = service.getCharacteristic(PetProtocol.CHAR_SCREEN)
            // v6. Absent on a pre-v6 pet, in which case resetPet() no-ops and a
            // dead pet can still be restarted from the BOOT button on the device.
            commandChar = service.getCharacteristic(PetProtocol.CHAR_COMMAND)
            // v8. Absent on a pre-v8 pet, in which case sendClock() no-ops and
            // the pet has no quiet hours — it decays and calls around the clock,
            // exactly as it did before, which is the right degradation.
            clockChar = service.getCharacteristic(PetProtocol.CHAR_CLOCK)
            // v9. Absent on a pre-v9 pet, in which case selectFaceSet() no-ops
            // and faceSetId stays null — the pet wears whatever it was built
            // with and the app draws the default, which is the same face.
            faceSetChar = service.getCharacteristic(PetProtocol.CHAR_FACESET)
            // v10. Absent on a pre-v10 pet, in which case its quiet hours are
            // whatever it was built with and cannot be changed from here.
            quietChar = service.getCharacteristic(PetProtocol.CHAR_QUIET)
            _faceSetSupported.value = faceSetChar != null
            (faceSetStaleCache as MutableStateFlow).value =
                faceSetChar == null && (_info.value?.version ?: 0) >= 9
            if (faceSetStaleCache.value) {
                // Named explicitly because the symptom — taps that do nothing —
                // is identical to a broken feature, and the cause is neither the
                // firmware nor the app.
                logger.log(TAG, "faceset MISSING although the pet reports v9: " +
                    "Android is serving a cached GATT database. Re-pair the pet.")
            }
            // Immediately, not on the next poll: a pet that reconnects at 21:05
            // should be quiet at once rather than a minute later.
            sendClock()
            // Says which of the two possible silences this is: a pet that does
            // not have v4, or a phone whose cached GATT database predates it.
            // Android caches service discovery per bonded device, so adding a
            // characteristic to the firmware is invisible until the cache is
            // dropped — which looks exactly like the feature not working.
            logger.log(
                TAG,
                "v4 chars: status=${statusChar != null} condition=${condChar != null}; " +
                    "v5 chars: screen=${screenChar != null}; " +
                    "v6 chars: command=${commandChar != null}; " +
                    "v8 chars: clock=${clockChar != null}; " +
                    "v9 chars: faceset=${faceSetChar != null}"
            )
            condChar?.let { cond ->
                enqueue { subscribe(g, cond) }
                // Read once as well as subscribing: the pet notifies only on
                // change, so without this the phone knows nothing about a stable
                // pet until it next gets hungry.
                enqueue { g.readCharacteristic(cond) }
            }
            // v9, and the read matters more here than the subscription: the set
            // only changes when somebody changes it, so without reading once the
            // app would not learn the pet's face until the user picked a new one.
            faceSetChar?.let { fs ->
                enqueue { subscribe(g, fs) }
                enqueue { g.readCharacteristic(fs) }
            }
            // Read only: it changes when somebody changes it, and the writer
            // re-reads. A subscription would be a notification nobody needs.
            quietChar?.let { q -> enqueue { g.readCharacteristic(q) } }
            _state.value = State.READY
            _status.value = null
            startPetService()
        }

        // API 33+ delivers the value as a parameter; older devices read it off
        // the characteristic. Both paths funnel into onRead().
        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) {
            onRead(c.uuid, value)
            opComplete()
        }

        @Deprecated("Pre-API-33 callback", ReplaceWith(""))
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onRead(c.uuid, c.value ?: ByteArray(0))
                opComplete()
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) = onNotify(c.uuid, value)

        @Deprecated("Pre-API-33 callback", ReplaceWith(""))
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onNotify(c.uuid, c.value ?: ByteArray(0))
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
        ) {
            // status 0 = GATT_SUCCESS. Anything else means the pet did not take
            // the value — worth surfacing, since the symptom is a screen that
            // simply never updates.
            if (status != 0) logger.log(TAG, "write to ${c.uuid} FAILED status=$status")
            else logger.log(TAG, "write ok -> ${c.uuid.toString().take(8)}")
            opComplete()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int
        ) {
            logger.log(TAG, "subscribed (status=$status)")
            opComplete()
        }
    }

    private fun onRead(uuid: UUID, value: ByteArray) {
        if (uuid == PetProtocol.CHAR_INFO) {
            val parsed = PetProtocol.parseInfo(value)
            _info.value = parsed
            logger.log(TAG, "pet protocol v${parsed?.version} caps=${parsed?.capabilities?.bits}")
            if (parsed != null && parsed.version != PetProtocol.VERSION) {
                _status.value = "Firmware protocol v${parsed.version}, app expects v${PetProtocol.VERSION}"
            }
        }
        // Condition arrives BOTH ways: notified when it changes, and read once on
        // connect so a stable pet is not invisible until it next gets hungry.
        // Both paths have to land here — routing only the notification is how the
        // initial read got silently dropped, parsed by nothing.
        if (uuid == PetProtocol.CHAR_CONDITION) {
            onNotify(uuid, value)
        }
        // Same both-ways handling as Condition, for the same reason: read once on
        // connect so the pet's actual face is known immediately, and notified
        // afterwards whenever it changes.
        if (uuid == PetProtocol.CHAR_FACESET) {
            onNotify(uuid, value)
        }
        if (uuid == PetProtocol.CHAR_QUIET && value.size >= 4) {
            val from = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
            val to = (value[2].toInt() and 0xFF) or ((value[3].toInt() and 0xFF) shl 8)
            _quietHours.value = from to to
            logger.log(TAG, "quiet hours: ${from / 60}:${"%02d".format(from % 60)}" +
                "-${to / 60}:${"%02d".format(to % 60)}")
        }
    }

    private fun onNotify(uuid: UUID, value: ByteArray) {
        when (uuid) {
            PetProtocol.CHAR_EVENT ->
                PetProtocol.parseEvent(value)?.let { _events.tryEmit(it) }

            PetProtocol.CHAR_AUDIO_DAT ->
                PetProtocol.parseAudioFrame(value)?.let {
                    if (!_audioFrames.tryEmit(it)) {
                        logger.log(TAG, "audio frame buffer full, dropped seq=${it.seq}")
                    }
                }

            PetProtocol.CHAR_FACESET -> {
                val id = value.toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                _faceSetId.value = id
                logger.log(TAG, "face set: ${id ?: "(none)"}")
            }

            PetProtocol.CHAR_CONDITION ->
                PetProtocol.Condition.parse(value)?.let {
                    _condition.value = it
                    logger.log(TAG, "condition: satiety ${it.satiety} happiness ${it.happiness}" +
                        (if (it.calling) " CALLING" else "") +
                        (if (it.sick) " SICK" else "") +
                        (if (it.dead) " DEAD" else "") +
                        " misses ${it.careMistakes}" +
                        // v6. "null" rather than a guess when the pet predates
                        // it — the same rule the parser follows.
                        " stage ${it.stage?.name?.lowercase() ?: "unknown"}" +
                        // v7. Logged on one line with everything else so a
                        // discharge can be plotted against what the pet was
                        // doing — which is the whole reason it is on the wire.
                        " batt ${it.batteryPercent?.let { p -> "$p%" } ?: "?"}" +
                        " ${it.batteryMillivolts ?: 0}mV")
                }
        }
    }

    /**
     * Drop Android's cached copy of the pet's attribute table before discovery.
     *
     * Android caches service discovery per bonded device and does not re-read it
     * on reconnect, so adding a characteristic to the firmware is invisible to a
     * phone that has paired before. The alternative is asking the user to forget
     * and re-pair after every protocol change, which is not a thing a pet should
     * require.
     *
     * NOT what caused v4's characteristics to go missing, despite being added
     * while chasing exactly that. They were genuinely absent from the firmware's
     * GATT table — `caps=799` was truthful, because capability bits come from a
     * #define, while the attribute table never got the entries. Kept because the
     * caching problem is real and the *next* protocol change would hit it.
     *
     * `refresh()` is a hidden API with no public equivalent — the supported
     * answer is a Service Changed indication, which needs the peripheral to
     * bond-cache and version its GATT table. Worth doing on the firmware side
     * eventually; until then this is the practical fix. Failure is non-fatal: it
     * just means the stale cache stands.
     */
    private fun refreshGattCache(g: BluetoothGatt) {
        runCatching {
            @Suppress("DiscouragedPrivateApi")
            val m = g.javaClass.getMethod("refresh")
            val ok = m.invoke(g) as? Boolean
            logger.log(TAG, "GATT cache refresh -> $ok")
        }.onFailure { logger.log(TAG, "GATT cache refresh unavailable: ${it.message}") }
    }

    private fun subscribe(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(PetProtocol.CCCD)
        if (cccd == null) {
            opComplete()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
        }
    }

    private fun write(
        c: BluetoothGattCharacteristic,
        bytes: ByteArray,
        /**
         * Audio frames go unacknowledged: at 50 a second, waiting for a round
         * trip per frame would halve the rate for no benefit. Everything else
         * keeps WRITE_TYPE_DEFAULT so onCharacteristicWrite reports a real
         * status instead of silently succeeding.
         */
        noResponse: Boolean = false
    ) {
        val g = gatt ?: run { opComplete(); return }
        val type = if (noResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeCharacteristic(c, bytes, type)
            if (rc != BluetoothGatt.GATT_SUCCESS) {
                logger.log(TAG, "writeCharacteristic rejected rc=$rc")
                opComplete()
            }
        } else {
            @Suppress("DEPRECATION")
            run {
                c.value = bytes
                c.writeType = type
                if (!g.writeCharacteristic(c)) {
                    logger.log(TAG, "writeCharacteristic rejected")
                    opComplete()
                }
            }
        }
    }

    /**
     * Fit [text] into [maxBytes] for the pet's screen.
     *
     * Prefers cutting at a **sentence** boundary, so an over-long reply ends on
     * a complete thought instead of mid-word — a hard byte cut reads as a bug,
     * whereas a clipped sentence with an ellipsis reads as intentional. Falls
     * back to a character-boundary cut when even the first sentence is too long.
     */

    private fun loadPaired(): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ADDRESS, null)

    /**
     * Bring the foreground service up because the pet is now connected.
     *
     * The service exists to serve a connected pet, so the link owns its lifetime
     * rather than the app doing: opening or closing a screen should not decide
     * whether the pet works, and the service stopping is what disconnects the
     * pet in the other direction.
     *
     * Starting a foreground service from the background is not allowed on
     * Android 12+, which is survivable here because the path back on is the user
     * tapping Connect in the Pet panel, with the app in front. Logged rather than
     * thrown if that ever stops being true.
     */
    private fun startPetService() {
        runCatching {
            context.startService(
                android.content.Intent(context, com.digitalpet.service.PetForegroundService::class.java)
            )
        }.onFailure { logger.log(TAG, "could not start pet service: ${it.message}") }
    }

    private fun savePaired(address: String) {
        prefs.edit().putString(KEY_ADDRESS, address).apply()
    }

    private companion object {
        const val TAG = "PetBleRepository"
        const val PREFS = "digital_pet_prefs"
        const val KEY_ADDRESS = "pet_ble_address"
        const val KEY_STOPPED = "pet_stopped_by_user"
        const val REQUESTED_MTU = 517      // ask high; the peer negotiates down
        const val DEFAULT_MTU = 23         // BLE default until negotiated
        const val ATT_WRITE_OVERHEAD = 3   // ATT opcode + handle
        /** Below this, a "sentence" is too short to be worth keeping alone. */
        const val INITIAL_RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 30_000L
        const val SCAN_TIMEOUT_MS = 15_000L
    }
}
