#pragma once
#include <stdbool.h>
#include <stdint.h>
#include "pet_proto.h"   /* wire format: UUIDs, version, caps, event types */

/* Expressions the face can wear.
 *
 * 0-3 are wire values the phone can set via the Mood characteristic and must
 * match PetProtocol.kt. PET_MOOD_SAD is **pet-local**: it is derived from the
 * simulation (pet_sim_baseline_mood) and the phone neither sends nor
 * understands it. Adding it did not change the wire contract — the phone still
 * only ever writes 0-3 — but anything that forwards a mood *to* the phone must
 * not forward this one until protocol v4 gives status and condition their own
 * channels. See DESIGN.md §2.3. */
typedef enum {
    PET_MOOD_NEUTRAL   = 0,
    PET_MOOD_HAPPY     = 1,
    PET_MOOD_SLEEPY    = 2,
    PET_MOOD_SURPRISED = 3,
    PET_MOOD_SAD       = 4,   /* pet-local, not on the wire */
    PET_MOOD_SICK      = 5,   /* pet-local, not on the wire */
    PET_MOOD_DEAD      = 6,   /* pet-local, not on the wire */
} pet_mood_t;

/* Which gesture the pet is currently teaching, if any. DESIGN.md §5.5.
 *
 * Pet-local and NOT on the wire. The decision taken 2026-08-04 is that the pet
 * teaches and the phone does not, so there is deliberately nothing here for the
 * app to render — putting it on the wire is what a later change of mind would
 * need, and it should be a change of mind rather than a convenience. */
typedef enum {
    PET_TEACH_NONE = 0,
    PET_TEACH_FEED = 1,   /* double tap — also the cure while sick */
    PET_TEACH_PLAY = 2,   /* shake, held */
} pet_teach_t;

/* Life stages. Ordered, and the order is relied on: sim_decay_seconds() and the
 * face both treat these as a scale rather than a set. Mirrored by the phone in
 * PetProtocol.Stage — these DO go on the wire, in the Condition payload. */
typedef enum {
    PET_STAGE_EGG   = 0,
    PET_STAGE_CHILD = 1,
    PET_STAGE_TEEN  = 2,
    PET_STAGE_ADULT = 3,
} pet_stage_t;

/* UI updates. Safe to call from ANY task, including the NimBLE host task:
 * these only enqueue the request and return immediately — they never take the
 * LVGL lock on the caller's thread. A dedicated UI task applies them.
 *
 * This matters: chr_access() runs on the NimBLE host task, and blocking that
 * task on a render-held mutex stalls the BLE stack (the same failure mode that
 * caused link timeouts on the earlier nRF54 build).
 *
 * Implemented in pet-esp32.c; called by the BLE layer (pet_ble.c). */
/* Screen brightness, 0-100. Routes to the BSP on a V1 board and to our own
 * CO5300 panel io on a V2 one, because the BSP's setter only knows the panel
 * bsp_display_new() built. Always use this, never bsp_display_brightness_set. */
esp_err_t pet_display_brightness(int percent);

void pet_set_text(const char *s);
void pet_set_mood(uint8_t mood);

/* Reflect capture state on the pet's own screen (the talk button fills in while
 * recording). Enqueue-only like the others, so the mic worker can call it. */
void pet_set_listening_ui(bool on);

/* Bring the talk button and the spoken line back, and restart the clock that
 * hides them. Enqueue-only like the rest, so it is safe from any task. */
void pet_chrome_wake(void);

/* Whether a phone is connected, shown as a small dot on the pet's screen.
 *
 * DERIVED ON THE PET, not sent by the phone — when the link is down there is
 * nothing to send it with, so "no phone" is the one state that must be noticed
 * rather than told. Called from pet_ble.c's GAP connect/disconnect handlers.
 * Enqueue-only like the rest, so it is safe from the NimBLE host task. */
void pet_set_link(bool connected);

/* v4: what the PHONE is doing — thinking, speaking, idle. Distinct from the
 * pet's own expression (Mood) and from its condition. Enqueue-only. */
void pet_set_status(uint8_t status);

/* v4: tell the phone how the pet is. Called by the simulation whenever
 * something it reports changes; a no-op when nothing is connected. */
void pet_ble_notify_condition(void);

/* ---- Face sets (v9) ------------------------------------------------------
 *
 * Which face the pet wears is DATA, generated into pet_faces.h from
 * design-system/faces/ — see that directory's README for why the design
 * system owns this and apply_mood() no longer does.
 *
 * The pet owns which set is active, exactly as it owns everything else about
 * itself (DESIGN.md §1 decision 1). The phone asks; the pet decides and says
 * what it decided. Implemented in pet-esp32.c because that is where the face
 * objects live.
 */

/* The active set's id. Never NULL — index 0 is a compiled-in fallback. */
const char *pet_face_set_id(void);

/* Wear this set. False means the id is unknown and the pet did not change,
 * which is a normal answer to a newer phone rather than an error. Persists the
 * choice and repaints everything the set touches. */
bool pet_face_set_select(const char *id);

/* What this firmware was built with. The phone has its own copy of the same
 * generated data, so these exist for logging and for the self-check. */
uint8_t     pet_face_set_count(void);

/* Tell the phone what the pet is wearing. Implemented in pet_ble.c. */
void pet_ble_notify_face_set(void);

/* Start the BLE peripheral (advertises as PET_ADV_NAME, see pet_ble.c).
 * Implemented in
 * pet_ble.c; called from app_main. */
void pet_ble_start(void);

/* Bring up the mic subsystem (M4). Implemented in pet_mic.c.
 *
 * Never fatal: if the codec does not answer on I2C the pet carries on without a
 * mic rather than aborting, because the BSP's own init would take the firmware
 * down in a boot loop (the same trap as bsp_display_start + touch).
 *
 * This only starts the worker; nothing is captured until pet_mic_set_listening
 * is called. */
void pet_mic_start(void);

/* Bring up the ES8311 speaker (M5). Implemented in pet_spk.c.
 *
 * Shares the codec with the mic, so call it after pet_mic_start(). Never
 * fatal for the same reason the mic is not. */
void pet_spk_start(void);

/* Playback, driven by the phone over SpeakCtl/SpeakDat. All are called from the
 * NimBLE host task, so none of them block: they only touch a queue and a flag.
 *
 * begin() opens an utterance, push() queues one Opus frame, end() means "no
 * more frames, play out what is buffered", abort() drops it. Playback starts
 * once PET_SPEAK_PREBUFFER frames have arrived, since BLE writes come in bursts
 * and the DAC needs a steady feed. Implemented in pet_spk.c. */
void pet_spk_begin(void);
void pet_spk_push(const uint8_t *frame, uint8_t len);
void pet_spk_end(void);
void pet_spk_abort(void);

/* Half-duplex interlock. Driving the speaker costs ~28 dB of microphone SNR on
 * top of the acoustic echo, so neither side starts while the other is running.
 * The phone owns both ends and should never ask; these are the backstop. */
bool pet_spk_is_playing(void);

/* Two short chirps — the pet asking for attention, not speaking a reply.
 * Blocks for about half a second and refuses while the mic or speaker is busy.
 * Implemented in pet_spk.c. */
void pet_spk_beep(void);

bool pet_mic_is_listening(void);

/* Start/stop capturing. Called from the NimBLE host task when the phone writes
 * the AudioCtl characteristic, and on disconnect — so it only flips a flag and
 * signals the mic task, never blocking the caller.
 *
 * While listening the pet encodes Opus frames and streams them over AudioDat,
 * bracketed by PET_EVT_AUDIO started/stopped events. */
void pet_mic_set_listening(bool on);

/* Send one encoded audio frame (already carrying its sequence byte) to the
 * phone over the AudioDat characteristic. Returns 0 on success, non-zero if
 * nothing is connected or the notification could not be queued.
 *
 * Implemented in pet_ble.c; called by pet_mic.c. */
int pet_ble_notify_audio(const uint8_t *frame, uint8_t len);

/* Send an event to the connected phone (pet -> app).
 *
 * No-op when nothing is connected or the client has not subscribed, so callers
 * do not need to track connection state. `payload` may be NULL when len == 0.
 * Safe to call from any task. */
void pet_ble_notify(pet_evt_t evt, const void *payload, uint8_t len);

/* --- The simulation (DESIGN.md §1 phase 1) ---------------------------------
 *
 * The pet owns its own life: scores live in its NVS and age against its RTC, so
 * it goes on living while the app is closed or the device is switched off. None
 * of this involves the phone. Implemented in pet_sim.c.
 */

/* Load the saved pet, age it by however long it was away, and start ticking. */
void pet_sim_start(void);

/* Raise a score by one and persist.
 *
 * Feeding is a DOUBLE TAP, playing is a shake held for a second. Both are
 * deliberately awkward: a single tap and a single threshold crossing were far
 * too easy to trigger by setting the pet down or knocking the desk.
 *
 * Both are rate-limited by a cooldown enforced inside pet_sim.c, so any future
 * input path inherits it. A call inside the cooldown is logged and ignored. */
void pet_sim_feed(void);
void pet_sim_play(void);

/* Milliseconds left before feeding / playing is allowed again; 0 when ready.
 * For the debug HUD. Either pointer may be NULL. */
void pet_sim_cooldowns(int32_t *feed_ms, int32_t *play_ms);

/* --- the call ---------------------------------------------------------------
 *
 * A score reaching 0 makes the pet ask out loud, and not answering within the
 * window is a care mistake. Answering means the gesture that fixes it, not
 * dismissing it. See DESIGN.md §1.
 */
bool     pet_sim_is_calling(void);
uint16_t pet_sim_care_mistakes(void);

/* True only while the pet is still ASKING — i.e. inside the care window, when it
 * is beeping. pet_sim_is_calling() stays true after the window closes, because
 * the score is still 0 and the phone still has something to tell the user; this
 * one follows the noise instead. Use it for anything that should stop when the
 * pet stops asking, sleep above all. */
bool     pet_sim_is_calling_aloud(void);

/* --- quiet hours, 21:00-09:00 (DESIGN.md 1) --------------------------------
 *
 * The pet does not decay, call or die overnight. It needs the local wall clock
 * to know when that is, and it has none of its own: pet_rtc.c starts the
 * oscillator at an arbitrary baseline because only DIFFERENCES matter to
 * everything else. So the phone tells it, and the pet keeps the answer as an
 * OFFSET rather than resetting its clock - resetting it would shift every
 * persisted timestamp at once and age a saved pet by months.
 *
 * Persisted, because the whole point is the night and at night the phone is
 * usually in another room. Until a phone has ever said, there are no quiet
 * hours and the pet behaves exactly as it always did.
 */
void pet_sim_set_time_of_day(uint32_t seconds_since_local_midnight);
bool pet_sim_is_quiet_hours(void);

/* v10: the quiet-hours window, as minutes past local midnight.
 *
 * The phone CONFIGURES; the pet keeps. Stored in NVS and applied by the pet's
 * own clock, so it survives a reboot and a phone that never comes back.
 *
 * set() REFUSES a window leaving fewer than six waking hours, and returns
 * false rather than clamping: the pet ages by waking seconds, so a 23-hour
 * quiet window would make it effectively immortal and the care mechanic
 * pointless. Silently giving somebody a different window from the one they
 * asked for is worse than telling them no. */
void pet_sim_get_quiet_hours(uint16_t *from_min, uint16_t *to_min);
bool pet_sim_set_quiet_hours(uint16_t from_min, uint16_t to_min);

/* Which gesture the pet should be demonstrating right now (pet_teach_t), or
 * PET_TEACH_NONE. Derived from how long a need has stood unmet — see the long
 * note at the implementation for why it is a duration and not just an empty
 * score. Cheap; safe to poll every second. */
uint8_t  pet_sim_teach_hint(void);

/* Stop the noise for the CURRENT call. The care mistake still accrues — a
 * silence button that also cancelled the obligation would let the consequence be
 * dismissed without the behaviour changing, which is the whole failure mode this
 * mechanic exists to avoid. Bound to a PWR short press. */
void pet_sim_silence_call(void);

/* The face the scores imply: HAPPY / NEUTRAL / SAD from their sum, floored to
 * SAD whenever either score is 0, and SICK whenever the pet is ill — sickness
 * outranks the scores because it is the thing the user can act on. This is what
 * an expression decays back to. */
uint8_t pet_sim_baseline_mood(void);

/* Current scores, for logging and diagnostics. Either pointer may be NULL. */
void pet_sim_get(uint8_t *satiety, uint8_t *happiness);

/* --- sickness, driven by screen time (DESIGN.md §1 phase 4) -----------------
 *
 * The phone reports the OBSERVATION — the user is over their screen-time
 * threshold on a monitored app, or is not — and the pet decides what it means.
 * That split is decision 1: the phone contributes what only it can see, and it
 * is an input rather than a reason to move the state.
 *
 * The report is a LEVEL and the phone renews it every poll, so a missed write
 * cannot strand the pet ill. Called from the NimBLE host task, and cheap: it
 * only touches simulation state and enqueues a UI update.
 */
void pet_sim_set_screen_overuse(bool overusing);

bool pet_sim_is_sick(void);

/* End the illness from the pet's side — the double tap, while sick.
 *
 * This clears the pet's condition and tells the phone nothing, so a user who is
 * still in the app makes the pet ill again on the next poll. That is deliberate:
 * a cure that outlasts the behaviour would let the consequence be dismissed
 * without anything changing, which DESIGN.md §1 names as the one way this
 * mechanic quietly becomes pointless. */
void pet_sim_cure(void);

/* --- life stages, death and reset (DESIGN.md §1 phase 6) --------------------
 *
 * The pet ages against the RTC from a birth date in NVS, so it grows up while
 * switched off exactly as it gets hungry while switched off. The stage is
 * derived from that age and is never stored — there is no state to get out of
 * step with the clock.
 */
uint8_t pet_sim_stage(void);        /* pet_stage_t */
int64_t pet_sim_age_seconds(void);

/* Death is BOTH scores at 0 continuously, for a window that shortens with the
 * care mistakes counted since phase 2. AND rather than OR is deliberate and
 * merciful: a starving but entertained pet lives, and so does a well-fed
 * miserable one, so neglect has to be total. */
bool pet_sim_is_dead(void);

/* Seconds until death at the current rate, or -1 when the pet is not dying —
 * either it is already dead, or at least one score is above 0. For the HUD, and
 * the only way to see a window that is otherwise entirely invisible. */
int64_t pet_sim_death_due_seconds(void);

/* Start a new pet. Wipes the scores, the age, the care mistakes and the death.
 *
 * ALWAYS an explicit user action — a BOOT hold on the pet, or a confirmed button
 * in the app. Death sets a flag rather than clearing the save precisely so that
 * this stays a decision someone makes; a pet that silently respawned would have
 * no stakes, and the corpse is the only evidence the user has that the whole
 * thing was real. */
void pet_sim_reset(void);

/* --- PCF85063 real-time clock (pet_rtc.c) -----------------------------------
 *
 * Elapsed wall time, which uptime cannot give: the RTC keeps running while the
 * device is off, because the AXP2101 powers it from the LiPo. Not in the BSP.
 */
bool pet_rtc_start(void);
bool pet_rtc_now(int64_t *out_epoch_sec);

/* True when the oscillator had stopped, so the time since the last save is
 * unknowable — a flat or removed battery. The simulation skips ageing across a
 * gap it cannot measure rather than inventing one. */
bool pet_rtc_time_was_lost(void);

/* --- QMI8658 accelerometer (pet_imu.c) --------------------------------------
 *
 * Only used to notice a shake, which feeds the pet. Not in the BSP.
 */
typedef void (*pet_imu_shake_cb_t)(void);
bool pet_imu_start(pet_imu_shake_cb_t on_shake);

/* For the debug HUD only: the largest recent departure from rest, the measured
 * resting magnitude, and the threshold a shake has to clear. Held for a couple
 * of seconds so a shake is readable on screen. Any pointer may be NULL. */
void pet_imu_debug(int32_t *peak, int32_t *rest, int32_t *threshold,
                   int32_t *held_ms, int32_t *hold_target_ms);

/* --- AXP2101 power management and the physical buttons (pet_pwr.c) ----------
 *
 * PWR is wired to the PMIC's PWRKEY, not to a GPIO, so its presses arrive as
 * interrupt-status bits over I2C. BOOT is GPIO 0, read directly. Neither is in
 * the BSP, which declares BSP_CAPS_BUTTONS 0.
 */
bool pet_pwr_start(void);

/* Battery state from the AXP2101's ADC and its own fuel gauge. False when the
 * PMIC is not answering; `percent` is 0xFF while the gauge is still settling
 * after a cold start. Any pointer may be NULL. */
bool pet_pwr_battery(uint8_t *percent, uint16_t *millivolts, bool *charging);

/* Dump every register that might hold a state of charge, so the gauge can be
 * identified from how the values MOVE rather than from a datasheet. Call it
 * from a task that runs after ~1.2 s; earlier logs do not survive the S3's
 * native-USB re-enumeration. */
void pet_pwr_battery_probe(void);

/* Read-only dump of the PMIC's output rails. Diagnostic for the black-screen
 * bug: it identifies which rail feeds the AMOLED by comparing a working board
 * against a black one. Writes nothing — see the comment at the definition. */
void pet_pwr_dump_rails(void);

/* --- idle sleep -------------------------------------------------------------
 *
 * The pet sleeps when it is left alone: the animations stop and the screen
 * dims. It is NOT switched off — the panel latches a state that only removing
 * power clears (a known open bug), so nothing here goes near
 * esp_lcd_panel_disp_on_off.
 *
 * Call pet_activity() from anything that counts as attention. Cheap and safe
 * from any task: it only stamps a timestamp and, if the pet was asleep, queues
 * a wake through the same UI queue everything else uses. */
void pet_activity(void);
bool pet_is_asleep(void);
