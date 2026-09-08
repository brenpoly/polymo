package com.digitalpet.ble

import java.util.UUID

/**
 * DigitalPet BLE protocol — v7.
 *
 * MIRROR OF `pet-esp32/main/pet_proto.h`. That header is the source of truth;
 * if you change it, change this file too and bump [VERSION] on both sides.
 *
 * ```
 * Service  D16B0001-A1B2-4C3D-8E5F-0A1B2C3D4E5F
 *   0002  Text     write | read    UTF-8, <= TEXT_MAX bytes
 *   0003  Mood     write | read    1 byte (Mood)
 *   0004  Info     read            [version, capsLo, capsHi]
 *   0005  Event    notify          [type, len, payload...]
 *   0010  AudioCtl write           1 byte, AUDIO_CTL_*
 *   0011  AudioDat notify          [seq] + one Opus packet
 *   0012  SpeakCtl write           1 byte, SPEAK_*
 *   0013  SpeakDat write           [seq] + one Opus packet, phone -> pet
 *   0014  Status   write           1 byte, what the PHONE is doing
 *   0015  Condition read | notify  [satiety, happiness, flags, misses×2, stage, batt%, mV×2]
 *   0016  Screen   write           1 byte, ScreenTime — the user's screen time
 *   0017  Command  write           1 byte, Command — an explicit user action
 *   0018..001F     reserved
 * ```
 */
object PetProtocol {

    const val VERSION = 10

    /**
     * Longest Text payload in **bytes on the wire**. Must match `PET_TEXT_MAX`
     * in `pet_proto.h`, whose receive buffer is this + 1 for the NUL.
     * Sized to fit one ATT write at the negotiated MTU of 256 (payload = MTU-3).
     */
    const val TEXT_MAX = 240

    /**
     * Advertised name, used as a scan fallback when filtering by UUID fails.
     *
     * **MUST match `ble_svc_gap_device_name_set` in `pet_ble.c`.** It is not
     * decoration: the UUID lives in the scan response, and matching a
     * scan-response UUID is unreliable across Android versions, so this name is
     * the dependable half of the scan filter — see `PetBleRepository.scan`.
     * Change it on one side only and *Scan for pets* finds nothing, while an
     * already-paired pet keeps working because reconnection goes by address.
     * That combination is a nasty one to debug: pairing looks broken only for
     * new pets, and only on a pet that has not been reflashed.
     *
     * Renamed from `DigitalPet` with the product, 2026-08-27.
     */
    const val DEVICE_NAME = "PolyMO"

    private fun uuid(id: Int) =
        UUID.fromString("D16B%04X-A1B2-4C3D-8E5F-0A1B2C3D4E5F".format(id))

    val SERVICE: UUID = uuid(0x0001)
    val CHAR_TEXT: UUID = uuid(0x0002)
    val CHAR_MOOD: UUID = uuid(0x0003)
    val CHAR_INFO: UUID = uuid(0x0004)
    val CHAR_EVENT: UUID = uuid(0x0005)
    val CHAR_AUDIO_CTL: UUID = uuid(0x0010)
    val CHAR_AUDIO_DAT: UUID = uuid(0x0011)
    val CHAR_SPEAK_CTL: UUID = uuid(0x0012)
    val CHAR_SPEAK_DAT: UUID = uuid(0x0013)

    /**
     * v4. Until v4 the Mood byte carried two unrelated things — an expression
     * parsed from a reply's emoji, and "the model is thinking" — and could carry
     * a third not at all, because "sick" and "dead" are not moods and cannot be
     * sent when the link is down anyway.
     *
     * Mood stays exactly as it was (expression, phone to pet, transient), which
     * is why a v3 phone's chat path still works. These two are the split:
     */
    /** What the PHONE is doing. Write, phone to pet. Persists until changed. */
    val CHAR_STATUS: UUID = uuid(0x0014)

    /** How the PET is. Read + notify, **pet to phone** — the pet owns this. */
    val CHAR_CONDITION: UUID = uuid(0x0015)

    /**
     * v9. WHICH FACE SET the pet is wearing. Read + write + notify.
     *
     * The payload is the set's id as UTF-8 — "classic", "bloom" — not an index,
     * and not terminated: the length is the length. An index would silently mean
     * a different face the moment a set was inserted into the middle of
     * `design-system/faces/`, and nothing about that failure would look wrong in
     * either codebase.
     *
     * **The pet decides, and says what it decided.** A write it does not
     * recognise is ignored rather than refused, and the pet notifies the id it
     * is actually wearing — so an app built against a newer design system than
     * the firmware converges on the truth instead of showing a face the pet is
     * not wearing. §5.0 rule 2 applied to a setting rather than to a mood.
     */
    val CHAR_FACESET: UUID = uuid(0x0019)

    /**
     * v10. The quiet-hours window. Read + write, phone to pet and back.
     *
     * Four bytes: start and end as minutes past local midnight, uint16
     * little-endian each. Minutes rather than hours because 21:30 is a
     * reasonable bedtime.
     *
     * **Readable, and the read is the point.** The pet REFUSES a window leaving
     * it fewer than six waking hours — it ages by waking seconds, so a 23-hour
     * quiet window would make it immortal and the care mechanic pointless. What
     * it holds may therefore not be what was asked, so the UI shows what it
     * reads back rather than what it sent. §5.0 rule 2, applied to a setting.
     */
    val CHAR_QUIET: UUID = uuid(0x001A)

    /**
     * v5. What the user's SCREEN TIME is doing. Write, phone to pet.
     *
     * DESIGN.md §1 decision 3: screen time is a sensor, not a feature. This is
     * the only thing on the wire the pet cannot observe for itself, and it is
     * sent as an *observation* — the user is over a threshold, or is not —
     * never as an instruction about how the pet should look or feel. Which apps
     * are watched and what the thresholds are stay phone-side settings; whether
     * that makes the pet ill, how fast it then declines, and when to stop
     * believing a stale report are all the pet's own decisions.
     */
    val CHAR_SCREEN: UUID = uuid(0x0016)

    /**
     * v6. An explicit user action, phone to pet. Currently only [Command.RESET].
     *
     * Deliberately a command channel rather than another state one. Everything
     * else the phone writes *describes the world* and leaves the pet to decide
     * what it means; this carries the one thing that is genuinely the user's
     * decision and not an observation — ending a dead pet's story.
     */
    val CHAR_COMMAND: UUID = uuid(0x0017)

    /**
     * v8. The local wall clock, for quiet hours. Write, phone to pet.
     *
     * Payload: **seconds since local midnight**, uint32 little-endian.
     *
     * Seconds-into-the-day rather than an epoch, deliberately. The pet needs to
     * know when it is 9pm and has no use for the date — and sending one would
     * invite setting its RTC from it, which would shift every persisted
     * timestamp (`born_epoch`, `zero_epoch`, the decay epoch) at once and age a
     * saved pet by months. This payload cannot be misused that way.
     *
     * Re-sent on every connect and on every usage poll, so DST changes and RTC
     * drift correct themselves without anything having to notice them.
     */
    val CHAR_CLOCK: UUID = uuid(0x0018)

    /** Written to [CHAR_STATUS]. */
    enum class Status(val value: Byte) {
        IDLE(0), THINKING(1), SPEAKING(2);
    }

    /**
     * Written to [CHAR_SCREEN]. A **level, re-asserted on every poll** — not an
     * edge sent once when the session starts.
     *
     * Two reasons, and both are about what happens when a message goes missing.
     * A lost "it ended" write would leave the pet ill indefinitely, which is the
     * same shape as a stuck playback flag on the firmware side. And a level is
     * what lets the pet time out on its own: silence means the phone is gone,
     * not that the user is still scrolling. The firmware stops believing a
     * report after [SCREEN_STALE_SEC], so the poll interval must stay well
     * inside it.
     */
    enum class ScreenTime(val value: Byte) {
        OK(0), OVERUSE(1);
    }

    /**
     * How long the pet keeps believing an overuse report it stops hearing.
     * Mirrors `PET_SCREEN_STALE_HINT_SEC`. The phone's usage poll runs once a
     * minute, five times inside this, so a single missed write costs nothing.
     */
    const val SCREEN_STALE_SEC = 300

    /**
     * Written to [CHAR_COMMAND].
     *
     * [RESET] is destructive and irreversible: it discards the pet's scores,
     * age, care mistakes and death, and starts a new one. **The pet refuses it
     * while alive**, and the app must confirm with the user before sending it —
     * neither check is a substitute for the other.
     */
    enum class Command(val value: Byte) {
        NONE(0), RESET(1);
    }

    /**
     * v6. The pet's life stage, derived on the pet from its age against its own
     * RTC — so it grows up while switched off, exactly as it gets hungry while
     * switched off. Never stored, so it cannot drift out of step with the clock.
     *
     * Ordered, and the order carries meaning: the stage sets how fast the scores
     * decay (an adult is harder work than an egg) as well as how big the face is.
     */
    enum class Stage {
        EGG, CHILD, TEEN, ADULT;

        companion object {
            fun from(b: Int): Stage? = entries.getOrNull(b)
        }
    }

    /**
     * Read from / notified on [CHAR_CONDITION]: how the pet actually is.
     *
     * The direction is the point of v4. The phone used to push the pet's face at
     * it; now the pet reports itself, which is what lets the LLM speak from the
     * pet's real state rather than being told what to pretend.
     */
    data class Condition(
        val satiety: Int,
        val happiness: Int,
        val calling: Boolean,
        val sick: Boolean,
        val dead: Boolean,
        val careMistakes: Int,
        /** v6, and **null on a pet that predates it** — not known, not EGG. */
        val stage: Stage? = null,
        /**
         * v7. **Null when not known** — a pre-v7 pet, a PMIC that did not
         * answer, or a gauge still settling after a cold start. Never treat an
         * absent reading as a flat battery.
         */
        val batteryPercent: Int? = null,
        val batteryMillivolts: Int? = null,
        /**
         * v10. The pet is in its QUIET HOURS — not merely dozing.
         *
         * This was called `asleep` and read a bit the firmware set from a
         * FIVE MINUTE inactivity timer, so the phone held every spoken
         * announcement all day while the pet beeped normally. Named for
         * what it means now.
         *
         * LAST in the list on purpose: everything before it is positional in
         * existing callers and tests, and inserting a field in the middle
         * silently re-binds their arguments — which is exactly what it did
         * before it was moved here, and the compiler only caught it because the
         * neighbouring types happened to differ.
         *
         * The pet owns when it sleeps — it has the clock and the rule — and this
         * makes that legible to the one other thing that can make it talk. An
         * unsolicited announcement waits for morning; an answer to something you
         * said does not, which is why the pet cannot enforce this itself: it
         * cannot tell the two apart when asked to speak.
         */
        val quietHours: Boolean = false,
    ) {
        companion object {
            const val MAX_SCORE = 4
            private const val CALLING = 0x01
            private const val SICK = 0x02
            private const val DEAD = 0x04
            private const val QUIET = 0x08

            /**
             * Null when the payload is too short to be a Condition. Extra
             * trailing bytes are tolerated: a later firmware may add fields.
             *
             * v6 is the first time that rule has been used in anger — the stage
             * byte was appended and the length went 5 -> 6. The `< 5` here
             * rather than `!= 5` is exactly what made that a non-event, which is
             * worth remembering the next time a field wants adding.
             */
            fun parse(b: ByteArray): Condition? {
                if (b.size < 5) return null
                val flags = b[2].toInt()
                return Condition(
                    satiety = b[0].toInt() and 0xFF,
                    happiness = b[1].toInt() and 0xFF,
                    calling = flags and CALLING != 0,
                    sick = flags and SICK != 0,
                    dead = flags and DEAD != 0,
                    quietHours = flags and QUIET != 0,
                    careMistakes = (b[3].toInt() and 0xFF) or ((b[4].toInt() and 0xFF) shl 8),
                    stage = if (b.size >= 6) Stage.from(b[5].toInt() and 0xFF) else null,
                    // 0xFF is the pet's "I do not know", distinct from 0.
                    batteryPercent = if (b.size >= 9) {
                        (b[6].toInt() and 0xFF).takeIf { it <= 100 }
                    } else null,
                    batteryMillivolts = if (b.size >= 9) {
                        ((b[7].toInt() and 0xFF) or ((b[8].toInt() and 0xFF) shl 8))
                            .takeIf { it > 0 }
                    } else null,
                )
            }
        }
    }

    // ---- Audio (pet mic -> phone) ----------------------------------------

    /**
     * The pet captures 16 kHz mono and encodes 20 ms Opus frames, so each
     * packet decodes to [AUDIO_FRAME_SAMPLES] samples. This is also what
     * Whisper wants, so nothing resamples anywhere in the chain.
     */
    const val AUDIO_SAMPLE_RATE = 16_000
    const val AUDIO_FRAME_MS = 20
    const val AUDIO_FRAME_SAMPLES = AUDIO_SAMPLE_RATE / 1000 * AUDIO_FRAME_MS

    /** AudioDat payload is `[seq]` followed by the Opus packet. */
    const val AUDIO_HDR_LEN = 1

    const val AUDIO_CTL_STOP: Byte = 0
    const val AUDIO_CTL_START: Byte = 1

    // ---- Audio (phone -> pet speaker) ------------------------------------

    /**
     * Same format as the uplink, on purpose. Piper runs at 22050 Hz, which
     * Opus does not accept, so the phone resamples down to the uplink's rate
     * rather than up to 24 kHz — one audio format in the whole system.
     */
    const val SPEAK_ABORT: Byte = 0
    const val SPEAK_BEGIN: Byte = 1
    const val SPEAK_END: Byte = 2

    /** One AudioDat notification: a sequence number and the raw Opus packet. */
    data class AudioFrame(val seq: Int, val packet: ByteArray) {
        // ByteArray needs these by hand; the seq is what actually identifies a frame.
        override fun equals(other: Any?) =
            other is AudioFrame && other.seq == seq && other.packet.contentEquals(packet)

        override fun hashCode() = 31 * seq + packet.contentHashCode()
    }

    /** Parses one AudioDat notification, or null if it is too short to hold a packet. */
    fun parseAudioFrame(bytes: ByteArray): AudioFrame? {
        if (bytes.size <= AUDIO_HDR_LEN) return null
        return AudioFrame(
            seq = bytes[0].toInt() and 0xFF,
            packet = bytes.copyOfRange(AUDIO_HDR_LEN, bytes.size)
        )
    }

    /** Standard Client Characteristic Configuration descriptor, for notifications. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Expressions the pet can display. Wire values must match `pet_mood_t`. */
    enum class Mood(val value: Byte) {
        NEUTRAL(0), HAPPY(1), SLEEPY(2), SURPRISED(3);

        companion object {
            fun from(value: Byte): Mood = entries.firstOrNull { it.value == value } ?: NEUTRAL
        }
    }

    /**
     * What the connected firmware can actually do, from the Info characteristic.
     * Lets the app light up features per-device instead of assuming.
     */
    @JvmInline
    value class Capabilities(val bits: Int) {
        val text: Boolean get() = bits and (1 shl 0) != 0
        val mood: Boolean get() = bits and (1 shl 1) != 0
        val events: Boolean get() = bits and (1 shl 2) != 0
        val mic: Boolean get() = bits and (1 shl 3) != 0
        val speaker: Boolean get() = bits and (1 shl 4) != 0
        val imu: Boolean get() = bits and (1 shl 5) != 0
        val touch: Boolean get() = bits and (1 shl 6) != 0
        val battery: Boolean get() = bits and (1 shl 7) != 0
    }

    data class Info(val version: Int, val capabilities: Capabilities)

    /** Parses the 3-byte Info payload, tolerating extra trailing bytes from a newer firmware. */
    fun parseInfo(bytes: ByteArray): Info? {
        if (bytes.size < 3) return null
        val caps = (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
        return Info(bytes[0].toInt() and 0xFF, Capabilities(caps))
    }

    /** Events the pet sends up. Unknown types are skipped via the length byte. */
    sealed interface Event {
        data class Ready(val info: Info) : Event
        data class MoodChanged(val mood: Mood) : Event

        /**
         * Capture started, or stopped after [frames] frames were produced.
         *
         * [frames] is the pet's own count, so a phone that received fewer knows
         * notifications went missing rather than transcribing the hole.
         */
        data class AudioState(val listening: Boolean, val frames: Int) : Event

        /** The pet finished speaking, so it is safe to listen again. */
        data object SpeakDone : Event

        /**
         * v10. Care offered and declined — the pet did not need it.
         *
         * **The one reaction the phone cannot derive.** Everything else comes
         * from a Condition transition, because satiety only ever rises when the
         * pet is fed. A refused feed changes nothing, so without this event the
         * pet's "no thank you" is silent and a full pet looks broken.
         */
        data class CareDeclined(val play: Boolean, val reason: Declined) : Event

        data class Unknown(val type: Int, val payload: ByteArray) : Event
    }

    /** Why care was declined. Only [FULL] is worth saying out loud — see
     *  PetVoice for why the other two stay quiet. */
    enum class Declined {
        FULL, COOLDOWN, DEAD, UNKNOWN;

        companion object {
            fun from(b: Byte) = when (b.toInt() and 0xFF) {
                0 -> FULL
                1 -> COOLDOWN
                2 -> DEAD
                else -> UNKNOWN
            }
        }
    }

    private const val EVT_READY = 0x01
    private const val EVT_MOOD = 0x02
    private const val EVT_CARE_NO = 0x13
    private const val EVT_AUDIO = 0x20

    /** PET_EVT_AUDIO payload byte 0 when the pet has finished playing. */
    private const val AUDIO_SPEAK_DONE = 2

    /** Parses one `[type, len, payload...]` notification frame. */
    fun parseEvent(bytes: ByteArray): Event? {
        if (bytes.size < 2) return null
        val type = bytes[0].toInt() and 0xFF
        val len = bytes[1].toInt() and 0xFF
        val payload = bytes.copyOfRange(2, minOf(2 + len, bytes.size))

        return when (type) {
            EVT_READY -> parseInfo(payload)?.let { Event.Ready(it) }
            EVT_MOOD -> payload.firstOrNull()?.let { Event.MoodChanged(Mood.from(it)) }
            EVT_CARE_NO -> if (payload.size >= 2) {
                Event.CareDeclined(
                    play = payload[0].toInt() == 1,
                    reason = Declined.from(payload[1]),
                )
            } else null
            EVT_AUDIO -> payload.firstOrNull()?.let { state ->
                if (state.toInt() == AUDIO_SPEAK_DONE) return Event.SpeakDone
                // Frame count only accompanies STOPPED; absent on START.
                val frames = if (payload.size >= 3) {
                    (payload[1].toInt() and 0xFF) or ((payload[2].toInt() and 0xFF) shl 8)
                } else {
                    0
                }
                Event.AudioState(listening = state.toInt() == 1, frames = frames)
            }
            else -> Event.Unknown(type, payload)
        }
    }
}
