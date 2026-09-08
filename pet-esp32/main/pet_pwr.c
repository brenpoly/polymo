/*
 * DigitalPet — AXP2101 power management, and the physical buttons.
 *
 * The board has two side buttons, PWR and BOOT. The BSP declares
 * BSP_CAPS_BUTTONS 0 and knows about neither; Waveshare describe both as
 * customisable. They are not the same kind of thing:
 *
 *   BOOT  a plain GPIO (0 on this chip), readable directly and active-low.
 *   PWR   wired to the AXP2101's PWRKEY pin, NOT to a GPIO. Presses arrive as
 *         PMIC interrupt-status bits over I2C, and the PMIC may also act on a
 *         long press by itself depending on how it is configured.
 *
 * That second point is why this file starts as a probe rather than a driver:
 * what a long press already does is a property of the PMIC's current
 * configuration, and reading it is cheaper than assuming it.
 */
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "driver/gpio.h"
#include "driver/i2c_master.h"
#include "bsp/esp-bsp.h"
#include "pet.h"

static const char *TAG = "pet_pwr";

#define AXP_ADDR      0x34
#define AXP_I2C_HZ    100000

/* AXP2101 registers used here. */
#define AXP_REG_CHIP_ID     0x03
#define AXP_REG_PWRON_CFG   0x27   /* on/off level timing: press durations */
#define AXP_REG_IRQ_EN0     0x40
#define AXP_REG_IRQ_EN1     0x41
#define AXP_REG_IRQ_EN2     0x42
#define AXP_REG_IRQ_ST0     0x48
#define AXP_REG_IRQ_ST1     0x49   /* PWRKEY short/long live here */
#define AXP_REG_IRQ_ST2     0x4A

/*
 * Battery, via the AXP2101's own ADC and fuel gauge.
 *
 * REGISTER NUMBERS FROM THE DATASHEET, VALUES CONFIRMED ON THIS BOARD — the
 * same rule the PWRKEY table below was written under, and for the same reason:
 * this part is not in the BSP and the numbers that matter here were wrong once
 * already. Every read is logged raw at startup (see pet_pwr_battery_probe) so a
 * mapping that is off shows up as nonsense volts rather than as a plausible
 * lie.
 */
#define AXP_REG_STATUS2     0x01   /* bits 6:5 = charge direction            */
#define AXP_REG_ADC_EN      0x30   /* bit0 enables the VBAT channel          */
#define AXP_REG_VBAT_H      0x34   /* 14-bit VBAT, in millivolts             */
#define AXP_REG_VBAT_L      0x35
#define AXP_REG_BAT_PCT     0xA4   /* fuel gauge, 0-100 %                    */

#define AXP_ADC_EN_VBAT     0x01

/* Constant-current charge setting. Bits 4:0; the rest are not ours. The value
 * and the reasoning behind it are at the write site in pet_pwr_start(). */
#define AXP_REG_CHG_CURRENT   0x62
#define AXP_CHG_CURRENT_MASK  0x1F
#define AXP_CHG_CURRENT_SET   0x0A

/* Software power-off. Setting this makes the PMIC drop its output rails; a
 * PWRKEY press then brings them back, which is the PMIC's own behaviour and
 * needs no firmware. That is what makes one button do both directions. */
#define AXP_REG_COMMON_CFG  0x10
#define AXP_SOFF_BIT        0x01

/*
 * PWRKEY events, in IRQ status register 1.
 *
 * MEASURED on this board rather than read off a datasheet — the probe below
 * logged the same sequence three times running:
 *
 *   press                      0x02
 *   release under ~1.45 s      0x09  (release 0x01 | short 0x08, together)
 *   held past ~1.45 s          0x04  fires while still held
 *   release                    0x01
 *
 * The long-press bit lands at 1450 ms every time, whatever the total hold, so
 * that is the PMIC's threshold and not something firmware has to time.
 */
#define AXP_IRQ1_RELEASE    0x01
#define AXP_IRQ1_PRESS      0x02
#define AXP_IRQ1_LONG       0x04
#define AXP_IRQ1_SHORT      0x08

/* BOOT is GPIO0 on the S3 and is active-low with an external pull-up. */
#define BOOT_GPIO     GPIO_NUM_0

static i2c_master_dev_handle_t s_dev;

static esp_err_t axp_read(uint8_t reg, uint8_t *out, size_t len)
{
    return i2c_master_transmit_receive(s_dev, &reg, 1, out, len, 200);
}

static esp_err_t axp_write(uint8_t reg, uint8_t val)
{
    const uint8_t buf[2] = { reg, val };
    return i2c_master_transmit(s_dev, buf, sizeof(buf), 200);
}

/*
 * TEMPORARY probe. Reports what the PMIC is configured to do and what each
 * button actually produces, so the power button can be built from observation
 * rather than from a datasheet reading that may not match this board.
 *
 * Build with `idf.py build -DPET_PWR_PROBE=1`.
 */
#ifndef PET_PWR_PROBE
#define PET_PWR_PROBE 0
#endif

#if PET_PWR_PROBE
static void pet_pwr_probe_task(void *arg)
{
    (void)arg;

    uint8_t cfg = 0, en0 = 0, en1 = 0, en2 = 0;
    axp_read(AXP_REG_PWRON_CFG, &cfg, 1);
    axp_read(AXP_REG_IRQ_EN0, &en0, 1);
    axp_read(AXP_REG_IRQ_EN1, &en1, 1);
    axp_read(AXP_REG_IRQ_EN2, &en2, 1);
    ESP_LOGW(TAG, "PROBE cfg(0x27)=0x%02X irq_en=%02X %02X %02X", cfg, en0, en1, en2);

    /* Enable every interrupt so nothing a button does is missed, then report
     * whichever bits actually set. Which bit means what is the thing being
     * measured, so guessing a mask here would defeat the point. */
    axp_write(AXP_REG_IRQ_EN0, 0xFF);
    axp_write(AXP_REG_IRQ_EN1, 0xFF);
    axp_write(AXP_REG_IRQ_EN2, 0xFF);
    axp_write(AXP_REG_IRQ_ST0, 0xFF);   /* write-1-to-clear */
    axp_write(AXP_REG_IRQ_ST1, 0xFF);
    axp_write(AXP_REG_IRQ_ST2, 0xFF);

    int last_boot = 1;
    ESP_LOGW(TAG, "PROBE ready - press PWR (short, then long) and BOOT");

    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(50));

        uint8_t st[3] = {0};
        if (axp_read(AXP_REG_IRQ_ST0, st, sizeof(st)) == ESP_OK &&
            (st[0] | st[1] | st[2]) != 0) {
            ESP_LOGW(TAG, "PROBE irq %02X %02X %02X", st[0], st[1], st[2]);
            axp_write(AXP_REG_IRQ_ST0, st[0]);
            axp_write(AXP_REG_IRQ_ST1, st[1]);
            axp_write(AXP_REG_IRQ_ST2, st[2]);
        }

        const int boot = gpio_get_level(BOOT_GPIO);
        if (boot != last_boot) {
            ESP_LOGW(TAG, "PROBE BOOT gpio0 -> %d (%s)", boot,
                     boot == 0 ? "pressed" : "released");
            last_boot = boot;
        }
    }
}
#endif /* PET_PWR_PROBE */

/*
 * Hold PWR to switch the pet off; press it again to switch it back on.
 *
 * Nothing needs saving first. pet_sim writes to NVS whenever a score actually
 * changes, and the persisted epoch is what decay is computed from, so the pet's
 * state is already durable at every instant — it will wake up having aged by
 * however long it was off, which is the whole point of using the RTC.
 *
 * The PMIC keeps the RTC powered from the battery across this, so "off" really
 * does mean off rather than sleeping.
 */
static void pet_pwr_off(void)
{
    ESP_LOGW(TAG, "power key held - switching off");

    /* Dim first: it is the only feedback the user gets, and if the power-off
     * below is refused it is also the signal that something went wrong. */
    pet_display_brightness(0);
    vTaskDelay(pdMS_TO_TICKS(80));   /* let the log drain over USB */

    uint8_t cfg = 0;
    if (axp_read(AXP_REG_COMMON_CFG, &cfg, 1) == ESP_OK) {
        axp_write(AXP_REG_COMMON_CFG, (uint8_t)(cfg | AXP_SOFF_BIT));
    }

    /*
     * If the rails had dropped, execution would not reach here. Getting this far
     * means the PMIC declined — which it will while USB is supplying power, since
     * there is nothing for it to switch off. Say so plainly and put the screen
     * back, rather than leaving a dark pet that looks broken.
     */
    vTaskDelay(pdMS_TO_TICKS(600));
    ESP_LOGW(TAG, "still running - PMIC declined to power down. "
                  "Expected while USB is connected; try it on battery.");
    pet_display_brightness(80);
}

/*
 * Hold BOOT for five seconds to start a new pet — but only when the current one
 * is dead. DESIGN.md §1 phase 6.
 *
 * BOOT is the right button for this. It is a real, documented, unused input; it
 * is awkward enough that nobody reaches it by accident; and it keeps the pet
 * self-sufficient, which is the architecture — a dead pet must be revivable with
 * no phone in the room, the same way it lives and ages with no phone in the room.
 *
 * Five seconds, and the hold is reported as it builds so the gesture is
 * discoverable at all. The same reasoning as the shake meter in the HUD: an
 * invisible hold is indistinguishable from a button that does nothing.
 *
 * Refused while alive, deliberately without any feedback. There is no "are you
 * sure" on this device to put in front of it, so the answer to "what if someone
 * holds it on a living pet" is that nothing whatsoever happens.
 */
#define BOOT_RESET_HOLD_MS 5000

static void boot_button_poll(void)
{
    static int64_t held_since_us;
    static bool    announced;

    /* Active-low with an external pull-up: 0 means pressed. */
    const bool down = (gpio_get_level(BOOT_GPIO) == 0);

    if (!down) {
        held_since_us = 0;
        announced = false;
        return;
    }

    if (held_since_us == 0) {
        held_since_us = esp_timer_get_time();
        return;
    }

    const int64_t held_ms = (esp_timer_get_time() - held_since_us) / 1000;

    if (!announced && held_ms > 400) {
        announced = true;
        ESP_LOGI(TAG, "BOOT held%s", pet_sim_is_dead()
                 ? " - keep holding for 5 s to start a new pet" : " (pet is alive - ignored)");
    }

    if (held_ms >= BOOT_RESET_HOLD_MS && pet_sim_is_dead()) {
        held_since_us = 0;      /* consumed: one reset per hold */
        announced = false;
        ESP_LOGW(TAG, "BOOT held %d s - starting a new pet", BOOT_RESET_HOLD_MS / 1000);
        pet_sim_reset();
    }
}

static void pet_pwr_task(void *arg)
{
    (void)arg;
    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(50));

        boot_button_poll();

        uint8_t st = 0;
        if (axp_read(AXP_REG_IRQ_ST1, &st, 1) != ESP_OK || st == 0) {
            continue;
        }
        axp_write(AXP_REG_IRQ_ST1, st);    /* write-1-to-clear */

        if (st & AXP_IRQ1_LONG) {
            pet_pwr_off();
        } else if (st & AXP_IRQ1_SHORT) {
            /*
             * Silence the call — but NOT the obligation. The care mistake still
             * accrues, because a button that cancelled it would let the
             * consequence be dismissed without the behaviour changing, which is
             * exactly the failure mode the mechanic exists to avoid. Sound off,
             * obligation intact.
             */
            pet_sim_silence_call();
        }
    }
}

/*
 * How the battery is, or false when the PMIC is not answering.
 *
 * Percentage comes from the AXP2101's own fuel gauge rather than being derived
 * from voltage here. A LiPo's discharge curve is flat across most of its range,
 * so a voltage-to-percent guess in firmware would read 100 % for hours and then
 * fall off a cliff — which is exactly the "abrupt death" DESIGN.md §1 wants a
 * real battery state to replace.
 */
bool pet_pwr_battery(uint8_t *percent, uint16_t *millivolts, bool *charging)
{
    if (s_dev == NULL) {
        return false;
    }

    uint8_t pct = 0, vh = 0, vl = 0, st2 = 0;
    if (axp_read(AXP_REG_BAT_PCT, &pct, 1) != ESP_OK ||
        axp_read(AXP_REG_VBAT_H, &vh, 1) != ESP_OK ||
        axp_read(AXP_REG_VBAT_L, &vl, 1) != ESP_OK ||
        axp_read(AXP_REG_STATUS2, &st2, 1) != ESP_OK) {
        return false;
    }

    if (percent) {
        /* The gauge reads 0xFF before it has settled after a cold start. Report
         * it as unknown rather than as a flat battery, which would otherwise
         * look like an emergency for the first few seconds of every boot. */
        *percent = (pct > 100) ? 0xFF : pct;
    }
    if (millivolts) {
        *millivolts = (uint16_t)(((vh & 0x3F) << 8) | vl);
    }
    if (charging) {
        /* bits 6:5 — 01 = charging. Anything else is standby or discharging;
         * the distinction that matters here is only "is it going up". */
        *charging = ((st2 >> 5) & 0x03) == 0x01;
    }
    return true;
}

/*
 * Raw dump of every register that might carry a state of charge.
 *
 * WHY THIS IS PUBLIC AND CALLED LATE: the AXP2101 is not in the BSP and its
 * gauge did not behave as the datasheet mapping suggested — 0xA4 read 0 through
 * eight minutes of charging while VBAT climbed 3776 -> 3828 mV, which is not a
 * flat cell. Rather than guess at another register, this prints the candidates
 * so the right one can be identified from how they MOVE, which is the same way
 * the PWRKEY bits below were pinned down.
 *
 * Called from pet_status_task, not from here: nothing logged before ~1.2 s
 * survives the S3's native-USB re-enumeration, and the first version of this
 * printed perfectly into a port nobody could open yet.
 */
void pet_pwr_battery_probe(void)
{
    uint8_t pct = 0, vh = 0, vl = 0, st1 = 0, st2 = 0, aden = 0;
    axp_read(AXP_REG_BAT_PCT, &pct, 1);
    axp_read(AXP_REG_VBAT_H, &vh, 1);
    axp_read(AXP_REG_VBAT_L, &vl, 1);
    axp_read(0x00, &st1, 1);
    axp_read(AXP_REG_STATUS2, &st2, 1);
    axp_read(AXP_REG_ADC_EN, &aden, 1);
    /* Candidates for the gauge, and the module-enable that may be gating it. */
    uint8_t mod_en = 0, a3 = 0, a5 = 0, chg_st = 0;
    axp_read(0x18, &mod_en, 1);      /* module enable: gauge/charger bits      */
    axp_read(0xA3, &a3, 1);          /* neighbours of 0xA4, in case of an      */
    axp_read(0xA5, &a5, 1);          /* off-by-one in the map                  */
    axp_read(0x20, &chg_st, 1);      /* charger status                          */

    ESP_LOGI(TAG, "battery raw: pct(A4)=0x%02X vbat=0x%02X%02X (%u mV) "
                  "st1=0x%02X st2=0x%02X adcen=0x%02X moden(18)=0x%02X "
                  "A3=0x%02X A5=0x%02X chg(20)=0x%02X",
             pct, vh, vl, (unsigned)(((vh & 0x3F) << 8) | vl), st1, st2, aden,
             mod_en, a3, a5, chg_st);

    /*
     * THE CHARGE SETTINGS, READ AND NEVER WRITTEN — 2026-08-04.
     *
     * The 1300 mAh cell wants a faster charge than the ~183 mA it gets now
     * (0.14C, ~7 h plus taper), and the current is a register. It is not being
     * written here, and the reason is worth stating rather than leaving as
     * caution: **this is a charge current for a bare LiPo pouch**, the AXP2101 is
     * not in the BSP, there is no driver for it in this tree, and its datasheet
     * is not either. Setting it from a remembered bit mapping risks asking for
     * amps where hundreds of milliamps were meant, and the failure mode of that
     * mistake is a fire rather than a bad build.
     *
     * So it is pinned the same way the fuel gauge and the PWRKEY bits were: read
     * the register, put it beside a current we have already measured, and let the
     * mapping fall out of the pair. If ~183 mA reads as 0x07 then the steps are
     * 25 mA and the datasheet mapping is confirmed; if it reads as something
     * else, the guess was wrong and would have been written blind.
     *
     * 0x62 is the constant-current setting, 0x61 the pre-charge and 0x63 the
     * termination current, on the mapping this is trying to confirm — logged
     * together because reading them as a group is what makes an off-by-one in the
     * map obvious.
     *
     * NEXT STEP once the mapping is confirmed: raise it. Even 0.5C is 650 mA for
     * this cell, so there is a lot of room above 183 mA; the number to choose is
     * whatever the cell's own datasheet allows, not the maximum the PMIC offers.
     */
    uint8_t icc = 0, ipre = 0, iterm = 0, vterm = 0;
    axp_read(AXP_REG_CHG_CURRENT, &icc, 1); /* constant-current charge setting */
    axp_read(0x61, &ipre, 1);        /* pre-charge current              */
    axp_read(0x63, &iterm, 1);       /* termination current             */
    axp_read(0x64, &vterm, 1);       /* charge target voltage           */
    ESP_LOGW(TAG, "charge cfg (READ ONLY): icc(62)=0x%02X ipre(61)=0x%02X "
                  "iterm(63)=0x%02X vterm(64)=0x%02X  <- pair icc with the "
                  "measured charge current to pin the mA-per-step",
             icc, ipre, iterm, vterm);
}

/*
 * READ-ONLY dump of every AXP2101 output rail. Diagnostic, 2026-09-01.
 *
 * For the black-screen bug: the AMOLED has to be fed from one of these, and
 * nothing in this repo currently says which. Comparing the dump from the board
 * whose panel works against the one whose panel does not is the cheapest way to
 * find out — and it is a comparison that could not be made while only one
 * board was to hand.
 *
 * **READ-ONLY, deliberately.** Turning off the wrong rail browns out the ESP32, so this identifies before
 * anything writes. Registers are the AXP2101's documented map: 0x80 enables the
 * DCDCs, 0x90/0x91 the LDOs, and 0x82..0x9A are the voltage settings.
 */
void pet_pwr_dump_rails(void)
{
    if (s_dev == NULL) {
        ESP_LOGE(TAG, "rails: PMIC not started");
        return;
    }
    static const struct { uint8_t reg; const char *name; } V[] = {
        { 0x82, "DCDC1" }, { 0x83, "DCDC2" }, { 0x84, "DCDC3" },
        { 0x85, "DCDC4" }, { 0x86, "DCDC5" },
        { 0x92, "ALDO1" }, { 0x93, "ALDO2" }, { 0x94, "ALDO3" },
        { 0x95, "ALDO4" }, { 0x96, "BLDO1" }, { 0x97, "BLDO2" },
        { 0x99, "DLDO1" }, { 0x9A, "DLDO2" },
    };
    uint8_t dc = 0, ld0 = 0, ld1 = 0;
    axp_read(0x80, &dc, 1);
    axp_read(0x90, &ld0, 1);
    axp_read(0x91, &ld1, 1);
    ESP_LOGW(TAG, "rails: enable regs 0x80=0x%02X 0x90=0x%02X 0x91=0x%02X",
             dc, ld0, ld1);
    for (size_t i = 0; i < sizeof(V) / sizeof(V[0]); i++) {
        uint8_t v = 0;
        if (axp_read(V[i].reg, &v, 1) != ESP_OK) {
            continue;
        }
        /* Which bit enables this rail: DCDC1..5 are 0x80 bits 0..4; the LDOs
         * are 0x90 bits 0..7 in the order ALDO1..4, BLDO1..2, CPUSLDO, DLDO1,
         * and DLDO2 is 0x91 bit 0. */
        bool on;
        if (i < 5) {
            on = dc & (1u << i);
        } else if (i < 12) {
            on = ld0 & (1u << (i - 5));
        } else {
            on = ld1 & 0x01;
        }
        ESP_LOGW(TAG, "rails:   %-5s reg 0x%02X = 0x%02X  %s",
                 V[i].name, V[i].reg, v, on ? "ON" : "off");
    }
}

bool pet_pwr_start(void)
{
    if (s_dev != NULL) {
        return true;
    }

    i2c_master_bus_handle_t bus = bsp_i2c_get_handle();
    if (bus == NULL && bsp_i2c_init() == ESP_OK) {
        bus = bsp_i2c_get_handle();
    }
    if (bus == NULL) {
        ESP_LOGE(TAG, "no I2C bus - power management unavailable");
        return false;
    }

    const i2c_device_config_t cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = AXP_ADDR,
        .scl_speed_hz    = AXP_I2C_HZ,
    };
    if (i2c_master_bus_add_device(bus, &cfg, &s_dev) != ESP_OK) {
        ESP_LOGE(TAG, "add_device failed - power management unavailable");
        return false;
    }

    uint8_t id = 0;
    if (axp_read(AXP_REG_CHIP_ID, &id, 1) != ESP_OK) {
        ESP_LOGE(TAG, "AXP2101 not responding");
        i2c_master_bus_rm_device(s_dev);
        s_dev = NULL;
        return false;
    }
    ESP_LOGI(TAG, "AXP2101 chip id 0x%02X", id);

    /* Enable the VBAT ADC channel. Read-modify-write rather than a bare store:
     * the other bits in this register are the BSP's business (VBUS, VSYS, die
     * temperature) and clobbering them would silently break whatever reads them
     * next. Without this VBAT reads 0 and the gauge never settles. */
    uint8_t adc_en = 0;
    if (axp_read(AXP_REG_ADC_EN, &adc_en, 1) == ESP_OK) {
        axp_write(AXP_REG_ADC_EN, adc_en | AXP_ADC_EN_VBAT);
    }

    /*
     * CHARGE CURRENT: 0x08 -> 0x0A, raised 2026-08-05 for the 1300 mAh cell.
     *
     * THE MAPPING WAS MEASURED, NOT REMEMBERED. The register read 0x08 against a
     * charge current independently measured at ~183 mA, which pins the low range
     * at 25 mA per step (8 x 25 = 200 mA nominal, ~183 mA under load). That is
     * the same technique the fuel gauge and the PWRKEY bits were pinned with,
     * and it is used here because there is no AXP2101 driver or datasheet in
     * this tree and the failure mode of a wrong bit table on a bare LiPo pouch
     * is a fire rather than a bad build.
     *
     * WHY 0x0A SPECIFICALLY, AND WHY THE REMAINING AMBIGUITY DOES NOT MATTER.
     * The mapping is believed to change step size above 0x08 — 25 mA steps below,
     * 100 mA above — and only the low range has been confirmed. So 0x0A is either
     * 250 mA (if the steps stay uniform) or 400 mA (if they change). **Both are
     * <= 0.31C for a 1300 mAh cell**, so this is safe whichever is true and the
     * ambiguity did not need resolving. Choosing a value that is correct under
     * every candidate mapping is cheaper than being certain about one.
     *
     * The recharge duration disambiguates it for free: ~5.2 h means 250 mA,
     * ~3.3 h means 400 mA. Write that down here when it is known.
     *
     * DO NOT RAISE THIS FURTHER WITHOUT THE REAL DATASHEET. Above 0x0A the two
     * candidate mappings diverge past 0.5C and the argument above stops holding.
     *
     * DO NOT TOUCH vterm (0x64, reads 0x03 = the 4.2 V target). Charge current
     * affects how long a charge takes; termination voltage decides whether the
     * cell is overcharged, and it is already correct.
     *
     * Read-modify-write, for the same reason as the ADC enable above: only bits
     * 4:0 are the current, and the rest are not ours to clear.
     */
    uint8_t icc = 0;
    if (axp_read(AXP_REG_CHG_CURRENT, &icc, 1) == ESP_OK) {
        const uint8_t want = (uint8_t)((icc & ~AXP_CHG_CURRENT_MASK) | AXP_CHG_CURRENT_SET);
        if ((icc & AXP_CHG_CURRENT_MASK) != AXP_CHG_CURRENT_SET) {
            axp_write(AXP_REG_CHG_CURRENT, want);
            uint8_t back = 0;
            axp_read(AXP_REG_CHG_CURRENT, &back, 1);
            /* Read back and log both, because a PMIC that silently refuses a
             * value would otherwise look exactly like one that accepted it. */
            ESP_LOGW(TAG, "charge current 0x%02X -> 0x%02X (reads back 0x%02X)",
                     icc & AXP_CHG_CURRENT_MASK, AXP_CHG_CURRENT_SET,
                     back & AXP_CHG_CURRENT_MASK);
        }
    }

    pet_pwr_battery_probe();

    /* BOOT is shared with the bootloader strap, so it is only ever read — never
     * driven, and never used during the strapping window at reset. */
    const gpio_config_t bcfg = {
        .pin_bit_mask = 1ULL << BOOT_GPIO,
        .mode         = GPIO_MODE_INPUT,
        .pull_up_en   = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type    = GPIO_INTR_DISABLE,
    };
    gpio_config(&bcfg);

    /* Only the PWRKEY interrupts are wanted; the probe enabled everything. */
    axp_write(AXP_REG_IRQ_EN1, AXP_IRQ1_PRESS | AXP_IRQ1_RELEASE |
                               AXP_IRQ1_SHORT | AXP_IRQ1_LONG);
    axp_write(AXP_REG_IRQ_ST1, 0xFF);   /* clear anything already latched */

#if PET_PWR_PROBE
    xTaskCreate(pet_pwr_probe_task, "pet_pwr_probe", 3072, NULL, 2, NULL);
#else
    if (xTaskCreate(pet_pwr_task, "pet_pwr", 3072, NULL, 2, NULL) != pdPASS) {
        ESP_LOGE(TAG, "task alloc failed - power button disabled");
        return false;
    }
    ESP_LOGI(TAG, "ready - hold PWR ~1.5 s to switch off, press to switch on");
#endif
    return true;
}
