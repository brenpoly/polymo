/*
 * DigitalPet BLE protocol — v7.
 *
 * SINGLE SOURCE OF TRUTH for the wire format. The Android client mirrors this
 * file (see PetProtocol.kt); if you change anything here, change it there and
 * bump PET_PROTO_VERSION.
 *
 * ---------------------------------------------------------------------------
 * Service  D16B0001-A1B2-4C3D-8E5F-0A1B2C3D4E5F
 *
 *   0002  Text     write | read    UTF-8, up to PET_TEXT_MAX, shown on screen
 *   0003  Mood     write | read    1 byte, pet_mood_t
 *   0004  Info     read            version + capability bitmap (see below)
 *   0005  Event    notify          pet -> phone events (see pet_evt_t)
 *
 *   0010  AudioCtl write           1 byte, PET_AUDIO_CTL_* — start/stop mic
 *   0011  AudioDat notify          [seq] + one Opus frame (see below)
 *   0012  SpeakCtl write           1 byte, PET_SPEAK_* — bracket an utterance
 *   0013  SpeakDat write           [seq] + one Opus frame, phone -> pet
 *
 *   0014  Status   write           1 byte, pet_status_t — what the PHONE is doing
 *   0015  Condition read | notify  how the PET is (see PET_COND_LEN)
 *   0016  Screen   write           1 byte, PET_SCREEN_* — the user's screen time
 *   0017  Command  write           1 byte, PET_CMD_* — an explicit user action
 *
 *   0019  FaceSet  read/write/ntf  the active face set's id, as a string
 *
 *   001A  Quiet    read/write      4 bytes, the quiet-hours window
 *
 *   001B..001F   still RESERVED.
 * ---------------------------------------------------------------------------
 *
 * Design notes:
 *  - Text/Mood are readable so a reconnecting client can resync instead of
 *    guessing what the pet is currently showing.
 *  - Info is read FIRST by the client: it announces which features this
 *    firmware actually has, so app and firmware can version independently.
 *  - v2 adds the mic path. Capture is *gated*: the pet records only between an
 *    explicit START and STOP, so it is not listening (or draining a battery)
 *    the rest of the time. There is no on-device wake word and no touch button
 *    (see the FT3168 note in pet-esp32.c), so the phone drives it.
 *  - Frames are streamed as they are encoded rather than buffered into one
 *    blob and sent at the end: transfer then overlaps with speaking, so the
 *    utterance is almost entirely delivered by the time the user stops. It is
 *    still not a realtime link — nothing depends on any single frame's latency.
 *  - v3 adds the speaker path, deliberately mirroring the mic path: same rate,
 *    same frame duration, same [seq]+packet framing. One audio format exists in
 *    this system, which is what made the uplink easy to reason about.
 *  - The link is HALF-DUPLEX by design. Driving the speaker raises the mic's
 *    noise floor by ~28 dB (measured), on top of the acoustic echo between two
 *    transducers centimetres apart. The phone owns both ends so it simply never
 *    overlaps them, and the firmware refuses either way round as a backstop.
 */
#pragma once
#include <stdint.h>

/* Bump on any incompatible change to the layout above. */
#define PET_PROTO_VERSION 10

/* Longest Text payload, in BYTES ON THE WIRE (not including any terminator).
 * The receive buffer is deliberately PET_TEXT_MAX + 1 so a full-size write is
 * accepted with room for the NUL — sizing it at exactly PET_TEXT_MAX meant a
 * maximal write failed with ATT_ERR_UNLIKELY, which looked like a mysterious
 * "long messages never appear".
 *
 * 240 keeps a full message inside a single ATT write at the negotiated MTU of
 * 256 (payload = MTU - 3), and is about as much as the 368px screen can show at
 * Montserrat 14. Must match TEXT_MAX in PetProtocol.kt. */
#define PET_TEXT_MAX 240

/* ---- UUIDs -------------------------------------------------------------- */
/* Display form: D16B00xx-A1B2-4C3D-8E5F-0A1B2C3D4E5F
 * BLE_UUID128_INIT() takes bytes little-endian, i.e. reversed; only byte 13
 * varies per attribute, so PET_UUID128(x) builds them all. */
#define PET_UUID128(last) BLE_UUID128_INIT(                       \
    0x5F, 0x4E, 0x3D, 0x2C, 0x1B, 0x0A, 0x5F, 0x8E,               \
    0x3D, 0x4C, 0xB2, 0xA1, (last), 0x00, 0x6B, 0xD1)

#define PET_UUID_SERVICE   0x01
#define PET_UUID_TEXT      0x02
#define PET_UUID_MOOD      0x03
#define PET_UUID_INFO      0x04
#define PET_UUID_EVENT     0x05
#define PET_UUID_AUDIO_CTL 0x10
#define PET_UUID_AUDIO_DAT 0x11
#define PET_UUID_SPEAK_CTL 0x12
#define PET_UUID_SPEAK_DAT 0x13
#define PET_UUID_STATUS    0x14   /* v4: phone -> pet, what the phone is doing  */
#define PET_UUID_CONDITION 0x15   /* v4: pet -> phone, how the pet actually is  */
#define PET_UUID_SCREEN    0x16   /* v5: phone -> pet, the user's screen time   */
#define PET_UUID_COMMAND   0x17   /* v6: phone -> pet, an explicit user action  */
#define PET_UUID_CLOCK     0x18   /* v8: phone -> pet, the local wall clock     */
#define PET_UUID_FACESET   0x19   /* v9: which face set the pet is wearing      */

/* ---- v9: the face set (0x19), read + write + notify ----------------------
 *
 * Payload is the set's ID STRING — "classic", "bloom" — not an index, and not
 * padded or terminated: the length is the length.
 *
 * AN ID RATHER THAN AN INDEX, because the two sides are built separately. An
 * index means a different face the moment a set is inserted into the middle of
 * design-system/faces/, and nothing about that failure looks wrong in either
 * codebase. An id that the pet does not have is simply refused.
 *
 * SELF-CORRECTING BY DESIGN. A write the pet does not recognise is IGNORED
 * rather than rejected, and the pet notifies the id it is actually wearing. So
 * an older firmware paired with a newer app converges on the truth instead of
 * the app showing a face the pet is not wearing — which is §5.0 rule 2 applied
 * to a setting rather than to a mood.
 *
 * The pet owns this, like everything else about how it looks and is: the phone
 * asks, and reads back what actually happened.
 */
#define PET_FACESET_ID_MAX 16

#define PET_UUID_QUIET     0x1A   /* v10: the quiet-hours window              */

/* ---- v10: quiet hours (0x1A), read + write --------------------------------
 *
 *   byte 0..1 : start, minutes past local midnight, uint16 little-endian
 *   byte 2..3 : end,   same
 *
 * READ AS WELL AS WRITE, and the read is the point: the pet REFUSES a window
 * leaving it fewer than six waking hours, so what it holds may not be what was
 * asked for. The phone shows what it reads back rather than what it sent —
 * §5.0 rule 2 applied to a setting, exactly as the face set is.
 *
 * Minutes rather than hours because 21:30 is a reasonable bedtime.
 */
#define PET_QUIET_LEN 4

/* ---- Info characteristic (read) ----------------------------------------- */
/*   byte 0    : PET_PROTO_VERSION
 *   byte 1..2 : capability bitmap, little-endian (PET_CAP_*)
 * Fixed 3 bytes; a client that knows a later version must tolerate extra
 * trailing bytes rather than assuming the length. */
#define PET_INFO_LEN 3

enum {
    PET_CAP_TEXT     = 1u << 0,  /* can display text          */
    PET_CAP_MOOD     = 1u << 1,  /* can change expression     */
    PET_CAP_EVENTS   = 1u << 2,  /* sends Event notifications */
    /* Not implemented yet — advertised as 0 until they are: */
    PET_CAP_MIC      = 1u << 3,  /* can capture audio         */
    PET_CAP_SPEAKER  = 1u << 4,  /* can play audio            */
    PET_CAP_IMU      = 1u << 5,  /* reports motion            */
    PET_CAP_TOUCH    = 1u << 6,  /* reports touch             */
    PET_CAP_BATTERY  = 1u << 7,  /* v7: reports battery in Condition */
    PET_CAP_STATUS   = 1u << 8,  /* v4: accepts a Status write         */
    PET_CAP_SIM      = 1u << 9,  /* v4: reports Condition — it is alive */
    PET_CAP_SCREEN   = 1u << 10, /* v5: accepts a Screen write          */
    PET_CAP_LIFE     = 1u << 11, /* v6: has life stages, can die, can be reset */
    PET_CAP_CLOCK    = 1u << 13, /* v8: accepts a Clock write, has quiet hours */
    PET_CAP_FACESET  = 1u << 14, /* v9: has face sets and can be told to switch */
    PET_CAP_QUIET    = 1u << 15, /* v10: quiet hours can be set and read back */
};

/* What this firmware currently supports. */
#define PET_CAPABILITIES \
    (PET_CAP_TEXT | PET_CAP_MOOD | PET_CAP_EVENTS | PET_CAP_MIC | PET_CAP_SPEAKER | \
     PET_CAP_STATUS | PET_CAP_SIM | PET_CAP_SCREEN | PET_CAP_LIFE | \
     PET_CAP_BATTERY | PET_CAP_CLOCK | PET_CAP_FACESET | PET_CAP_QUIET)

/* ---- v4: expression, status and condition are three different things ------
 *
 * Until v4 the Mood byte carried two of them and could not carry the third.
 * `PetExpression.parse` wrote SLEEPY because a reply contained an emoji; the
 * conversation engine wrote SLEEPY because the model was thinking. Same byte,
 * unrelated meanings — and neither could ever say "sick" or "dead", because
 * those are not moods and, when the link is down, nothing can be sent at all.
 *
 * So:
 *   Mood       (0x03)  EXPRESSION. Phone -> pet, transient, decays. Unchanged,
 *                      which is why v4 does not break a v3 phone's chat path.
 *   Status     (0x14)  What the PHONE is doing. Phone -> pet, persists until
 *                      changed. "Thinking" belongs here, not in Mood.
 *   Condition  (0x15)  How the PET is. Pet -> phone, read + notify. The pet
 *                      owns this (DESIGN.md §1 decision 1); the phone reads it,
 *                      which is what lets the LLM speak from the pet's state.
 *
 * The direction of Condition is the real change. Before v4 the phone pushed the
 * pet's face at it; now the pet reports itself.
 */
typedef enum {
    PET_STATUS_IDLE     = 0,
    PET_STATUS_THINKING = 1,   /* the model is generating */
    PET_STATUS_SPEAKING = 2,   /* a reply is being played */
} pet_status_t;

/* Condition payload, pet -> phone. A client seeing a later version must tolerate
 * extra trailing bytes rather than assume the length — v6 is the first time that
 * rule has actually been used. */
/*
 * v6 appends byte 5 and the length grows from 5 to 6. That is a COMPATIBLE
 * change by construction: this payload has always been documented as
 * append-only, with readers required to tolerate trailing bytes they do not
 * understand, and the phone's parser has always checked `size < 5` rather than
 * `size != 5`. A v5 phone against a v6 pet reads the first five bytes and simply
 * never learns the stage.
 */
#define PET_COND_LEN 9
/*   byte 0 : satiety   0..4
 *   byte 1 : happiness 0..4
 *   byte 2 : flags, PET_COND_*
 *   byte 3..4 : care mistakes, little-endian
 *   byte 5 : life stage, pet_stage_t          (v6)
 *   byte 6 : battery percent, 0xFF unknown    (v7)
 *   byte 7..8 : battery millivolts, LE        (v7)
 */
/*
 * WHY BATTERY LIVES HERE rather than in its own characteristic: it is part of
 * how the pet *is*, and Condition is already the one thing the pet says about
 * itself. It also has a practical reason that decided it — the serial log runs
 * over the same USB cable that charges the board, so a discharge can only be
 * observed from the phone. Battery had to reach the phone before battery life
 * could be measured at all.
 *
 * Bit 0 of the flags byte is not reused for a low-battery flag on purpose: what
 * counts as low is a product decision that has not been taken, and a threshold
 * baked into the wire is the hardest kind to change later.
 */
enum {
    PET_COND_CALLING = 1u << 0,  /* asking for attention right now */
    PET_COND_SICK    = 1u << 1,  /* v5: overusing a monitored app   */
    PET_COND_DEAD    = 1u << 2,  /* v6: both scores at 0 for too long */
    /*
     * v10: the pet is in its QUIET HOURS — not merely dozing.
     *
     * NAMED FOR WHAT IT MEANS, because the first version was called ASLEEP and
     * was wired to pet_is_asleep(), which is a FIVE MINUTE inactivity doze that
     * happens all day long. The phone read it as "quiet hours" and therefore
     * held every announcement and every spoken call, around the clock. The pet
     * still beeped, because the beep is gated on the real quiet hours — so it
     * called for help audibly and silently at the same time.
     *
     * Here so the PHONE can hold an unsolicited announcement until morning
     * without inventing a second set of quiet hours. The pet already owns when
     * it sleeps (it has the clock and the rule); this makes that decision
     * legible to the one other thing that can make it talk.
     *
     * It could not be enforced pet-side instead, and the reason is worth
     * recording: the pet cannot tell an ANSWER from an ANNOUNCEMENT at
     * pet_spk_begin(). If you speak to it at 3am you want a reply; you do not
     * want it reading out your email. Only the phone knows which it is asking
     * for, so only the phone can hold one and not the other.
     */
    PET_COND_QUIET   = 1u << 3,
};

/* ---- v5: screen time, phone -> pet ---------------------------------------
 *
 * DESIGN.md §1 decision 3: *screen time is a sensor, not a feature*. It feeds
 * the simulation rather than sitting beside it, so the shape here is an
 * OBSERVATION the pet reacts to — not an instruction about how to look.
 *
 * The split of responsibility follows decision 1, "the pet owns its own life":
 *
 *   the PHONE reports what only it can see — the user is over the threshold on
 *   a monitored app, or is not. Which apps, and what threshold, are the phone's
 *   own settings and stay there.
 *
 *   the PET decides what that MEANS: whether to fall sick, how fast the scores
 *   drain while it lasts, and when to give up waiting. None of that crosses the
 *   wire, and none of it is the phone's to decide.
 *
 * This is a LEVEL, not an edge, and the phone re-asserts it on every poll. Two
 * reasons. An edge that goes missing (a dropped write, a link that came back
 * without the phone noticing) would strand the pet sick forever, which is the
 * same class of bug as a stuck playback flag, where one lost end-of-utterance
 * left the pet refusing every later recording. And a level lets
 * the pet time out on its own: no report for PET_SCREEN_STALE_HINT_SEC means
 * the phone is gone, not that the user is still scrolling.
 *
 * One byte, because the pet needs to know the fact and not the detail. If a
 * future version wants "which app" or "how long", append fields — a reader must
 * tolerate extra trailing bytes rather than assume the length.
 */
enum {
    PET_SCREEN_OK      = 0,  /* not over the threshold on any monitored app */
    PET_SCREEN_OVERUSE = 1,  /* over it right now                           */
};

/* How long the pet should keep believing an overuse report it stops hearing.
 * A HINT to the firmware, published here so the phone can size its own poll
 * interval against it — a phone that reports less often than this would let the
 * pet recover on its own while the user is still scrolling. */
#define PET_SCREEN_STALE_HINT_SEC 300

/* ---- v6: commands, phone -> pet ------------------------------------------
 *
 * Deliberately a COMMAND channel and not a state one. Everything else the phone
 * writes describes the world (what the phone is doing, what the user's screen
 * time is doing) and leaves the pet to decide what it means. This carries the
 * one thing that is genuinely the user's decision rather than an observation:
 * ending a dead pet's story and starting a new one.
 *
 * RESET is destructive and irreversible, which is why it is a distinct
 * characteristic rather than a flag on an existing one — nothing should be able
 * to reach it by writing a wrong value to something else. The pet refuses it
 * while alive for the same reason; the phone is expected to confirm with the
 * user first, but the pet does not rely on that.
 */
enum {
    PET_CMD_NONE  = 0,
    PET_CMD_RESET = 1,   /* start a new pet — refused unless the pet is dead */
};

/* ---- Audio (mic -> phone) ------------------------------------------------ */
/*
 * 16 kHz mono 16-bit, Opus, 20 ms frames. The rate is fixed by what Whisper
 * wants on the phone, which is also what AudioRecorder already produces there,
 * so no resampling exists in the chain to get wrong. 20 ms is a legal Opus
 * frame duration at this rate and gives 320 samples per frame.
 *
 * AudioCtl (write, 1 byte) starts and stops capture. AudioDat (notify) then
 * carries ONE Opus frame per notification:
 *
 *     byte 0    : sequence number, wraps at 256
 *     byte 1..  : the Opus packet, exactly as opus_decode() wants it
 *
 * The frame length is the notification length minus the header — Opus packets
 * are self-describing in duration, so no explicit length field is needed. The
 * sequence number exists so the phone can spot a dropped notification and fill
 * it with PLC instead of splicing a gap into the audio.
 *
 * At ~24 kbps a frame is around 60 bytes, so a frame always fits one
 * notification at the negotiated MTU and never needs splitting.
 */
#define PET_AUDIO_SAMPLE_RATE   16000
#define PET_AUDIO_FRAME_MS      20
#define PET_AUDIO_FRAME_SAMPLES ((PET_AUDIO_SAMPLE_RATE / 1000) * PET_AUDIO_FRAME_MS)
#define PET_AUDIO_BITRATE       24000

/* [seq] */
#define PET_AUDIO_HDR_LEN       1

/* Ceiling for one encoded frame. Generous: at 24 kbps frames are ~60 bytes, and
 * the whole notification still has to fit the MTU. */
#define PET_AUDIO_MAX_FRAME     160

/* AudioCtl values. */
#define PET_AUDIO_CTL_STOP      0
#define PET_AUDIO_CTL_START     1

/* PET_EVT_AUDIO payload byte 0 — capture state changes.
 * On STOPPED, bytes 1..2 carry the total frames sent this utterance as a
 * little-endian uint16, so the phone can tell "the user was quiet" apart from
 * "notifications went missing" rather than silently transcribing a hole. */
#define PET_AUDIO_STOPPED       0
#define PET_AUDIO_STARTED       1

/* ---- Audio (phone -> pet speaker) ---------------------------------------
 *
 * Identical format to the uplink: 16 kHz mono Opus, 20 ms frames, one frame
 * per write as [seq][packet]. Piper runs at 22050 Hz, which Opus does not
 * accept, so the phone resamples — deliberately down to the uplink's rate
 * rather than up to 24 kHz, so this system has exactly one audio format.
 *
 * SpeakCtl brackets an utterance. BEGIN resets the pet's buffer and opens the
 * codec; END means "no more frames, play out what is left". ABORT drops
 * whatever is buffered, for when the user interrupts.
 *
 * Frames are buffered on the pet before playback starts, because BLE writes
 * arrive in bursts while the DAC needs a steady feed. PET_SPEAK_PREBUFFER is
 * how many frames must land before audio starts — enough to absorb a gap
 * between bursts without adding noticeable delay.
 */
#define PET_SPEAK_ABORT         0
#define PET_SPEAK_BEGIN         1
#define PET_SPEAK_END           2

#define PET_SPEAK_PREBUFFER     15    /* 300 ms */

/* PET_EVT_AUDIO payload byte 0 also reports playback, so the phone knows when
 * the pet has finished speaking and it is safe to listen again. */
#define PET_AUDIO_SPEAK_DONE    2

/* ---- Event characteristic (notify) -------------------------------------- */
/*   byte 0    : pet_evt_t
 *   byte 1    : payload length (may be 0)
 *   byte 2..  : payload
 * Clients must skip unknown event types using the length byte rather than
 * failing, so new events can be added without breaking older apps. */
#define PET_EVT_HDR_LEN 2

typedef enum {
    PET_EVT_READY   = 0x01,  /* sent on connect; payload: [version, caps_lo, caps_hi] */
    PET_EVT_MOOD    = 0x02,  /* mood applied;    payload: [mood]                      */
    /* Reserved for later milestones: */
    PET_EVT_TOUCH   = 0x10,
    PET_EVT_MOTION  = 0x11,
    PET_EVT_BATTERY = 0x12,
    /*
     * v10: care offered and DECLINED. payload: [what, why]
     *
     * The one thing the phone cannot derive for itself. Every other reaction
     * comes from a Condition transition — satiety only rises when the pet is
     * fed — but a refused feed changes nothing at all, so there is no
     * transition to read and the pet's "no thank you" would be silent.
     *
     * Additive, and older apps skip it by its length byte.
     */
    PET_EVT_CARE_NO = 0x13,
    PET_EVT_AUDIO   = 0x20,
} pet_evt_t;

/* PET_EVT_CARE_NO payload byte 0 — which offer was declined. */
enum { PET_CARE_FEED = 0, PET_CARE_PLAY = 1 };

/* ...and byte 1, why. FULL is the interesting one: the pet is well and says so.
 * COOLDOWN is pacing rather than refusal — the pet will accept in a moment —
 * and DEAD is not a refusal either, it is the absence of anyone to refuse. */
enum { PET_CARE_NO_FULL = 0, PET_CARE_NO_COOLDOWN = 1, PET_CARE_NO_DEAD = 2 };
