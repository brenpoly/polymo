package com.digitalpet.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import com.digitalpet.util.DiagnosticLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talk to the pet over USB, well enough to say what it is.
 *
 * **SPIKE. It reads and never writes flash.** The question it exists to answer
 * is whether flashing the firmware from the phone is worth building, and that
 * turns on three unknowns, none of which need a single byte written:
 *
 *  1. does the phone enumerate the board at all, in host mode;
 *  2. can we reset it into the ROM download loader from Android;
 *  3. once there, does it answer.
 *
 * ### Why this is tractable, and it is a property of this board
 *
 * The Waveshare board presents `0x303A:0x1001` — Espressif's **native**
 * USB-Serial/JTAG peripheral, a standard CDC-ACM device. `esptool.py` on the
 * same board reports `USB mode: USB-Serial/JTAG`. That means no vendor driver,
 * no CP210x or CH34x quirks, and DTR/RTS is an ordinary CDC control transfer
 * rather than a chip-specific register write. A board with a bridge chip would
 * have made this a much longer piece of work.
 *
 * ### The reset that gets it into download mode
 *
 * On a native-USB part the ROM watches DTR and RTS on the CDC interface and
 * reboots into the download loader when it sees a particular walk through the
 * four possible line states — the software equivalent of holding BOOT while
 * tapping RESET. [enterDownloadMode] has the sequence and why its ORDER is the
 * whole trick.
 *
 * **This was predicted as the step most likely to need adjusting on real
 * hardware, and it was.** The first attempt used a bridge-chip style sequence;
 * the board enumerated and claimed perfectly and SYNC was answered by nobody,
 * because the chip had never left the application firmware. If sync stops
 * answering again, this is still the first place to look and not the protocol —
 * which is the other reason the protocol lives in [EspRom] where it can be
 * tested without any of this.
 *
 * ### Nothing here can brick the pet
 *
 * The ROM bootloader is in mask ROM. It cannot be overwritten, so download mode
 * is always reachable and any failure is recoverable by unplugging. That is
 * what makes a spike safe to run against the only board in existence.
 */
@Singleton
class PetUsbLink @Inject constructor(
    private val appContext: Context,
    private val logger: DiagnosticLogger,
) {

    sealed interface Result {
        data object NoDevice : Result
        data object NeedsPermission : Result
        data class Failed(val why: String) : Result
        /** Synced with the ROM and read the chip. [name] is null if unrecognised. */
        data class Identified(val magic: Int, val name: String?, val serial: String?) : Result
    }

    /** One image and where it goes. Offsets are the build's own. */
    data class Image(val offset: Int, val bytes: ByteArray, val label: String)

    sealed interface Flash {
        data class Progress(val label: String, val written: Int, val total: Int) : Flash
        data class Done(val verified: Boolean) : Flash
        data class Failed(val why: String) : Flash
    }

    companion object {
        private const val TAG = "PetUsbLink"

        /** Espressif's native USB-Serial/JTAG. Measured on the pet's own board. */
        const val VID_ESPRESSIF = 0x303A
        const val PID_USB_JTAG_SERIAL = 0x1001

        const val ACTION_PERMISSION = "com.digitalpet.action.USB_PERMISSION"

        /** CDC: SET_CONTROL_LINE_STATE, bit 0 = DTR, bit 1 = RTS. */
        private const val CDC_SET_CONTROL_LINE_STATE = 0x22
        private const val CDC_REQ_TYPE_OUT = 0x21

        private const val SYNC_ATTEMPTS = 10
        private const val READ_TIMEOUT_MS = 200
        private const val WRITE_TIMEOUT_MS = 200

        /**
         * For FLASH_BEGIN and MD5, both of which do real work before replying —
         * the ROM erases the whole region up front, synchronously. Everything
         * else here answers in milliseconds.
         */
        private const val ERASE_TIMEOUT_MS = 30_000L
    }

    private val usb get() = appContext.getSystemService(UsbManager::class.java)

    /** The pet, if it is plugged in. Null covers "nothing" and "something else". */
    fun findBoard(): UsbDevice? = usb?.deviceList?.values?.firstOrNull {
        it.vendorId == VID_ESPRESSIF && it.productId == PID_USB_JTAG_SERIAL
    }

    fun hasPermission(device: UsbDevice): Boolean = usb?.hasPermission(device) == true

    fun requestPermission(device: UsbDevice) {
        val intent = PendingIntent.getBroadcast(
            appContext, 0, Intent(ACTION_PERMISSION).setPackage(appContext.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        usb?.requestPermission(device, intent)
    }

    /**
     * Reset into the download loader, sync, and read the chip magic.
     *
     * Blocking, and meant to be called off the main thread. Bulk transfers here
     * are milliseconds, but the reset has deliberate sleeps in it.
     */
    fun identify(): Result {
        val device = findBoard() ?: return Result.NoDevice
        if (!hasPermission(device)) return Result.NeedsPermission

        val conn = usb?.openDevice(device) ?: return Result.Failed("openDevice returned null")
        try {
            val eps = claim(device, conn)
                ?: return Result.Failed("no CDC bulk endpoints; interfaces=${device.interfaceCount}")
            val bulkIn = eps.bulkIn
            val bulkOut = eps.bulkOut

            if (!reachLoader(conn, eps)) {
                return Result.Failed(
                    "no answer to SYNC. If this is a board running other firmware, " +
                        "hold BOOT while plugging it in."
                )
            }

            /*
             * DRAIN BEFORE ASKING ANYTHING ELSE.
             *
             * The ROM answers a sync burst with SEVERAL replies, and the loop
             * above stops at the first one — so the rest are still in the pipe.
             * Without this the next read after READ_REG returns leftover SYNC
             * frames, `firstOrNull { op == READ_REG }` finds none, and the
             * result is "synced, but no answer to READ_REG" from a chip that was
             * answering perfectly. That was the second hardware failure of this
             * spike, and it is a read-once-and-give-up bug rather than a
             * protocol one. esptool flushes here for the same reason.
             */
            drain(conn, bulkIn)

            val req = EspRom.readReg(EspRom.CHIP_MAGIC_ADDR)
            conn.bulkTransfer(bulkOut, req, req.size, WRITE_TIMEOUT_MS)
            val reply = awaitReply(conn, bulkIn, EspRom.CMD_READ_REG)
                ?: return Result.Failed("synced, but no answer to READ_REG")
            if (!reply.ok) return Result.Failed("READ_REG reported failure")

            logger.log(TAG, "chip magic 0x%08x (%s)".format(reply.value, EspRom.chipName(reply.value)))
            return Result.Identified(reply.value, EspRom.chipName(reply.value), device.serialNumber)
        } finally {
            conn.close()
        }
    }

    /**
     * The DTR/RTS sequence, and it is esptool's `USBJTAGSerialReset` verbatim.
     *
     * **A NATIVE-USB PART NEEDS A DIFFERENT SEQUENCE FROM A BRIDGE CHIP, and
     * the wrong one fails silently.** esptool picks by PID: `_get_pid() ==
     * USB_JTAG_SERIAL_PID` (0x1001) returns `USBJTAGSerialReset` and nothing
     * else, where a bridge board gets `ClassicReset`. The first version here
     * was a classic-style sequence; the board enumerated and claimed perfectly
     * and SYNC was answered by nobody, because the chip had never left the
     * application firmware.
     *
     * **THE ORDER OF THE INTERMEDIATE STATES IS THE WHOLE TRICK.** esptool's own
     * comment on the third step reads "Calls inverted to go through (1,1)
     * instead of (0,0)". pyserial sets DTR and RTS separately, so each call
     * emits a control transfer carrying the current value of the other, and the
     * pair walks:
     *
     * ```
     *   (0,0)  idle
     *   (1,0)  IO0 asserted — this is what selects download mode
     *   (1,1)  reset asserted WHILE IO0 is held      <- never (0,0)
     *   (0,1)  IO0 released, still in reset
     *   (0,0)  out of reset; the ROM samples the strapping and enters the loader
     * ```
     *
     * Passing through (0,0) instead resets the chip with IO0 already released,
     * which is an ordinary reboot into the firmware — the failure that was seen.
     * So this is written as a list of STATES rather than two independent
     * setters, because the states are the specification and the setters are
     * only how pyserial happens to produce them.
     *
     * The repeated (0,1) is esptool's as well: it notes Windows only propagates
     * DTR when RTS is set. Harmless here, and kept so this stays a
     * transcription rather than an adaptation.
     */
    private fun enterDownloadMode(conn: UsbDeviceConnection) {
        fun lines(dtr: Boolean, rts: Boolean) {
            val value = (if (dtr) 1 else 0) or (if (rts) 2 else 0)
            conn.controlTransfer(
                CDC_REQ_TYPE_OUT, CDC_SET_CONTROL_LINE_STATE, value, 0, null, 0, WRITE_TIMEOUT_MS
            )
        }
        lines(dtr = false, rts = false); Thread.sleep(100)   // idle
        lines(dtr = true, rts = false); Thread.sleep(100)    // set IO0
        lines(dtr = true, rts = true)                        // reset, IO0 still held
        lines(dtr = false, rts = true)                       // release IO0
        lines(dtr = false, rts = true); Thread.sleep(100)    // esptool's Windows repeat
        lines(dtr = false, rts = false); Thread.sleep(200)   // out of reset
    }

    /**
     * Write [images] to flash and verify each by MD5.
     *
     * **PARKED DELIBERATELY, 2026-08-12, and NEVER RUN AGAINST HARDWARE.**
     *
     * It was written for a first-run flow that has since been dropped: setting
     * a pet up will not involve flashing it. This is kept for firmware
     * UPDATES, which is the case it suits better anyway — an existing pet runs
     * our firmware, so the software reset below reaches it and no buttons are
     * involved.
     *
     * **This is not the unreachable-code smell, and the difference is the
     * record.** A sweep on 2026-08-11 deleted four functions with no callers
     * and the commit drew the line: a finished feature whose entry point was
     * never wired is a bug; residue from a removed control is clutter. This is
     * a third thing — parked, with a reason and a date. Do not delete it as
     * dead code — what is proven and what is not is recorded directly below.
     *
     * ### What is proven and what is not
     *
     * Proven on hardware, via [identify]: enumeration in host mode, the CDC
     * claim, the reset into the ROM loader, and the ROM answering — chip magic
     * `0x00000009`, matching `esptool.py` on the same board.
     *
     * NOT proven: a single byte of this function. Every command layout below is
     * mutation-tested in [EspRom] against vectors generated from esptool's own
     * struct packing, but the sequencing has never met a chip, and sequencing
     * against real hardware is exactly what caught the spike out twice.
     *
     * ### Why this cannot brick a pet, and why that is load-bearing
     *
     * The ROM bootloader is in mask ROM and is not one of the regions written.
     * A flash interrupted anywhere leaves a board that still enumerates, still
     * enters download mode, and can simply be flashed again. That is what makes
     * it defensible to offer this to somebody during setup.
     *
     * ### No stub, deliberately for now
     *
     * esptool uploads a small stub before writing, which raises the block size
     * from 0x400 to 0x4000 and is most of its speed. Doing that means shipping
     * a per-chip binary and a RAM-download path — more surface, and none of it
     * needed to find out whether flashing works at all. The ROM's own loader is
     * slower and complete. Speed is the next question, not this one.
     *
     * @param onProgress called from this thread, often. Not for the main one.
     */
    fun flash(images: List<Image>, reboot: Boolean = true, onProgress: (Flash) -> Unit): Flash {
        val device = findBoard() ?: return Flash.Failed("no board on USB")
        if (!hasPermission(device)) return Flash.Failed("no USB permission")
        val conn = usb?.openDevice(device) ?: return Flash.Failed("openDevice returned null")
        try {
            val eps = claim(device, conn) ?: return Flash.Failed("no CDC bulk endpoints")
            if (!reachLoader(conn, eps)) {
                return Flash.Failed("no answer to SYNC — hold BOOT while plugging in")
            }
            drain(conn, eps.bulkIn)

            conn.bulkTransfer(eps.bulkOut, EspRom.spiAttach(), EspRom.spiAttach().size, WRITE_TIMEOUT_MS)
            awaitReply(conn, eps.bulkIn, EspRom.CMD_SPI_ATTACH)
                ?: return Flash.Failed("SPI_ATTACH was not answered")

            for (img in images) {
                val begin = EspRom.flashBegin(img.bytes.size, img.offset)
                conn.bulkTransfer(eps.bulkOut, begin, begin.size, WRITE_TIMEOUT_MS)
                /*
                 * The ROM erases the whole region synchronously before it
                 * answers, so this reply is seconds away for a megabyte — not
                 * milliseconds like every other command here. A default timeout
                 * reads that as a dead chip.
                 */
                val ok = awaitReply(conn, eps.bulkIn, EspRom.CMD_FLASH_BEGIN, ERASE_TIMEOUT_MS)
                    ?: return Flash.Failed("${img.label}: FLASH_BEGIN was not answered (erase)")
                if (!ok.ok) return Flash.Failed("${img.label}: FLASH_BEGIN refused")

                var seq = 0
                var sent = 0
                while (sent < img.bytes.size) {
                    val n = minOf(EspRom.FLASH_BLOCK, img.bytes.size - sent)
                    /*
                     * PADDED TO A FULL BLOCK WITH 0xFF, which is erased-flash's
                     * own value. The ROM expects the block size it was promised
                     * in FLASH_BEGIN; a short final block is a length mismatch,
                     * and padding with zeroes would write real zeroes over the
                     * tail of the image instead of leaving it erased.
                     */
                    val block = ByteArray(EspRom.FLASH_BLOCK) { 0xFF.toByte() }
                    img.bytes.copyInto(block, 0, sent, sent + n)
                    val pkt = EspRom.flashData(block, seq)
                    conn.bulkTransfer(eps.bulkOut, pkt, pkt.size, WRITE_TIMEOUT_MS)
                    val r = awaitReply(conn, eps.bulkIn, EspRom.CMD_FLASH_DATA)
                        ?: return Flash.Failed("${img.label}: block $seq was not answered")
                    if (!r.ok) return Flash.Failed("${img.label}: block $seq refused")
                    sent += n
                    seq++
                    onProgress(Flash.Progress(img.label, sent, img.bytes.size))
                }
            }

            /*
             * VERIFY BEFORE LEAVING FLASH MODE. The ROM returns the MD5 as 32
             * ASCII hex characters, so this compares text to text. A flash that
             * reports success and was never checked is the failure mode that
             * reaches a user as a pet that will not boot.
             */
            var verified = true
            for (img in images) {
                val q = EspRom.flashMd5(img.offset, img.bytes.size)
                conn.bulkTransfer(eps.bulkOut, q, q.size, WRITE_TIMEOUT_MS)
                val r = awaitReply(conn, eps.bulkIn, EspRom.CMD_SPI_FLASH_MD5, ERASE_TIMEOUT_MS)
                if (r == null || !r.ok) { verified = false; break }
                if (String(r.data).lowercase() != md5Hex(img.bytes)) { verified = false; break }
            }

            val end = EspRom.flashEnd(reboot)
            conn.bulkTransfer(eps.bulkOut, end, end.size, WRITE_TIMEOUT_MS)
            return Flash.Done(verified)
        } finally {
            conn.close()
        }
    }

    private class Endpoints(val bulkIn: UsbEndpoint, val bulkOut: UsbEndpoint)

    /**
     * Claim the CDC **data** interface and find its bulk pair.
     *
     * A CDC-ACM device splits into a control interface (class 0x02) and a data
     * one (class 0x0A). The line state is a control transfer to endpoint zero,
     * so only the data interface has to be claimed.
     */
    private fun claim(device: UsbDevice, conn: UsbDeviceConnection): Endpoints? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != UsbConstants.USB_CLASS_CDC_DATA) continue
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep else bulkOut = ep
            }
            if (bulkIn != null && bulkOut != null && conn.claimInterface(iface, true)) {
                return Endpoints(bulkIn, bulkOut)
            }
        }
        return null
    }

    /**
     * Sync with the ROM loader, retrying.
     *
     * Sent repeatedly on purpose: the chip has just rebooted, so early attempts
     * land while the ROM is still coming up — esptool does the same. Reading
     * between attempts also drains the echo a native-USB part loops back, which
     * is why [EspRom.parse] rejects direction 0x00 rather than trusting the
     * first frame to arrive.
     */
    private fun sync(conn: UsbDeviceConnection, eps: Endpoints, attempts: Int = SYNC_ATTEMPTS): Boolean {
        repeat(attempts) {
            val s = EspRom.sync()
            conn.bulkTransfer(eps.bulkOut, s, s.size, WRITE_TIMEOUT_MS)
            if (readReplies(conn, eps.bulkIn).any { it.op == EspRom.CMD_SYNC }) return true
            Thread.sleep(50)
        }
        return false
    }

    /**
     * Get to a ROM loader that answers, however the board arrived.
     *
     * **TRY SYNC BEFORE RESETTING, because a board may already be in download
     * mode** — held BOOT while plugging in, or left there by a previous
     * attempt. Resetting one that is already listening is not harmful, but
     * asking first means the button path needs no software reset at all, and
     * the software reset is the part that depends on the running firmware
     * having exposed a CDC interface to receive it.
     *
     * That dependency is the whole reason this order matters. Our own firmware
     * enables USB Serial/JTAG as a secondary console
     * (`CONFIG_ESP_CONSOLE_SECONDARY_USB_SERIAL_JTAG`), so DTR/RTS reaches it.
     * **A factory board runs somebody else's firmware and may expose no CDC at
     * all** — in which case there is nothing to send a line state to, and
     * holding BOOT is the only way in. Sync-first makes both work through one
     * path.
     */
    private fun reachLoader(conn: UsbDeviceConnection, eps: Endpoints): Boolean {
        if (sync(conn, eps, attempts = 2)) return true
        enterDownloadMode(conn)
        return sync(conn, eps)
    }

    private fun md5Hex(b: ByteArray): String =
        java.security.MessageDigest.getInstance("MD5").digest(b)
            .joinToString("") { "%02x".format(it) }

    /**
     * Read until nothing more arrives, discarding it. Bounded, because an
     * unbounded drain is how a diagnostic becomes the fault.
     */
    private fun drain(conn: UsbDeviceConnection, ep: UsbEndpoint) {
        val buf = ByteArray(1024)
        repeat(16) {
            if (conn.bulkTransfer(ep, buf, buf.size, 50) <= 0) return
        }
    }

    /**
     * Wait for a reply to [op], across as many reads as it takes.
     *
     * **One read is not enough, and that is not a timeout question.** A bulk
     * transfer returns whatever is buffered at that instant — possibly an
     * unrelated frame, possibly nothing while the chip is still composing its
     * answer. Giving up after one read reports a silent chip whenever the
     * timing is merely unlucky.
     */
    private fun awaitReply(
        conn: UsbDeviceConnection,
        ep: UsbEndpoint,
        op: Byte,
        timeoutMs: Long = 1000,
    ): EspRom.Reply? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            readReplies(conn, ep).firstOrNull { it.op == op }?.let { return it }
        }
        return null
    }

    private fun readReplies(conn: UsbDeviceConnection, ep: UsbEndpoint): List<EspRom.Reply> {
        val buf = ByteArray(1024)
        val n = conn.bulkTransfer(ep, buf, buf.size, READ_TIMEOUT_MS)
        if (n <= 0) return emptyList()
        return EspRom.slipDecode(buf.copyOf(n)).mapNotNull { EspRom.parse(it) }
    }
}
