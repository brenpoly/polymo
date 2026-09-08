/*
 * DigitalPet — QMI8658 accelerometer, used only to notice a shake.
 *
 * Shaking the pet feeds it. Nothing here needs orientation, gyro or step
 * counting, so only the accelerometer is enabled and only its magnitude is
 * looked at: a shake is a departure from the ~1 g the pet feels sitting still,
 * in any direction.
 *
 * The chip is not in the BSP. Scanning the I2C bus found it at 0x6B, and
 * WHO_AM_I returning 0x05 identified it.
 *
 * The threshold is in raw LSB rather than g, deliberately. Converting would mean
 * trusting a full-scale setting that has not been verified on this board, and
 * the number that matters is "how hard does a person actually shake it", which
 * is measured, not derived. SHAKE_LSB is therefore tuned against the peaks this
 * file logs — see pet_imu_start's note on PET_IMU_TUNE.
 */
#include <math.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "driver/i2c_master.h"
#include "bsp/esp-bsp.h"
#include "pet.h"

#ifndef PET_IMU_TUNE
#define PET_IMU_TUNE 0
#endif

static const char *TAG = "pet_imu";

#define IMU_ADDR      0x6B
#define IMU_I2C_HZ    400000

#define IMU_REG_WHOAMI 0x00
#define IMU_REG_CTRL1  0x02
#define IMU_REG_CTRL2  0x03
#define IMU_REG_CTRL7  0x08
#define IMU_REG_AX_L   0x35

#define IMU_WHOAMI_QMI8658 0x05

/* CTRL1: serial-interface address auto-increment, so a 6-byte burst read of the
 * accelerometer block works. Without it every axis needs its own transaction. */
#define IMU_CTRL1_ADDR_AI  0x40
/* CTRL2: accelerometer +-8 g at 235 Hz. Range is generous because a shake is a
 * large transient and clipping it would flatten exactly the peak being measured. */
#define IMU_CTRL2_ACC_CFG  0x24
/* CTRL7: accelerometer on, gyro off — the gyro is the larger power draw and
 * nothing here uses rotation. */
#define IMU_CTRL7_ACC_EN   0x01

/*
 * Poll rate. A shake is a sustained wobble of several hundred ms, so 20 Hz is
 * ample to catch one and costs one small I2C read per 50 ms.
 */
#define IMU_POLL_MS   50

/* One g of deviation from rest. Measured on this board: rest reads ~4003 LSB,
 * which confirms the +-8 g range above (32768/8 = 4096 per g), and the noise
 * floor sitting still is ~220. So this is about 18x noise — comfortably a
 * deliberate shake rather than a bump or the pet being picked up. */
#define SHAKE_LSB     4000

/*
 * A shake must be SUSTAINED, not a single spike.
 *
 * One threshold crossing was too easy to hit by accident — setting the pet down,
 * knocking the desk, picking it up.
 *
 * WAS 3000 ms, AND THREE SECONDS IS A LONG TIME TO SHAKE SOMETHING. Reported as
 * taking too long to play with the pet, which it was: the gesture is the whole
 * interaction, and one that outlasts the intention behind it stops feeling like
 * play.
 *
 * **The duration is what rejects accidents, not the amplitude.** A desk knock or
 * a pick-up passes SHAKE_LSB easily — a bump is well over 1 g — and is rejected
 * because it is a TRANSIENT: it exceeds the threshold for a few tens of
 * milliseconds and then sits quiet for far longer than SHAKE_GAP_MS, which
 * resets the hold. One second of *continuous* 1 g oscillation is still something
 * nobody does by accident at a desk.
 *
 * "Continuous" cannot mean every sample, because a shake is oscillatory and the
 * magnitude dips through rest at each direction change. SHAKE_GAP_MS is how long
 * the motion may fall below threshold before it counts as having stopped.
 *
 * **GAP CAME DOWN WITH HOLD, and had to.** At a 3000 ms hold a 500 ms tolerance
 * is a sixth of the gesture; at 1000 ms it would be half, so motion / quiet /
 * motion in 250 ms slices would have counted as a continuous shake. 300 ms is
 * still generous against the dips themselves: a vigorous shake turns around at
 * roughly 3–5 Hz, so dips arrive every 100–170 ms, and the IMU is polled every
 * 50 ms.
 *
 * THE FALSE POSITIVE TO WATCH FOR ON HARDWARE is not a knock — it is CARRIED
 * motion. A pet in a bag or a pocket can oscillate past 1 g for a second at a
 * time with no quiet gap, and that was comfortably outside a three-second hold.
 * If the pet starts reporting play it was never given, this is why, and the
 * answer is the hold rather than the threshold.
 */
#define SHAKE_HOLD_MS 1000
#define SHAKE_GAP_MS  300

/* After a shake registers, the motion has to actually stop before another can
 * begin — otherwise carrying on shaking would play with the pet on a timer.
 * (This comment said "feed" until 2026-08-11. Feeding is the double tap; the
 * shake has always been play. pet.h has the pairing.) */
#define SHAKE_REARM_MS 1000

static i2c_master_dev_handle_t s_dev;
static pet_imu_shake_cb_t s_on_shake;

/* For the debug HUD: the largest recent departure from rest, held briefly so a
 * shake stays legible on screen instead of flashing past in one frame, and how
 * far through the required hold the current shake has got. */
static volatile int32_t s_dbg_peak;
static volatile int64_t s_dbg_peak_us;
static volatile int32_t s_dbg_rest;
static volatile int32_t s_dbg_held_ms;
#define DBG_PEAK_HOLD_US 2000000

static esp_err_t imu_write(uint8_t reg, uint8_t val)
{
    const uint8_t buf[2] = { reg, val };
    return i2c_master_transmit(s_dev, buf, sizeof(buf), 200);
}

static esp_err_t imu_read(uint8_t reg, uint8_t *out, size_t len)
{
    return i2c_master_transmit_receive(s_dev, &reg, 1, out, len, 200);
}

/* Magnitude of the acceleration vector, in raw LSB. */
static bool imu_magnitude(int32_t *out)
{
    uint8_t r[6];
    if (imu_read(IMU_REG_AX_L, r, sizeof(r)) != ESP_OK) {
        return false;
    }
    const int16_t ax = (int16_t)((uint16_t)r[1] << 8 | r[0]);
    const int16_t ay = (int16_t)((uint16_t)r[3] << 8 | r[2]);
    const int16_t az = (int16_t)((uint16_t)r[5] << 8 | r[4]);
    *out = (int32_t)sqrtf((float)ax * ax + (float)ay * ay + (float)az * az);
    return true;
}

static void pet_imu_task(void *arg)
{
    (void)arg;

    /*
     * Rest is whatever the pet reads sitting still, which depends on the
     * full-scale setting. Measuring it at startup rather than assuming 1 g in
     * some assumed unit means the threshold stays meaningful if CTRL2 changes.
     */
    int32_t rest = 0;
    int used = 0;
    for (int i = 0; i < 20; i++) {
        int32_t m;
        if (imu_magnitude(&m)) { rest += m; used++; }
        vTaskDelay(pdMS_TO_TICKS(20));
    }
    rest = used ? rest / used : 0;
    s_dbg_rest = rest;
    ESP_LOGI(TAG, "at rest %ld LSB (shake threshold +-%d)", (long)rest, SHAKE_LSB);

    int64_t last_shake_us = 0;
    int64_t shake_start_us = 0;   /* when the current sustained shake began */
    int64_t last_above_us = 0;    /* last sample above threshold */
    /* The hardest this shake got, for the log line at the end of it. Reset with
     * the hold rather than accumulated forever — see the ESP_LOGI below. */
    int32_t hold_peak = 0;
#if PET_IMU_TUNE
    int32_t peak = 0;
    int64_t last_report_us = 0;
#endif

    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(IMU_POLL_MS));

        int32_t m;
        if (!imu_magnitude(&m)) {
            continue;
        }
        const int32_t delta = m > rest ? m - rest : rest - m;
        const int64_t now_us = esp_timer_get_time();

        if (delta > s_dbg_peak || now_us - s_dbg_peak_us > DBG_PEAK_HOLD_US) {
            s_dbg_peak = delta;
            s_dbg_peak_us = now_us;
        }

#if PET_IMU_TUNE
        /* Tuning aid: report the largest departure from rest each second, so
         * SHAKE_LSB can be set from what a person's shake actually produces
         * rather than from a datasheet. Build with -DPET_IMU_TUNE=1. */
        if (delta > peak) peak = delta;
        if (now_us - last_report_us > 1000000) {
            ESP_LOGW(TAG, "TUNE peak delta %ld LSB (rest %ld)", (long)peak, (long)rest);
            peak = 0;
            last_report_us = now_us;
        }
#endif

        if (delta >= SHAKE_LSB) {
            /* A gap longer than SHAKE_GAP_MS means the previous shake stopped,
             * so this is the start of a new one rather than a continuation. */
            if (shake_start_us == 0 || now_us - last_above_us > SHAKE_GAP_MS * 1000) {
                shake_start_us = now_us;
                hold_peak = 0;
            }
            last_above_us = now_us;
            if (delta > hold_peak) hold_peak = delta;
        } else if (shake_start_us != 0 && now_us - last_above_us > SHAKE_GAP_MS * 1000) {
            shake_start_us = 0;   /* motion stopped before the hold completed */
            hold_peak = 0;
        }

        const int32_t held_ms = shake_start_us == 0
                                ? 0 : (int32_t)((now_us - shake_start_us) / 1000);
        s_dbg_held_ms = held_ms;

        if (shake_start_us == 0 || held_ms < SHAKE_HOLD_MS) {
            continue;
        }
        if (now_us - last_shake_us < (SHAKE_HOLD_MS + SHAKE_REARM_MS) * 1000) {
            continue;
        }
        last_shake_us = now_us;
        shake_start_us = 0;       /* require the motion to stop and restart */
        s_dbg_held_ms = 0;
        const int32_t peak_ms = hold_peak;
        hold_peak = 0;

        /*
         * THE PEAK, NOT THE INSTANTANEOUS READING.
         *
         * This printed `delta` until 2026-08-11, and `delta` at this moment is
         * whatever the accelerometer happened to read on the sample the hold
         * completed on — which is very often mid-dip, because a shake is
         * oscillatory. It logged "shake held 1000 ms (3056 LSB from rest)" for a
         * perfectly good shake, and 3056 is BELOW SHAKE_LSB: it reads as though
         * the threshold had been bypassed.
         *
         * The check above is time-based and runs every sample regardless of the
         * current magnitude, so that was never a bug — but the log said the one
         * thing guaranteed to send the next reader after a gate that is working.
         * The peak is the number that answers what the log is asked: was that a
         * vigorous shake or a marginal one.
         */
        ESP_LOGI(TAG, "shake held %d ms (peak %ld LSB from rest, threshold %d)",
                 SHAKE_HOLD_MS, (long)peak_ms, SHAKE_LSB);
        if (s_on_shake != NULL) {
            s_on_shake();
        }
    }
}

bool pet_imu_start(pet_imu_shake_cb_t on_shake)
{
    if (s_dev != NULL) {
        return true;
    }

    i2c_master_bus_handle_t bus = bsp_i2c_get_handle();
    if (bus == NULL && bsp_i2c_init() == ESP_OK) {
        bus = bsp_i2c_get_handle();
    }
    if (bus == NULL) {
        ESP_LOGE(TAG, "no I2C bus - shake disabled");
        return false;
    }

    i2c_device_config_t cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = IMU_ADDR,
        .scl_speed_hz    = IMU_I2C_HZ,
    };
    if (i2c_master_bus_add_device(bus, &cfg, &s_dev) != ESP_OK) {
        ESP_LOGE(TAG, "add_device failed - shake disabled");
        return false;
    }

    /* Identify before configuring: a wrong chip answering this address would
     * otherwise be silently written to. */
    uint8_t who = 0;
    if (imu_read(IMU_REG_WHOAMI, &who, 1) != ESP_OK || who != IMU_WHOAMI_QMI8658) {
        ESP_LOGE(TAG, "WHO_AM_I 0x%02X, expected 0x%02X - shake disabled",
                 who, IMU_WHOAMI_QMI8658);
        i2c_master_bus_rm_device(s_dev);
        s_dev = NULL;
        return false;
    }

    if (imu_write(IMU_REG_CTRL1, IMU_CTRL1_ADDR_AI) != ESP_OK ||
        imu_write(IMU_REG_CTRL2, IMU_CTRL2_ACC_CFG) != ESP_OK ||
        imu_write(IMU_REG_CTRL7, IMU_CTRL7_ACC_EN)  != ESP_OK) {
        ESP_LOGE(TAG, "configuration failed - shake disabled");
        i2c_master_bus_rm_device(s_dev);
        s_dev = NULL;
        return false;
    }

    s_on_shake = on_shake;

    /* Priority 2: below the UI, well below audio. Missing a poll costs nothing —
     * a shake lasts far longer than one interval. */
    if (xTaskCreate(pet_imu_task, "pet_imu", 3072, NULL, 2, NULL) != pdPASS) {
        ESP_LOGE(TAG, "task alloc failed - shake disabled");
        s_on_shake = NULL;
        return false;
    }

    ESP_LOGI(TAG, "ready - QMI8658, accelerometer only");
    return true;
}

void pet_imu_debug(int32_t *peak, int32_t *rest, int32_t *threshold,
                   int32_t *held_ms, int32_t *hold_target_ms)
{
    if (peak)           *peak           = s_dbg_peak;
    if (rest)           *rest           = s_dbg_rest;
    if (threshold)      *threshold      = SHAKE_LSB;
    if (held_ms)        *held_ms        = s_dbg_held_ms;
    if (hold_target_ms) *hold_target_ms = SHAKE_HOLD_MS;
}
