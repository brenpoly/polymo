/*
 * DigitalPet — the simulation. Phase 1 of DESIGN.md's roadmap.
 *
 * Two scores, 0-4 each. Satiety is raised by feeding (double tap), happiness by
 * playing (a sustained shake). Both decay slowly, and the face is derived from
 * their sum.
 *
 * THE PET OWNS THIS, not the phone. That is DESIGN.md §1 decision 1, and it is
 * why the state is in NVS here and the clock is the on-board RTC: the pet has to
 * go on living while the app is closed, the phone is elsewhere, or the device is
 * switched off. Nothing in this file talks to the phone, and phase 1 needs no
 * protocol change at all.
 *
 * Everything here is a starting guess and expected to be wrong. The point of
 * phase 1 is to find out how the loop *feels*, which no amount of specifying
 * answers.
 */
#include <stddef.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "nvs.h"
#include "nvs_flash.h"
#include "pet.h"

static const char *TAG = "pet_sim";

#define SIM_MAX_SCORE 4

/*
 * How fast the scores decay is NOT a constant — it depends on the life stage,
 * and the rates live with the stage thresholds further down (sim_decay_seconds).
 * Phases 1-5 ran at a flat 30 minutes a level, which is now what a teenager
 * gets; an egg is gentler and an adult is harder.
 *
 * The history is worth keeping because it is the argument that set the scale.
 * The first guess was 6 hours a level, which emptied a score in a day and made
 * the pet almost impossible to *observe* — you could not watch it get hungry, so
 * you could not judge whether the loop felt right. 30 minutes empties in two
 * hours and is still about 4x gentler than the 1996 Tamagotchi, whose table in
 * DESIGN.md §1 concluded that the honest direction to explore was downward.
 *
 * PET_SIM_FAST compresses every clock in this file to seconds so the whole
 * lifecycle can be watched end to end instead of waited out. Build with
 * `idf.py build -DPET_SIM_FAST=1`, and note the CMake cache keeps the flag until
 * it is explicitly set back to 0 — the same CMake-cache trap PET_DEMO_CAPTURE
 * carries. It matters more here than anywhere: at real rates this phase takes
 * three days to observe once.
 */
#ifndef PET_SIM_FAST
#define PET_SIM_FAST 0
#endif

#if PET_SIM_FAST
#define SIM_TICK_MS       2000
#else
#define SIM_TICK_MS       (60 * 1000)
#endif

/*
 * How long after feeding or playing before it can be done again.
 *
 * FIVE SECONDS as of 2026-08-04, down from sixty. The original argument, kept
 * because it is the thing that was traded away: a minute made tending the pet
 * *take a moment* rather than being four taps and done — filling an empty score
 * is four actions, so at a minute apart that was a few minutes of attention,
 * which DESIGN.md §1 called the point of the thing. At five seconds the same
 * four actions take twenty, so caring for the pet is now a brief interaction
 * rather than a paced one. A deliberate change, not a drift.
 *
 * The other job survives intact and is why this is not zero: it stops a mis-read
 * gesture being amplified into a full score. Five seconds is still far longer
 * than any plausible double-tap or shake misfire.
 *
 * Note this mostly affects topping up a HEALTHY pet. The cooldown is waived
 * entirely while a score is 0, because a pet that asks for food and then refuses
 * it is simply broken — so the emergency case was never paced anyway.
 *
 * Deliberately enforced HERE rather than in the gesture code. It is a rule about
 * the simulation, not about touch or the accelerometer, so every future input —
 * the phone, a mini-game — gets it for free.
 *
 * Uptime, not the RTC: a reboot forgetting a five-second cooldown is harmless,
 * and it keeps this off the I2C bus.
 */
#define SIM_ACTION_COOLDOWN_MS 5000

/*
 * The call, and care mistakes. DESIGN.md §1, "The call".
 *
 * A score reaching 0 makes the pet ask out loud. Not answering within the window
 * is a **care mistake** — which, taken from the original Tamagotchi, is the
 * actual game: it measures how well you responded, not what the score happens to
 * be right now. Nothing consumes mistakes until the life-stages phase; they are
 * counted and shown from here so the number can be judged before anything
 * depends on it.
 *
 * One hour against a 30-minute decay is close to the original's ratio (it allowed
 * 15 minutes against a ~6-minute decay), which is a coincidence worth noting
 * rather than relied on — the window is decoupled from the decay rate on purpose,
 * because scaling it would have made it absurd back when decay was 6 hours.
 *
 * ONE mistake per unanswered call, not one per window. After it accrues the
 * pet stops asking: the score stays 0 and the face stays sad, but the point has
 * been made and counted. A fresh call only arms once the score has been raised
 * and allowed to fall again. Unbounded accrual during neglect is what death is
 * for, not this counter.
 */
#if PET_SIM_FAST
#define SIM_CARE_WINDOW_SEC     30
#define SIM_CALL_REPEAT_SEC      2
#else
#define SIM_CARE_WINDOW_SEC   3600
#define SIM_CALL_REPEAT_SEC     60
#endif

/*
 * Sickness — DESIGN.md §1, "Sick, a condition not a mood". Phase 4.
 *
 * The phone reports that the user is over their screen-time threshold; the pet
 * decides that this makes it ill. While it lasts, both scores drain on top of
 * the ordinary decay, and that drain is the message: the point is not a warning
 * the user can dismiss but a consequence they can watch happening.
 *
 * SIX TIMES the normal rate, which is chosen to be *legible*. At 30 minutes a
 * level the ordinary decay is invisible over the length of a scrolling session,
 * so a sick pet decaying at the same speed would look identical to a well one
 * and the mechanic would say nothing. At five minutes a level, a full pet is
 * empty after twenty minutes of overuse and a score visibly moves inside a
 * single sitting. Still a guess, like every other rate here.
 */
#if PET_SIM_FAST
#define SIM_SICK_DRAIN_SECONDS   5
#else
#define SIM_SICK_DRAIN_SECONDS (5 * 60)
#endif

/*
 * How long an overuse report stays believed without being renewed.
 *
 * The phone re-asserts the level on every usage poll (once a minute), so in
 * normal running this never fires. It exists for the case the mechanic cannot
 * otherwise escape: the link drops while the pet is sick, and "the user closed
 * the app" can then never arrive. Without it the pet stays ill forever, which
 * is the same shape as a stuck playback flag that no later message can clear.
 *
 * Uptime, not the RTC: this is a watchdog on a live conversation with the
 * phone, not a fact about the pet's life, and it must not survive a reboot.
 */
#define SIM_SICK_STALE_MS (PET_SCREEN_STALE_HINT_SEC * 1000)

/*
 * Life stages — DESIGN.md §1 phase 6, and the numbers were a product decision
 * rather than a derivation.
 *
 * Four stages across roughly three days. The original Tamagotchi took 6-7 days
 * to adulthood; this project's decay is about 4x gentler than the original's, so
 * three days is the proportionate answer and is still a real arc rather than an
 * afternoon. Nothing about it is observable in a test session, which is exactly
 * what PET_SIM_FAST exists for — compressing the clock rather than distorting
 * the rate is what keeps the shipped numbers honest.
 *
 * Ages are seconds since birth, and birth is an RTC timestamp, so a pet grows up
 * while it is switched off in the same way it gets hungry while switched off.
 */
/*
 * The FAST thresholds are deliberately shorter than the FAST death timeline.
 * They were 30/90/180 s, and at those a fresh pet emptied at 80 s and died at
 * 140 s — a teenager whose scores had been 0 since before it stopped being a
 * child, so no decay ever ran at the teen or adult rate and the whole point of
 * the stage could not be observed. 10/25/45 s puts every stage inside a single
 * living pet. Real rates are untouched.
 */
#if PET_SIM_FAST
#define SIM_STAGE_CHILD_SEC   10
#define SIM_STAGE_TEEN_SEC    25
#define SIM_STAGE_ADULT_SEC   45
#else
#define SIM_STAGE_CHILD_SEC (12 * 3600)
#define SIM_STAGE_TEEN_SEC  (36 * 3600)
#define SIM_STAGE_ADULT_SEC (72 * 3600)
#endif

/*
 * DECAY ACCELERATES WITH AGE, which DESIGN.md §1 calls a free lifecycle lever:
 * the pet genuinely becomes more work as it grows up, without inventing a new
 * mechanic to make that true. It is mechanic 3 of the three taken from the
 * original.
 *
 * An egg is easy and an adult is demanding — 40 minutes a level against 20. The
 * middle of that range is the 30 minutes phases 1-5 were tuned and lived with,
 * so the pet people already know is now the teenager rather than every stage.
 */
#if PET_SIM_FAST
#define SIM_DECAY_EGG_SEC     20
#define SIM_DECAY_TEEN_SEC    15
#define SIM_DECAY_ADULT_SEC   10
#else
#define SIM_DECAY_EGG_SEC   (40 * 60)
#define SIM_DECAY_TEEN_SEC  (30 * 60)
#define SIM_DECAY_ADULT_SEC (20 * 60)
#endif

/*
 * Death — both scores at 0 CONTINUOUSLY, and the window shortens with care
 * mistakes.
 *
 * AND, not OR: a starving but entertained pet lives, and so does a well-fed
 * miserable one. DESIGN.md §1 is deliberate about that, and calls it merciful —
 * neglect has to be total.
 *
 * Twelve hours is chosen against the shape of a day rather than from the decay
 * rate. Scores empty about two hours after the last attention, so a pet left at
 * bedtime is roughly six hours into its window by morning and lives; a pet left
 * for a weekend does not. Sleeping through it must be survivable, going away
 * must not be, and twelve hours is the number that separates those two.
 *
 * The care mistakes finally do something here. They have been counted and
 * persisted since phase 2 with nothing consuming them precisely so the figure
 * could be judged before anything depended on it — this is that dependency, and
 * it is the original's rule: lifespan shortened by how badly you responded, not
 * by the score that happens to be showing now.
 *
 * Floored, because a pet with a long history of mistakes must still be
 * recoverable by someone who starts paying attention. An unbounded shortening
 * would make a neglected pet unsaveable, which punishes the person doing the
 * right thing late.
 */
#if PET_SIM_FAST
#define SIM_DEATH_BASE_SEC       60
#define SIM_DEATH_PER_MISS_SEC    5
#define SIM_DEATH_MIN_SEC        15
#else
#define SIM_DEATH_BASE_SEC   (12 * 3600)
#define SIM_DEATH_PER_MISS_SEC (36 * 60)
#define SIM_DEATH_MIN_SEC     (3 * 3600)
#endif

#define SIM_NVS_NAMESPACE "petsim"
#define SIM_NVS_KEY       "state"

/*
 * What is persisted.
 *
 * `epoch` is not "when we last ticked" but "when the current scores started",
 * which is what lets decay be computed correctly across a reboot *and* keeps
 * flash writes rare: nothing is saved on a tick that changes nothing, so in
 * normal running this is written about four times a day rather than every five
 * minutes.
 */
/*
 * Fields are APPENDED, never reordered, so an older saved pet still loads: the
 * old layout is a prefix of this one and sim_load accepts the shorter length.
 * Reordering would silently reinterpret a saved pet's bytes.
 */
typedef struct {
    uint8_t satiety;
    uint8_t happiness;
    int64_t epoch;
    /* --- appended after the first release --- */
    int64_t  call_epoch[2];   /* when each score's call began; 0 = not calling */
    uint16_t care_mistakes;
    /* --- appended for phase 6: life stages and death --- */
    int64_t born_epoch;       /* when this pet's life began; its age is now - this */
    int64_t zero_epoch;       /* when BOTH scores last reached 0; 0 = not both zero */
    uint8_t dead;
    /* --- appended for quiet hours: what the phone said the local time was --- */
    int32_t tod_offset;       /* add to an RTC reading to get local time-of-day */
    uint8_t tod_known;        /* 0 until a phone has ever told us */
} sim_state_t;

/* The layout before call state existed. See sim_load. */
#define SIM_STATE_V1_BYTES (offsetof(sim_state_t, call_epoch))
/* The layout before life stages and death existed. */
#define SIM_STATE_V2_BYTES (offsetof(sim_state_t, born_epoch))
/* The layout before quiet hours existed. A pet saved then simply has no clock
 * yet, which is the same state as a pet that has never met a phone. */
#define SIM_STATE_V3_BYTES (offsetof(sim_state_t, tod_offset))

/* Index into call_epoch, and the order sim_score() returns. */
enum { SCORE_SATIETY = 0, SCORE_HAPPINESS = 1, SCORE_COUNT = 2 };

static sim_state_t s_state = { .satiety = SIM_MAX_SCORE, .happiness = SIM_MAX_SCORE };
static bool        s_loaded;

static void sim_save(void)
{
    nvs_handle_t h;
    if (nvs_open(SIM_NVS_NAMESPACE, NVS_READWRITE, &h) != ESP_OK) {
        ESP_LOGE(TAG, "nvs_open failed - the pet will forget itself");
        return;
    }
    if (nvs_set_blob(h, SIM_NVS_KEY, &s_state, sizeof(s_state)) == ESP_OK) {
        nvs_commit(h);
    } else {
        ESP_LOGE(TAG, "nvs_set_blob failed - the pet will forget itself");
    }
    nvs_close(h);
}

static void sim_load(void)
{
    nvs_handle_t h;
    if (nvs_open(SIM_NVS_NAMESPACE, NVS_READONLY, &h) != ESP_OK) {
        ESP_LOGI(TAG, "no saved pet - starting a new one");
        return;
    }
    size_t len = sizeof(s_state);
    sim_state_t loaded;
    memset(&loaded, 0, sizeof(loaded));
    const esp_err_t err = nvs_get_blob(h, SIM_NVS_KEY, &loaded, &len);
    const bool sane = loaded.satiety <= SIM_MAX_SCORE && loaded.happiness <= SIM_MAX_SCORE;

    if (err == ESP_OK && sane &&
        (len == sizeof(loaded) || len == SIM_STATE_V1_BYTES ||
         len == SIM_STATE_V2_BYTES || len == SIM_STATE_V3_BYTES)) {
        /* A pet saved before call state existed is still a pet. The shorter
         * blob is this struct's prefix, and the appended fields were zeroed
         * above, so it loads with no call in progress and no mistakes — which
         * is the right starting point rather than a reason to kill it. */
        s_state = loaded;
        s_loaded = true;
        if (len == SIM_STATE_V1_BYTES) {
            ESP_LOGI(TAG, "migrated a pet saved before call state existed");
        } else if (len == SIM_STATE_V2_BYTES) {
            ESP_LOGI(TAG, "migrated a pet saved before life stages existed");
        }
        /*
         * A pet from before phase 6 has no birth date, and there is no way to
         * recover one — nothing ever recorded when it started. Treating it as
         * newborn is the only honest option, and it is also the kind one: the
         * alternative is inventing an age, and an invented age could hatch a
         * pet straight into adulthood or, worse, straight into a death window.
         * pet_sim_start() fills this in once it has a clock.
         */
    } else {
        ESP_LOGW(TAG, "saved pet unreadable (err %d, %u B) - starting a new one",
                 (int)err, (unsigned)len);
    }
    nvs_close(h);
}

static uint8_t decay_one(uint8_t score, uint32_t levels)
{
    return (uint8_t)(levels >= score ? 0 : score - levels);
}

/* --- life stages (phase 6) ------------------------------------------------- */

/* Defined with the rest of the sickness state further down; pet_sim_reset()
 * needs it and sits above it. */
static void sim_clear_sickness(void);
/* Quiet hours. Defined further down with the rest of the time-of-day handling;
 * declared here because death is computed above it. */
static int64_t sim_waking_between(int64_t from, int64_t to);
/* The live score, which is authoritative for "is there a need" — see
 * sim_need_is_prolonged. Defined below sim_service_calls. */
static uint8_t *sim_score(int which);

int64_t pet_sim_age_seconds(void)
{
    int64_t now = 0;
    if (s_state.born_epoch == 0 || !pet_rtc_now(&now) || now < s_state.born_epoch) {
        return 0;
    }
    return now - s_state.born_epoch;
}

uint8_t pet_sim_stage(void)
{
    const int64_t age = pet_sim_age_seconds();
    if (age < SIM_STAGE_CHILD_SEC) return PET_STAGE_EGG;
    if (age < SIM_STAGE_TEEN_SEC)  return PET_STAGE_CHILD;
    if (age < SIM_STAGE_ADULT_SEC) return PET_STAGE_TEEN;
    return PET_STAGE_ADULT;
}

bool pet_sim_is_dead(void)
{
    return s_state.dead != 0;
}

/*
 * Seconds per level of decay at the current stage.
 *
 * Note what this does NOT try to do: integrate the rate over an interval that
 * spans a stage change. sim_apply_elapsed applies the CURRENT stage's rate to
 * the whole elapsed period, so a pet that grew up while switched off is decayed
 * at its new rate for time it lived at the old one.
 *
 * That is deliberate and the error is unobservable. Both scores floor at 0, and
 * every interval long enough to cross a 12-hour stage boundary is many times
 * longer than the two-or-three hours it takes to empty a full score at any of
 * these rates — so both the exact and the approximate answer are "empty". The
 * exact version would need the decay integrated piecewise across boundaries for
 * a difference that cannot be seen.
 */
static uint32_t sim_decay_seconds(void)
{
    switch (pet_sim_stage()) {
    case PET_STAGE_EGG:
    case PET_STAGE_CHILD: return SIM_DECAY_EGG_SEC;
    case PET_STAGE_TEEN:  return SIM_DECAY_TEEN_SEC;
    default:              return SIM_DECAY_ADULT_SEC;
    }
}

/* How long both scores may sit at 0 before the pet dies, given its history. */
static int64_t sim_death_window(void)
{
    const int64_t shortening = (int64_t)s_state.care_mistakes * SIM_DEATH_PER_MISS_SEC;
    const int64_t window = (int64_t)SIM_DEATH_BASE_SEC - shortening;
    return window < SIM_DEATH_MIN_SEC ? SIM_DEATH_MIN_SEC : window;
}

int64_t pet_sim_death_due_seconds(void)
{
    if (s_state.dead || s_state.zero_epoch == 0) {
        return -1;
    }
    int64_t now = 0;
    if (!pet_rtc_now(&now)) {
        return -1;
    }
    /* Waking seconds: a pet must not die during the hours it cannot call for
     * help, and in which nobody is awake to feed it. */
    const int64_t left = sim_death_window() - sim_waking_between(s_state.zero_epoch, now);
    return left > 0 ? left : 0;
}

/*
 * Track the both-at-zero clock and kill the pet when it runs out.
 *
 * Returns true when something worth persisting changed.
 */
static bool sim_service_death(int64_t now)
{
    if (s_state.dead) {
        return false;
    }

    const bool both_zero = (s_state.satiety == 0 && s_state.happiness == 0);

    if (!both_zero) {
        if (s_state.zero_epoch != 0) {
            ESP_LOGI(TAG, "no longer at zero on both - the death clock stops");
            s_state.zero_epoch = 0;
            return true;
        }
        return false;
    }

    if (s_state.zero_epoch == 0) {
        s_state.zero_epoch = now;
        /* Printed in whichever unit is not zero. It read "0 h to live" under
          * PET_SIM_FAST, which is the mode this line exists to be read in. */
        const int64_t w = sim_death_window();
        if (w >= 3600) {
            ESP_LOGW(TAG, "both scores empty - %lld h to live unless something changes",
                     (long long)(w / 3600));
        } else {
            ESP_LOGW(TAG, "both scores empty - %lld s to live unless something changes",
                     (long long)w);
        }
        return true;
    }

    if (sim_waking_between(s_state.zero_epoch, now) < sim_death_window()) {
        return false;
    }

    /*
     * Death sets a flag; it does NOT wipe the save. DESIGN.md §1 is explicit
     * that reset must stay an explicit user action, because a pet that silently
     * respawns has no stakes — and because the corpse is the only evidence the
     * user has that any of this was real.
     */
    s_state.dead = 1;
    /*
     * The obligation ends with the pet. Without this the call state stays
     * frozen at the moment of death and Condition reports CALLING for a corpse
     * — measured on hardware, "satiety 0 happiness 0 CALLING DEAD misses 8".
     * Nothing acts on it (the beep is guarded and the prompt ignores calling),
     * so it is a flag that is merely untrue, which is the kind that survives.
     */
    s_state.call_epoch[SCORE_SATIETY] = 0;
    s_state.call_epoch[SCORE_HAPPINESS] = 0;

    ESP_LOGE(TAG, "the pet has died - %lld s at zero, %u care mistakes, age %lld h",
             (long long)sim_waking_between(s_state.zero_epoch, now), s_state.care_mistakes,
             (long long)(pet_sim_age_seconds() / 3600));
    return true;
}

void pet_sim_reset(void)
{
    int64_t now = 0;
    pet_rtc_now(&now);

    const bool was_dead = s_state.dead;
    memset(&s_state, 0, sizeof(s_state));
    /* A new pet is not ill. If the user is still over their threshold the phone
     * says so within the minute and it falls ill again on its own merits —
     * which is right, and is not the same thing as inheriting it. */
    sim_clear_sickness();
    s_state.satiety    = SIM_MAX_SCORE;
    s_state.happiness  = SIM_MAX_SCORE;
    s_state.epoch      = now;
    s_state.born_epoch = now;

    sim_save();
    ESP_LOGW(TAG, "reset - a new pet (the old one was %s)",
             was_dead ? "dead" : "alive");
    pet_set_mood(pet_sim_baseline_mood());
    pet_ble_notify_condition();
}

/*
 * Age the pet by however much wall time has passed.
 *
 * Called at boot and on every tick, and correct in both cases because it works
 * from `epoch` rather than from an interval — a pet switched off for two days
 * gets two days of decay the moment it comes back, which is the entire point of
 * using the RTC instead of uptime.
 */
/* --- quiet hours (DESIGN.md §1) -------------------------------------------
 *
 * The pet does not decay, call, or die between 21:00 and 09:00.
 *
 * WHY THE PET NEEDS AN OFFSET AND NOT A CLOCK. pet_rtc.c deliberately starts the
 * oscillator at an arbitrary baseline — "what is wanted here is elapsed seconds,
 * not the correct date" — and every persisted timestamp is measured against it:
 * `epoch`, `born_epoch`, `zero_epoch`, `call_epoch`. **Setting the RTC to real
 * local time would shift all four at once**, ageing a saved pet by months or
 * giving it a negative age. So the RTC is left exactly as it is and the phone's
 * wall clock is stored as a DIFFERENCE, which changes no saved value and can be
 * re-sent as often as we like.
 *
 * It is persisted because the point of this feature is the night, and at night
 * the phone is usually in another room. A quiet-hours rule that needed a live
 * connection would fail in precisely the case it exists for — the same reasoning
 * that put the simulation on the pet in the first place (§1 decision 1).
 *
 * UNKNOWN UNTIL TOLD, and then the pet behaves exactly as it did before: no
 * quiet hours at all. A pet that guessed at midnight would apply the rule twelve
 * hours out, which is worse than not applying it.
 */
/*
 * QUIET HOURS ARE SETTABLE, and they are the pet's rather than the phone's.
 *
 * The phone CONFIGURES them and the pet keeps them — in NVS, applied by its own
 * clock, surviving a reboot and a phone that never comes back. That is the same
 * division as everything else here (DESIGN.md §1 decision 1): the phone hands
 * over inputs, the pet owns the life.
 *
 * 21:00-09:00 by default, which is what these were as constants.
 */
#define SIM_QUIET_DEFAULT_FROM  (21 * 3600)
#define SIM_QUIET_DEFAULT_TO    ( 9 * 3600)

/*
 * The pet ages by WAKING seconds, so this window is not only about noise — it
 * sets the pace of the whole simulation. A 23-hour quiet window would make the
 * pet effectively immortal and the care mechanic pointless, so the waking day
 * has a floor. Six hours is short enough to be a real choice and long enough
 * that a pet still needs looking after.
 */
#define SIM_MIN_WAKING_SEC  (6 * 3600)

static int32_t s_quiet_from_sec = SIM_QUIET_DEFAULT_FROM;
static int32_t s_quiet_to_sec   = SIM_QUIET_DEFAULT_TO;

#define QUIET_NVS_NAMESPACE "petquiet"

/* Waking seconds in a day, from the current window. Wraps midnight, so it is
 * the complement of the quiet span rather than a subtraction. */
static int32_t sim_waking_per_day(void)
{
    const int32_t quiet = (s_quiet_from_sec <= s_quiet_to_sec)
        ? (s_quiet_to_sec - s_quiet_from_sec)
        : (86400 - s_quiet_from_sec + s_quiet_to_sec);
    int32_t waking = 86400 - quiet;
    if (waking < SIM_MIN_WAKING_SEC) {
        waking = SIM_MIN_WAKING_SEC;
    }
    return waking;
}

/* How far the offset must move before it is worth a flash write. Two minutes:
 * far above the second-or-two of jitter between the phone's clock and the
 * RTC, far below a DST jump or any drift that matters. */
#define TOD_RESAVE_TOLERANCE_SEC 120


void pet_sim_set_time_of_day(uint32_t seconds_since_local_midnight)
{
    int64_t now = 0;
    if (!pet_rtc_now(&now) || seconds_since_local_midnight >= 86400u) {
        return;
    }
    int32_t offset = (int32_t)(seconds_since_local_midnight - (uint32_t)(now % 86400));
    if (offset < 0) {
        offset += 86400;
    }

    const bool first = !s_state.tod_known;

    /* Always current in RAM: this is what time-of-day is read from, and it costs
     * nothing to keep exact. */
    s_state.tod_offset = offset;
    s_state.tod_known  = true;

    /*
     * PERSIST RARELY, AND NOT ON EVERY WRITE.
     *
     * This arrives once a minute for as long as a phone is connected. Saving
     * each time would be ~1440 NVS writes a day against the four this file
     * normally does — and it would not even be saving anything new, because the
     * offset JITTERS by a second or two every time: the phone's second boundary
     * and the RTC's never line up, so a plain `changed?` test is true nearly
     * always and would have hidden the wear behind a check that looked careful.
     *
     * So the tolerance is what makes the gate real. Anything smaller than it is
     * noise; anything larger is a DST change or genuine RTC drift, which are the
     * only two things worth a flash write. The saved value is at worst a couple
     * of minutes stale, which cannot move a 21:00 boundary in any way a person
     * would notice.
     */
    static int32_t saved_offset;
    static bool    have_saved;
    int32_t drift = offset - saved_offset;
    if (drift < 0) drift = -drift;

    if (first || !have_saved || drift > TOD_RESAVE_TOLERANCE_SEC) {
        ESP_LOGI(TAG, "local time %02u:%02u (offset %ld s)%s",
                 (unsigned)(seconds_since_local_midnight / 3600),
                 (unsigned)((seconds_since_local_midnight / 60) % 60),
                 (long)offset, first ? " - quiet hours now active" : "");
        saved_offset = offset;
        have_saved   = true;
        sim_save();
    }
}

/* Local seconds since midnight, or -1 when the clock has never been set. */
static int32_t sim_time_of_day(int64_t rtc)
{
    if (!s_state.tod_known) {
        return -1;
    }
    return (int32_t)((rtc + s_state.tod_offset) % 86400);
}

void pet_sim_get_quiet_hours(uint16_t *from_min, uint16_t *to_min)
{
    if (from_min) *from_min = (uint16_t)(s_quiet_from_sec / 60);
    if (to_min)   *to_min   = (uint16_t)(s_quiet_to_sec / 60);
}

bool pet_sim_set_quiet_hours(uint16_t from_min, uint16_t to_min)
{
    if (from_min >= 1440 || to_min >= 1440) {
        return false;
    }
    const int32_t prev_from = s_quiet_from_sec, prev_to = s_quiet_to_sec;
    s_quiet_from_sec = (int32_t)from_min * 60;
    s_quiet_to_sec   = (int32_t)to_min * 60;

    /* Refused rather than clamped: silently giving somebody a different window
     * from the one they set is worse than telling them no, and the phone shows
     * what the pet actually holds. See SIM_MIN_WAKING_SEC. */
    const int32_t quiet = (s_quiet_from_sec <= s_quiet_to_sec)
        ? (s_quiet_to_sec - s_quiet_from_sec)
        : (86400 - s_quiet_from_sec + s_quiet_to_sec);
    if (86400 - quiet < SIM_MIN_WAKING_SEC) {
        s_quiet_from_sec = prev_from;
        s_quiet_to_sec = prev_to;
        ESP_LOGW(TAG, "quiet hours refused: fewer than %d waking hours left",
                 SIM_MIN_WAKING_SEC / 3600);
        return false;
    }

    nvs_handle_t h;
    if (nvs_open(QUIET_NVS_NAMESPACE, NVS_READWRITE, &h) == ESP_OK) {
        nvs_set_u16(h, "from", from_min);
        nvs_set_u16(h, "to", to_min);
        nvs_commit(h);
        nvs_close(h);
    }
    ESP_LOGI(TAG, "quiet hours <- %02u:%02u-%02u:%02u",
             from_min / 60, from_min % 60, to_min / 60, to_min % 60);
    return true;
}

static void quiet_hours_restore(void)
{
    nvs_handle_t h;
    if (nvs_open(QUIET_NVS_NAMESPACE, NVS_READONLY, &h) != ESP_OK) {
        return;                 /* never set: the defaults stand */
    }
    uint16_t from = 0, to = 0;
    if (nvs_get_u16(h, "from", &from) == ESP_OK &&
        nvs_get_u16(h, "to", &to) == ESP_OK &&
        from < 1440 && to < 1440) {
        s_quiet_from_sec = (int32_t)from * 60;
        s_quiet_to_sec   = (int32_t)to * 60;
        ESP_LOGI(TAG, "quiet hours restored: %02u:%02u-%02u:%02u",
                 from / 60, from % 60, to / 60, to % 60);
    }
    nvs_close(h);
}

bool pet_sim_is_quiet_hours(void)
{
    int64_t now = 0;
    if (!pet_rtc_now(&now)) {
        return false;
    }
    const int32_t tod = sim_time_of_day(now);
    if (tod < 0) {
        return false;   /* clock unknown: behave exactly as before */
    }
    /* The window wraps midnight, so it is a union rather than a range. */
    /* The window wraps midnight when from > to, and is a plain range when
     * it does not — somebody who wants 01:00-07:00 gets what they asked. */
    return (s_quiet_from_sec > s_quiet_to_sec)
        ? (tod >= s_quiet_from_sec || tod < s_quiet_to_sec)
        : (tod >= s_quiet_from_sec && tod < s_quiet_to_sec);
}

/*
 * Waking seconds from the RTC origin to `t` — the primitive everything else is
 * built from.
 *
 * Closed form rather than a loop over days, because the gap being measured can
 * be a week: the pet ages across being switched off, and iterating a week of
 * days on every tick to save a division would be a poor trade.
 *
 *   f(t) = whole_days(t) * 12h + however far into today's waking window t is
 *
 * Then the waking seconds BETWEEN two instants is just f(t1) - f(t0), which is
 * correct across any number of nights without special cases.
 */
static int64_t sim_waking_since_origin(int64_t t)
{
    const int64_t shifted = t + s_state.tod_offset;
    const int64_t day     = shifted / 86400;
    int32_t       tod     = (int32_t)(shifted % 86400);

    /* The waking day starts when quiet hours END. Configurable now, so it is
     * read rather than assumed — this was SIM_QUIET_TO_SEC, i.e. 09:00. */
    int32_t into_window = tod - s_quiet_to_sec;
    if (into_window < 0)                 into_window = 0;
    const int32_t waking_per_day = sim_waking_per_day();
    if (into_window > waking_per_day) into_window = waking_per_day;

    return day * waking_per_day + into_window;
}

/*
 * Elapsed time that counts, between two RTC readings.
 *
 * Falls back to plain elapsed time whenever the clock is unknown, so a pet that
 * has never met a phone behaves exactly as it always did.
 */
static int64_t sim_waking_between(int64_t from, int64_t to)
{
    if (!s_state.tod_known || to <= from) {
        return to - from;
    }
    return sim_waking_since_origin(to) - sim_waking_since_origin(from);
}

static bool sim_apply_elapsed(void)
{
    int64_t now = 0;
    if (!pet_rtc_now(&now)) {
        return false;   /* no clock: better to stall than to guess */
    }

    if (s_state.epoch == 0 || now < s_state.epoch) {
        /* First run, or the clock went backwards because it was reset after a
         * flat battery. Either way the interval is unknowable, so start from
         * here rather than inventing one. */
        s_state.epoch = now;
        return false;
    }

    const uint32_t rate = sim_decay_seconds();
    /*
     * Waking seconds, not elapsed ones — quiet hours. A night spent switched
     * off contributes nothing, and so does a night spent sitting on a desk;
     * the pet cannot tell the difference and should not.
     */
    const int64_t elapsed = sim_waking_between(s_state.epoch, now);
    const uint32_t levels = (uint32_t)(elapsed / rate);
    if (levels == 0) {
        return false;
    }

    const uint8_t old_sat = s_state.satiety, old_hap = s_state.happiness;
    s_state.satiety   = decay_one(s_state.satiety, levels);
    s_state.happiness = decay_one(s_state.happiness, levels);
    /*
     * Advance by whole levels only, so the remainder carries into the next one
     * and decay does not drift slower than it should.
     *
     * WALK THE EPOCH FORWARD IN WALL TIME UNTIL THE WAKING DEBT IS PAID. The
     * obvious `epoch += levels * rate` is wrong once nights are skipped: those
     * seconds are wall-clock seconds, and adding them to an epoch that sat
     * across a night would swallow the night twice. Searching for the instant
     * that owes exactly this many waking seconds is the honest inverse, and it
     * degenerates to the old arithmetic when the clock is unknown.
     */
    const int64_t owed = (int64_t)levels * rate;
    if (!s_state.tod_known) {
        s_state.epoch += owed;
    } else {
        int64_t lo = s_state.epoch, hi = now;
        while (lo < hi) {
            const int64_t mid = lo + (hi - lo) / 2;
            if (sim_waking_between(s_state.epoch, mid) < owed) lo = mid + 1;
            else hi = mid;
        }
        s_state.epoch = lo;
    }

    if (s_state.satiety != old_sat || s_state.happiness != old_hap) {
        ESP_LOGI(TAG, "decayed %lu level(s): satiety %u->%u, happiness %u->%u",
                 (unsigned long)levels, old_sat, s_state.satiety, old_hap, s_state.happiness);
        return true;
    }
    return false;   /* already at the floor */
}

/* --- sickness (phase 4) ---------------------------------------------------
 *
 * DELIBERATELY NOT PERSISTED, unlike the scores. Sickness is a live report
 * about what the user is doing right now, and the phone re-asserts it every
 * minute — so a pet that came back from a reboot still ill would be ill because
 * of a scrolling session that ended hours ago. The *damage* persists, because
 * the scores it drained do; the condition does not. A pet that reboots mid-
 * session is sick again within a minute, from a fresh report rather than a
 * remembered one.
 */
static bool    s_sick;
static int64_t s_sick_epoch;        /* RTC seconds; when the current drain level started */
static int64_t s_sick_seen_us;      /* uptime of the last report, for the stale timeout */
/* When THIS illness began, which s_sick_epoch cannot say: that one is advanced
 * every time a drain level is taken, so it measures the level rather than the
 * illness. Only meaningful while s_sick, and stamped in the one place sickness
 * starts — every other writer of s_sick only ever clears it, so there is nothing
 * to keep in step. For the teaching hint below. */
static int64_t s_sick_since;

bool pet_sim_is_sick(void)
{
    return s_sick;
}

static void sim_clear_sickness(void)
{
    s_sick = false;
    s_sick_epoch = 0;
}

void pet_sim_set_screen_overuse(bool overusing)
{
    s_sick_seen_us = esp_timer_get_time();

    if (overusing == s_sick) {
        return;                     /* the common case: the level is unchanged */
    }
    s_sick = overusing;

    if (overusing) {
        /* Start the drain clock from now, so the first sick level costs a full
         * SIM_SICK_DRAIN_SECONDS rather than however long ago the last session
         * happened to be. */
        if (!pet_rtc_now(&s_sick_epoch)) {
            s_sick_epoch = 0;
        }
        s_sick_since = s_sick_epoch;
        ESP_LOGW(TAG, "sick - the user is over their screen-time threshold");
    } else {
        ESP_LOGI(TAG, "recovered - screen time back under the threshold");
    }

    pet_set_mood(pet_sim_baseline_mood());
    pet_ble_notify_condition();
}

void pet_sim_cure(void)
{
    if (!s_sick) {
        return;
    }
    /*
     * The tap cure, and it is worth knowing exactly what it does and does not
     * do. It clears the pet's condition; it does not tell the phone anything.
     * So if the user is still in the app, the next usage poll reports overuse
     * again and the pet falls ill within the minute.
     *
     * That is the point rather than a leak. DESIGN.md §1 records the one way
     * this mechanic can quietly become pointless — a cure that works while you
     * keep scrolling lets the consequence be dismissed without the behaviour
     * changing. Here, curing a pet without putting the phone down buys about a
     * minute, and putting the phone down is what makes it stick.
     *
     * The drain clock restarts, so the tap does buy the pet a fresh
     * SIM_SICK_DRAIN_SECONDS before the next level goes. Tapping is not
     * nothing; it is just not an escape.
     */
    ESP_LOGI(TAG, "cured by a tap - but the phone still decides if it comes back");
    s_sick = false;
    pet_set_mood(pet_sim_baseline_mood());
    pet_ble_notify_condition();
}

/*
 * The extra drain sickness causes, on top of the ordinary decay in
 * sim_apply_elapsed. Same whole-level-with-remainder arithmetic, for the same
 * reason: advancing the epoch by whole levels only keeps the rate honest
 * instead of losing the remainder on every tick.
 *
 * Returns true when a score actually moved.
 */
static bool sim_apply_sickness(int64_t now)
{
    if (!s_sick) {
        return false;
    }

    /* The stale timeout. Checked here rather than in its own place because this
     * runs on every tick and nothing else needs to know. */
    if ((esp_timer_get_time() - s_sick_seen_us) / 1000 > SIM_SICK_STALE_MS) {
        ESP_LOGW(TAG, "no screen-time report for %d s - assuming the phone is gone",
                 PET_SCREEN_STALE_HINT_SEC);
        s_sick = false;
        pet_set_mood(pet_sim_baseline_mood());
        pet_ble_notify_condition();
        return true;
    }

    if (s_sick_epoch == 0 || now < s_sick_epoch) {
        s_sick_epoch = now;         /* no clock when it started, or it moved back */
        return false;
    }

    const uint32_t levels = (uint32_t)((now - s_sick_epoch) / SIM_SICK_DRAIN_SECONDS);
    if (levels == 0) {
        return false;
    }
    s_sick_epoch += (int64_t)levels * SIM_SICK_DRAIN_SECONDS;

    const uint8_t old_sat = s_state.satiety, old_hap = s_state.happiness;
    s_state.satiety   = decay_one(s_state.satiety, levels);
    s_state.happiness = decay_one(s_state.happiness, levels);

    if (s_state.satiety != old_sat || s_state.happiness != old_hap) {
        ESP_LOGW(TAG, "sick drain %lu level(s): satiety %u->%u, happiness %u->%u",
                 (unsigned long)levels, old_sat, s_state.satiety, old_hap, s_state.happiness);
        return true;
    }
    return false;
}

uint8_t pet_sim_baseline_mood(void)
{
    /*
     * Dead outranks everything — DESIGN.md §1's ranking starts there, and a
     * corpse with an opinion about lunch is not a design, it is a bug.
     */
    if (s_state.dead) {
        return PET_MOOD_DEAD;
    }

    /*
     * Sick outranks the scores. DESIGN.md §1 ranks dead > sick > a transient
     * expression > the baseline, and the reason sick sits above the scores is
     * that it is the thing the user can act on: a pet that looks merely sad
     * while it is being made ill has not told them anything they can use.
     */
    if (s_sick) {
        return PET_MOOD_SICK;
    }
    /*
     * The face comes from the *sum*, 0-8, across three expressions. With only
     * three faces available the face cannot say *which* need is unmet, so the
     * smoother gradient is worth more than picking a culprit.
     *
     * The floor is the exception, and it exists because the sum alone would let
     * satiety 0 with happiness 4 total 4 and show a contented face on a starving
     * pet. A pet at zero on any need never smiles.
     */
    if (s_state.satiety == 0 || s_state.happiness == 0) {
        return PET_MOOD_SAD;
    }
    const uint8_t total = (uint8_t)(s_state.satiety + s_state.happiness);
    if (total <= 2) return PET_MOOD_SAD;
    if (total <= 5) return PET_MOOD_NEUTRAL;
    return PET_MOOD_HAPPY;
}

void pet_sim_get(uint8_t *satiety, uint8_t *happiness)
{
    if (satiety)   *satiety   = s_state.satiety;
    if (happiness) *happiness = s_state.happiness;
}

/* Shared by feeding and playing: raise a score, persist, and show the result. */
/*
 * Tell the phone that care was offered and declined.
 *
 * `what` is the same literal sim_raise() logs with, so the two cannot disagree
 * about which action this was — there is one string, used for both.
 */
static void sim_notify_declined(const char *what, uint8_t why)
{
    const uint8_t p[2] = {
        (uint8_t)(strcmp(what, "fed") == 0 ? PET_CARE_FEED : PET_CARE_PLAY),
        why,
    };
    pet_ble_notify(PET_EVT_CARE_NO, p, sizeof(p));
}

static void sim_raise(uint8_t *score, int64_t *last_us, const char *what)
{
    const int64_t now_us = esp_timer_get_time();
    const int64_t since_ms = (now_us - *last_us) / 1000;

    /*
     * A score at 0 can ALWAYS be raised, cooldown or not.
     *
     * The cooldown exists to stop a mis-read gesture topping a healthy pet up to
     * full, and to make tending it take a moment. Neither applies to a pet that
     * has run out and is calling for help — and a pet that asks for food and
     * then refuses it is simply broken.
     *
     * The result is the behaviour you want anyway: an emergency can be answered
     * the instant it happens, and the measured pace only applies to topping up
     * from there. Note this deliberately does not key off the *call* state,
     * because a call goes quiet once its care mistake has accrued while the
     * score stays 0 — the pet is still starving and must still be feedable.
     */
    const bool urgent = (*score == 0);

    /* Nothing reaches a dead pet. The gesture is not refused with a cooldown
     * message either — there is no cooldown, there is no pet. Reset is the only
     * thing that does anything from here, and it is deliberately a separate,
     * explicit act rather than something a hopeful double tap stumbles into. */
    if (s_state.dead) {
        ESP_LOGI(TAG, "%s ignored - the pet is dead", what);
        sim_notify_declined(what, PET_CARE_NO_DEAD);
        return;
    }

    if (!urgent && *last_us != 0 && since_ms < SIM_ACTION_COOLDOWN_MS) {
        ESP_LOGI(TAG, "%s ignored - %lld s of cooldown left", what,
                 (long long)((SIM_ACTION_COOLDOWN_MS - since_ms) / 1000));
        sim_notify_declined(what, PET_CARE_NO_COOLDOWN);
        return;
    }
    if (*score >= SIM_MAX_SCORE) {
        ESP_LOGI(TAG, "%s ignored - already full", what);
        sim_notify_declined(what, PET_CARE_NO_FULL);
        return;
    }
    *last_us = now_us;
    (*score)++;
    sim_save();
    ESP_LOGI(TAG, "%s -> satiety %u, happiness %u, face %u",
             what, s_state.satiety, s_state.happiness, pet_sim_baseline_mood());
    pet_set_mood(pet_sim_baseline_mood());
    pet_ble_notify_condition();
}

static int64_t s_last_feed_us;
static int64_t s_last_play_us;

void pet_sim_feed(void) { sim_raise(&s_state.satiety,   &s_last_feed_us, "fed");    }
void pet_sim_play(void) { sim_raise(&s_state.happiness, &s_last_play_us, "played"); }

void pet_sim_cooldowns(int32_t *feed_ms, int32_t *play_ms)
{
    const int64_t now_us = esp_timer_get_time();
    /* Report 0 whenever the score is 0, matching sim_raise's urgent case — a HUD
     * showing "feed 40s" on a pet that will in fact accept food is a lie, and
     * this one is exactly how the refusing-to-be-fed bug was noticed. */
    if (feed_ms != NULL) {
        const int64_t left = (s_state.satiety == 0 || s_last_feed_us == 0) ? 0
            : SIM_ACTION_COOLDOWN_MS - (now_us - s_last_feed_us) / 1000;
        *feed_ms = (int32_t)(left > 0 ? left : 0);
    }
    if (play_ms != NULL) {
        const int64_t left = (s_state.happiness == 0 || s_last_play_us == 0) ? 0
            : SIM_ACTION_COOLDOWN_MS - (now_us - s_last_play_us) / 1000;
        *play_ms = (int32_t)(left > 0 ? left : 0);
    }
}

/* Silences the *current* call only; the mistake still accrues. Cleared whenever
 * a new call starts, so it can never mute the pet permanently. */
static volatile bool s_call_silenced;

void pet_sim_silence_call(void)
{
    if (pet_sim_is_calling()) {
        s_call_silenced = true;
        ESP_LOGI(TAG, "call silenced - the care mistake still counts");
    }
}

bool pet_sim_is_calling(void)
{
    return s_state.call_epoch[SCORE_SATIETY] != 0 ||
           s_state.call_epoch[SCORE_HAPPINESS] != 0;
}

/*
 * Whether the pet is still ASKING, as opposed to merely having a call on record.
 *
 * The difference is the -1: sim_service_calls stamps that once the care window
 * has expired and the mistake has accrued, and from then on the pet is silent —
 * the score stays 0 and the face stays sad, but it has stopped asking. Both
 * states are "calling" to pet_sim_is_calling(), which is right for the wire flag
 * and for the phone's care card (the pet still needs feeding), and wrong for
 * anything that should follow the *noise*.
 *
 * IT COST A NIGHT OF SCREEN. sleep_tick() kept the pet awake on
 * pet_sim_is_calling(), so a score at 0 pinned the panel at full brightness with
 * three animations running until somebody fed it — which for a neglected pet is
 * most of its life, and exactly the case sleeping exists for. The comment above
 * that early return claimed the pet "sleeps again on its own once the call is
 * answered or counted"; the counted half had never been true. Silent, as ever:
 * an early return that logs nothing, under a confident note saying otherwise.
 */
bool pet_sim_is_calling_aloud(void)
{
    return s_state.call_epoch[SCORE_SATIETY] > 0 ||
           s_state.call_epoch[SCORE_HAPPINESS] > 0;
}

/*
 * WHICH GESTURE THE PET SHOULD BE TEACHING, if any. DESIGN.md §5.5.
 *
 * Five of the pet's six inputs are undiscoverable, which §5.5 calls the biggest
 * UX defect in the product: a pet that cannot be fed by someone who was not told
 * how to feed it is not a pet. The decision taken 2026-08-04 is that **the pet
 * teaches, and nothing else does** — no phone-side manual — and that it teaches
 * on **any prolonged unmet need**, not only after a call has gone unanswered.
 *
 * Prolonged, because the trigger has to be evidence the user does not know. A
 * pet that shows you how to feed it the instant it gets hungry is teaching
 * somebody who may simply not have got to it yet, and reads as nagging; one that
 * has been asking for a quarter of an hour with nothing happening is a better
 * guess at genuine ignorance. That is the whole justification for the delay, and
 * SIM_TEACH_AFTER_SEC is the first number to move if it turns out to be wrong in
 * either direction.
 *
 * HOW THE DURATION IS KNOWN WITHOUT NEW STATE. call_epoch already holds when
 * each score hit 0, and its -1 sentinel means the care window has expired — so a
 * -1 is by definition a need that has stood longer than SIM_CARE_WINDOW_SEC, and
 * as long as the teaching delay is shorter than that window, -1 always qualifies.
 * The static assert below is what keeps that reasoning true; nothing is
 * persisted, so no save format changes and no pet needs migrating.
 *
 * ONE GESTURE AT A TIME, hunger first. Showing two demonstrations at once
 * teaches neither, and hunger wins because it is the score the pet calls about
 * first in practice.
 *
 * SICKNESS TEACHES THE SAME DOUBLE TAP, because the cure *is* the double tap.
 * Worth knowing it is the weakest of the three triggers: sickness means the user
 * is looking at their phone right now, so it is the case least likely to have
 * anyone watching the pet.
 *
 * WHAT IS DELIBERATELY NOT TAUGHT HERE: the three button gestures — PWR to
 * silence, PWR-hold for off, BOOT-hold to reset. They are undiscoverable too,
 * but they cannot be *demonstrated* on a screen the way a tap and a shake can,
 * and inventing a picture of a button press is the onboarding art §5.6 defers.
 * Reset also has a home already: §5.3 gives it to the phone, on the one screen
 * that says the pet has died. Naming them here so their absence reads as a
 * decision rather than an oversight.
 */
#if PET_SIM_FAST
#define SIM_TEACH_AFTER_SEC       8
#else
#define SIM_TEACH_AFTER_SEC     900     /* a quarter of an hour */
#endif

_Static_assert(SIM_TEACH_AFTER_SEC < SIM_CARE_WINDOW_SEC,
               "the -1 shortcut in pet_sim_teach_hint assumes a counted call has "
               "already outlasted the teaching delay");

/* True when score `i` has stood empty for at least SIM_TEACH_AFTER_SEC. */
static bool sim_need_is_prolonged(int i, int64_t now)
{
    /*
     * THE SCORE ANSWERS "IS THERE A NEED", NOT call_epoch. Fixed 2026-08-06.
     *
     * This used to read `call_epoch == 0` as "the score is not empty", which is
     * true only between simulation ticks. `call_epoch` is bookkeeping maintained
     * by sim_service_calls, and that runs on SIM_TICK_MS — sixty seconds. The
     * teaching hint is polled once a SECOND.
     *
     * So feeding a pet raised its satiety instantly and left the demonstration
     * on screen for up to a minute afterwards, still telling the user to do the
     * thing they had just done. Reported as "the teaching gesture is not going
     * away after I tap, and the tap definitely registered" — which was an exact
     * description of a fast reader looking at slow state.
     *
     * The score is authoritative and updates the moment the pet is fed, so it
     * decides whether a need exists. call_epoch is still the right source for
     * how LONG it has existed, which is all it was ever good for here.
     *
     * (It looked correct on 2026-08-05 only because the tick happened to land a
     * second after the feed. A one-in-sixty coincidence reading as a pass.)
     */
    if (*sim_score(i) > 0) {
        return false;           /* fed or played with: no need, no lesson */
    }

    const int64_t started = s_state.call_epoch[i];
    if (started == 0) {
        return false;           /* empty, but the tick has not noticed yet */
    }
    if (started < 0) {
        return true;            /* counted, so older than the care window */
    }
    return (now - started) >= SIM_TEACH_AFTER_SEC;
}

uint8_t pet_sim_teach_hint(void)
{
    /*
     * A dead pet teaches nothing. Every gesture below is care, and none of them
     * do anything to a corpse — demonstrating a feed to somebody whose pet has
     * died would be the cruellest possible time to be helpful.
     */
    if (s_state.dead) {
        return PET_TEACH_NONE;
    }

    int64_t now = 0;
    if (!pet_rtc_now(&now)) {
        return PET_TEACH_NONE;  /* no clock, so no idea how long anything has stood */
    }

    if (s_sick && s_sick_since > 0 && (now - s_sick_since) >= SIM_TEACH_AFTER_SEC) {
        return PET_TEACH_FEED;  /* the cure is the same double tap */
    }
    if (sim_need_is_prolonged(SCORE_SATIETY, now)) {
        return PET_TEACH_FEED;
    }
    if (sim_need_is_prolonged(SCORE_HAPPINESS, now)) {
        return PET_TEACH_PLAY;
    }
    return PET_TEACH_NONE;
}

uint16_t pet_sim_care_mistakes(void)
{
    return s_state.care_mistakes;
}

static uint8_t *sim_score(int which)
{
    return which == SCORE_SATIETY ? &s_state.satiety : &s_state.happiness;
}

/*
 * Start, sustain and close calls. Runs on the tick, so SIM_TICK_MS is the
 * granularity of both the repeat and the window — they cannot be finer than it.
 *
 * Returns true when something was persisted-worthy.
 */
static bool sim_service_calls(int64_t now)
{
    static const char *name[SCORE_COUNT] = { "satiety", "happiness" };
    bool dirty = false;
    bool should_beep = false;

    for (int i = 0; i < SCORE_COUNT; i++) {
        const uint8_t score = *sim_score(i);
        int64_t *started = &s_state.call_epoch[i];

        if (score > 0) {
            if (*started != 0) {
                ESP_LOGI(TAG, "%s call answered", name[i]);
                *started = 0;
                dirty = true;
            }
            continue;
        }

        if (*started == 0) {
            /* Score just hit zero: the pet starts asking. -1 marks a call that
             * has already cost a mistake, so it must not re-arm from that. */
            *started = now;
            s_call_silenced = false;
            ESP_LOGI(TAG, "%s empty - calling", name[i]);
            dirty = true;
        }

        if (*started < 0) {
            continue;      /* already counted; stays quiet until answered */
        }

        if (now - *started >= SIM_CARE_WINDOW_SEC) {
            s_state.care_mistakes++;
            *started = -1;
            ESP_LOGW(TAG, "%s unanswered for %d s - care mistake #%u",
                     name[i], SIM_CARE_WINDOW_SEC, s_state.care_mistakes);
            dirty = true;
            continue;
        }
        should_beep = true;
    }

    /*
     * SILENT DURING QUIET HOURS. A pet that beeps at 3am is not asking for
     * help, it is waking somebody up - and since scores do not decay
     * overnight, a call still open at 21:00 started in the evening and can
     * safely wait until morning.
     *
     * The care-mistake window deliberately keeps running (DESIGN.md 1), so a
     * call that started at 20:30 can still cost a mistake at 21:30 while the
     * pet is silent. That was a decision, not an oversight.
     */
    if (should_beep && pet_sim_is_quiet_hours()) {
        should_beep = false;
    }
    if (should_beep && !s_call_silenced && !s_state.dead) {
        pet_spk_beep();
    }
    return dirty;
}

static void pet_sim_task(void *arg)
{
    (void)arg;
    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(SIM_TICK_MS));

        int64_t now = 0;
        const bool have_clock = pet_rtc_now(&now);

        /*
         * A dead pet does not decay, call, fall ill or age. The state is held
         * exactly as it was at death so the save is a record of how it ended,
         * and so nothing can quietly change while it is being shown as dead.
         */
        if (s_state.dead) {
            continue;
        }

        /*
         * Announce the stage when it changes. Nothing else does: the stage is
         * derived from the clock rather than stored, so it advances with no
         * event anywhere — and the only other place it shows is the debug HUD,
         * which cannot be read from a serial log. The decay rate goes with it
         * because that is the part the stage actually changes.
         */
        static uint8_t last_stage = 0xFF;
        const uint8_t stage_now = pet_sim_stage();
        if (stage_now != last_stage) {
            static const char *names[] = { "egg", "child", "teen", "adult" };
            ESP_LOGW(TAG, "stage -> %s (age %lld s, now %lu s per level)",
                     names[stage_now], (long long)pet_sim_age_seconds(),
                     (unsigned long)sim_decay_seconds());
            last_stage = stage_now;
        }

        bool dirty = sim_apply_elapsed();

        /*
         * QUIET HOURS ARE A CONDITION CHANGE, and nothing else was going to say
         * so. Entering them is a time-of-day boundary rather than a change to
         * any score, so without this the phone would keep announcing into the
         * evening until something unrelated happened to make the pet notify.
         */
        {
            static int8_t was_quiet = -1;   /* -1: not yet known */
            const int8_t now_quiet = pet_sim_is_quiet_hours() ? 1 : 0;
            if (now_quiet != was_quiet) {
                if (was_quiet != -1) {
                    ESP_LOGI(TAG, "quiet hours %s", now_quiet ? "begin" : "end");
                }
                was_quiet = now_quiet;
                dirty = true;
            }
        }

        /* Sickness drains on top of the ordinary decay, and before calls are
         * serviced — so a score the illness just emptied starts asking on this
         * tick rather than the next one. */
        if (have_clock && sim_apply_sickness(now)) {
            dirty = true;
        }

        /* Calls are serviced after decay, so a score that just reached zero
         * starts asking on the same tick rather than a minute later. */
        if (have_clock && sim_service_calls(now)) {
            dirty = true;
        }

        /* Death is judged last, on the scores this tick actually produced. A
         * pet that dies here still called for help first, which matters: the
         * user got every warning the system had before it ran out. */
        if (have_clock && sim_service_death(now)) {
            dirty = true;
        }
        if (dirty) {
            sim_save();
            pet_set_mood(pet_sim_baseline_mood());
            /* The pet owns this state; the phone is told, not asked. */
            pet_ble_notify_condition();
        }
    }
}

void pet_sim_start(void)
{
    /* Before sim_load(): the decay maths reads the waking day, which the
     * window defines, so restoring it afterwards would age the pet by the
     * default window for the first tick after every reboot. */
    quiet_hours_restore();
    sim_load();

    /*
     * A lost clock means the interval since the last save is unknowable. Rather
     * than guess, keep the scores where they are and restart the count from now:
     * the pet skips the neglect it cannot measure, which is the forgiving error
     * to make.
     */
    if (pet_rtc_time_was_lost()) {
        ESP_LOGW(TAG, "clock was lost - not ageing across the gap");
        int64_t now = 0;
        if (pet_rtc_now(&now)) {
            s_state.epoch = now;
        }
    }

    /*
     * Give a pet with no birth date one. That is either a brand new pet, or one
     * saved before phase 6 existed — and in both cases the only honest answer is
     * "born now", because nothing ever recorded when it actually started. See
     * the migration note in sim_load().
     */
    bool born_now = false;
    if (s_state.born_epoch == 0) {
        int64_t now = 0;
        if (pet_rtc_now(&now)) {
            s_state.born_epoch = now;
            born_now = true;
        }
    }

    const bool changed = sim_apply_elapsed();
    if (changed || born_now || !s_loaded) {
        sim_save();
    }

    static const char *stage_name[] = { "egg", "child", "teen", "adult" };
    ESP_LOGI(TAG, "%s pet - satiety %u, happiness %u, face %u, %s (age %lld h)%s%s",
             s_loaded ? "restored" : "new", s_state.satiety, s_state.happiness,
             pet_sim_baseline_mood(), stage_name[pet_sim_stage()],
             (long long)(pet_sim_age_seconds() / 3600),
             s_state.dead ? " DEAD" : "", PET_SIM_FAST ? " [FAST]" : "");

    if (xTaskCreate(pet_sim_task, "pet_sim", 3072, NULL, 2, NULL) != pdPASS) {
        ESP_LOGE(TAG, "task alloc failed - the pet will not age");
        return;
    }
    pet_set_mood(pet_sim_baseline_mood());
}
