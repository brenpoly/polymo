/*
 * DigitalPet — PCF85063 real-time clock.
 *
 * The pet ages whether or not it is switched on, so the simulation needs
 * elapsed wall time rather than uptime. `esp_timer` resets to zero on every
 * boot; this chip does not, because the AXP2101 keeps it powered from the LiPo
 * even when the device is off at the PWR button.
 *
 * What is wanted here is *elapsed seconds*, not the correct date. Nothing shows
 * a clock, and in phase 1 there is no phone to ask what time it is — so a fresh
 * or stopped oscillator is simply started at a fixed epoch and counted from
 * there. Only the difference between two readings matters.
 *
 * The chip is not in the BSP. Scanning the I2C bus found it at 0x51.
 */
#include <string.h>

#include "esp_log.h"
#include "driver/i2c_master.h"
#include "bsp/esp-bsp.h"
#include "pet.h"

static const char *TAG = "pet_rtc";

#define RTC_ADDR       0x51
#define RTC_I2C_HZ     100000

/* PCF85063 register map — only the time block is used. */
#define RTC_REG_CTRL1   0x00
#define RTC_REG_SECONDS 0x04   /* bit 7 is OS, the oscillator-stop flag */

/* Bit 7 of the seconds register. Set by the chip whenever the oscillator has
 * stopped, which means the time it is holding is not trustworthy. It is the
 * only warning available that a flat battery lost us the clock. */
#define RTC_OS_FLAG     0x80

static i2c_master_dev_handle_t s_dev;
static bool s_time_was_lost;

static uint8_t bcd_to_bin(uint8_t v) { return (uint8_t)((v >> 4) * 10 + (v & 0x0F)); }
static uint8_t bin_to_bcd(uint8_t v) { return (uint8_t)(((v / 10) << 4) | (v % 10)); }

static esp_err_t rtc_read(uint8_t reg, uint8_t *out, size_t len)
{
    if (s_dev == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    return i2c_master_transmit_receive(s_dev, &reg, 1, out, len, 200);
}

static esp_err_t rtc_write(uint8_t reg, const uint8_t *data, size_t len)
{
    if (s_dev == NULL || len > 8) {
        return ESP_ERR_INVALID_STATE;
    }
    uint8_t buf[9];
    buf[0] = reg;
    memcpy(&buf[1], data, len);
    return i2c_master_transmit(s_dev, buf, len + 1, 200);
}

/*
 * Days since 1970-01-01 for a civil date, by Howard Hinnant's algorithm.
 *
 * Used rather than mktime() because that drags in timezone handling for a
 * calculation whose only purpose is to difference two readings. Correct for any
 * date this pet will ever see.
 */
static int64_t days_from_civil(int y, unsigned m, unsigned d)
{
    y -= m <= 2;
    const int era = (y >= 0 ? y : y - 399) / 400;
    const unsigned yoe = (unsigned)(y - era * 400);
    const unsigned doy = (153u * (m + (m > 2 ? -3 : 9)) + 2u) / 5u + d - 1;
    const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return (int64_t)era * 146097 + (int64_t)doe - 719468;
}

/* Start the oscillator at a fixed, arbitrary epoch. Only differences matter. */
static void rtc_set_baseline(void)
{
    /* 2026-01-01 00:00:00, weekday field left at 0 — nothing reads it. */
    const uint8_t t[7] = {
        bin_to_bcd(0),   /* seconds, and clearing bit 7 clears OS */
        bin_to_bcd(0),   /* minutes */
        bin_to_bcd(0),   /* hours   */
        bin_to_bcd(1),   /* day     */
        0,               /* weekday */
        bin_to_bcd(1),   /* month   */
        bin_to_bcd(26),  /* year, 2000-based */
    };
    if (rtc_write(RTC_REG_SECONDS, t, sizeof(t)) != ESP_OK) {
        ESP_LOGE(TAG, "could not set baseline time");
    }
}

bool pet_rtc_start(void)
{
    if (s_dev != NULL) {
        return true;
    }

    i2c_master_bus_handle_t bus = bsp_i2c_get_handle();
    if (bus == NULL && bsp_i2c_init() == ESP_OK) {
        bus = bsp_i2c_get_handle();
    }
    if (bus == NULL) {
        ESP_LOGE(TAG, "no I2C bus - the pet will not age");
        return false;
    }

    i2c_device_config_t cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = RTC_ADDR,
        .scl_speed_hz    = RTC_I2C_HZ,
    };
    if (i2c_master_bus_add_device(bus, &cfg, &s_dev) != ESP_OK) {
        ESP_LOGE(TAG, "add_device failed - the pet will not age");
        return false;
    }

    uint8_t secs = 0;
    if (rtc_read(RTC_REG_SECONDS, &secs, 1) != ESP_OK) {
        ESP_LOGE(TAG, "not responding - the pet will not age");
        i2c_master_bus_rm_device(s_dev);
        s_dev = NULL;
        return false;
    }

    /*
     * A set OS flag means the oscillator stopped, so the elapsed time since the
     * last save is unknowable. Say so loudly and start again from a baseline —
     * the alternative is differencing against garbage and ageing the pet years
     * in one boot, which is the failure this flag exists to prevent.
     */
    s_time_was_lost = (secs & RTC_OS_FLAG) != 0;
    if (s_time_was_lost) {
        ESP_LOGW(TAG, "oscillator had stopped - time lost, starting from baseline");
        rtc_set_baseline();
    }

    int64_t now = 0;
    pet_rtc_now(&now);
    ESP_LOGI(TAG, "ready - now %lld, time_was_lost=%d", (long long)now, s_time_was_lost);
    return true;
}

bool pet_rtc_time_was_lost(void)
{
    return s_time_was_lost;
}

bool pet_rtc_now(int64_t *out_epoch_sec)
{
    if (out_epoch_sec == NULL) {
        return false;
    }
    uint8_t r[7];
    if (rtc_read(RTC_REG_SECONDS, r, sizeof(r)) != ESP_OK) {
        return false;
    }

    const unsigned sec  = bcd_to_bin(r[0] & 0x7F);
    const unsigned min  = bcd_to_bin(r[1] & 0x7F);
    const unsigned hour = bcd_to_bin(r[2] & 0x3F);
    const unsigned day  = bcd_to_bin(r[3] & 0x3F);
    const unsigned mon  = bcd_to_bin(r[5] & 0x1F);
    const unsigned year = bcd_to_bin(r[6]);

    if (mon < 1 || mon > 12 || day < 1 || day > 31 || hour > 23 || min > 59 || sec > 59) {
        ESP_LOGW(TAG, "implausible time %02u-%02u-%02u %02u:%02u:%02u",
                 year, mon, day, hour, min, sec);
        return false;
    }

    const int64_t days = days_from_civil(2000 + (int)year, mon, day);
    *out_epoch_sec = days * 86400 + (int64_t)hour * 3600 + (int64_t)min * 60 + sec;
    return true;
}
