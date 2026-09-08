/*
 * DigitalPet — ESP32-S3 display firmware.
 *
 * Milestone 1: bring up the AMOLED via the BSP and draw a first pet face.
 *
 * PORTABILITY: app_main() holds the ONLY board-specific calls (the bsp_* API).
 * pet_face_create() is pure LVGL — it works on any board/resolution, so moving
 * to the round 1.75" board later is just swapping the BSP dependency; the screen
 * size comes from BSP_LCD_H_RES/V_RES.
 */
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/idf_additions.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_check.h"
#include "esp_system.h"   /* esp_reset_reason() — see boot_reset_reason() */
#include <string.h>
#include <math.h>   /* the spiral eyes are ROTATED, which is the only trig here */
#include "nvs_flash.h"
#include "nvs.h"
#include "esp_pm.h"
#include "soc/rtc.h"

/* See the note at esp_pm_configure(). Off unless the build asks for it. */
#ifndef PET_LIGHT_SLEEP
#define PET_LIGHT_SLEEP 0
#endif
#include "bsp/esp-bsp.h"
#include "esp_lvgl_port.h"
#include "lvgl.h"
#include "driver/i2c_master.h"
#include "esp_lcd_co5300.h"   /* V2 boards; V1 uses the BSP's SH8601 */

/* The two touch controllers, which are also how the board names itself.
 * Measured on hardware, both boards. */
#define TOUCH_ADDR_FT3168   0x38   /* V1 */
#define TOUCH_ADDR_CST820   0x15   /* V2 */

/* NEITHER controller is reliably awake when first reached, which is why both
 * the board detector and the touch init retry rather than asking once. */
#define TOUCH_PROBE_TRIES    10
#define TOUCH_PROBE_DELAY_MS 50

/* 100 kHz, not the bus default of 400: this bus logs a pull-up warning on every
 * boot, and there is no reason to run the slowest, least critical device on it
 * at full speed. */
#define TOUCH_I2C_HZ 100000

/* FocalTech register map — the same on every part in this family. */
#define FT_REG_POINTS   0x02   /* number of touches, low nibble */
#define FT_REG_TOUCH1   0x03   /* XH, XL, YH, YL */
#define FT_REG_VENDOR   0xA8   /* 0x11 = FocalTech */

/* CST820 (a CST816S part). The data registers from 0x02 are laid out exactly as
 * FocalTech's — num, XH, XL, YH, YL, with the coordinate high nibbles in the low
 * 4 bits — which is why touch_read_cb() serves both boards unchanged. Confirmed
 * against Espressif's esp_lcd_touch_cst816s, not assumed from the addresses. */
#define CST_REG_CHIP_ID 0xA7
#include "pet.h"
#include "pet_faces.h"   /* GENERATED from design-system/faces/ */

static const char *TAG = "pet";

/*
 * WHY THE BOARD RESET, logged at every boot.
 *
 * Added 2026-09-01 while chasing the black-screen bug, because the log could
 * not tell a deliberate esp_restart() from a brownout or a panic — and the V1
 * board turns out to reset a second time about 1.2 s into every boot, which
 * nobody could see. A reset that leaves no trace is the same class of problem
 * as the silent serial capture: the instrument says nothing and that reads as
 * nothing happening.
 *
 * Cheap enough to keep permanently. `esp_reset_reason()` is a register read.
 */
static const char *boot_reset_reason(void)
{
    switch (esp_reset_reason()) {
    case ESP_RST_POWERON:  return "power-on";
    case ESP_RST_SW:       return "esp_restart()";
    case ESP_RST_PANIC:    return "PANIC";
    case ESP_RST_INT_WDT:  return "interrupt watchdog";
    case ESP_RST_TASK_WDT: return "task watchdog";
    case ESP_RST_WDT:      return "other watchdog";
    case ESP_RST_BROWNOUT: return "BROWNOUT - the supply sagged";
    case ESP_RST_DEEPSLEEP:return "deep sleep wake";
    case ESP_RST_EXT:      return "external reset pin";
    case ESP_RST_USB:      return "USB peripheral reset";
    case ESP_RST_JTAG:     return "JTAG";
    default:               return "unknown";
    }
}

/* UI handles the BLE layer updates via pet_set_text()/pet_set_mood(). */
static lv_obj_t *s_mouth;
/* The curved alternative. Exactly one of the two is visible. */
static lv_obj_t *s_mouth_arc;
/* The curved form of each eye. Like the mouth, both forms exist from boot
 * and one is hidden: swapping widget types inside apply_mood() would mean
 * allocating during a mood change, and a failed allocation on this board is
 * exactly how the screen froze once. */
static lv_obj_t *s_eye_arcs[2];
/* And the third form: the spiral the pet's eyes become when it is ill. A
 * polyline rather than a widget, because LVGL has no spiral and the shape is a
 * table of points from the same generator the arcs come from. Like the other
 * two it exists from boot and spends nearly all of its life hidden. */
static lv_obj_t *s_eye_spirals[2];
static lv_obj_t *s_text_label;
static lv_obj_t *s_mic_btn;
static lv_obj_t *s_mic_dot;
/* Whether a phone is connected. Declared up here with the other face widgets
 * because pet_face_create builds it long before apply_link is defined. */
static lv_obj_t *s_link_dot;

/* Whether touch came up, for the boot summary below. */
static bool s_touch_ok;

/*
 * Liveness of the LVGL task, for the stall backstop in pet_status_task().
 *
 * A heartbeat timer bumps this; anything else has to read it from another task,
 * because when LVGL wedges it is the task that would otherwise report on itself.
 */
static volatile uint32_t s_lvgl_beats;
static lv_display_t *s_disp;

/*
 * THE SCREEN IS LANDSCAPE; THE PANEL IS NOT.
 *
 * BSP_LCD_H_RES/V_RES are 368x448, the panel as it is wired. Everything the UI
 * lays out lives in the ROTATED space — 448 wide, 368 tall — so these two are
 * what layout code must use. Reaching for BSP_LCD_H_RES to mean "the width of
 * the screen" is now wrong by 80px, which is exactly the kind of plausible
 * number this project keeps getting caught by.
 *
 * ONE KNOB. Rotation and the touch mapping below both derive from PET_ROTATION,
 * so they cannot disagree: if the image comes up upside down, change this to
 * LV_DISPLAY_ROTATION_270 and touch follows it. That matters because LVGL does
 * NOT rotate pointer input — see touch_read_cb.
 */
/*
 * THE ROTATION IS DEGREES, AND IT HAS TO BE. Do not put the LVGL enum here.
 *
 * This was `#define PET_ROTATION LV_DISPLAY_ROTATION_270` and touch_read_cb
 * selected its mapping with `#if PET_ROTATION == LV_DISPLAY_ROTATION_90`. That
 * silently did the wrong thing: **LV_DISPLAY_ROTATION_* are enum constants, not
 * macros**, and the preprocessor replaces identifiers it has never heard of with
 * 0. So the test read `0 == 0`, was always true, and compiled the 90° mapping in
 * no matter what this said — while lv_display_set_rotation() received the real
 * enum and rotated the screen 270°.
 *
 * The result was a display and a finger that disagreed by 180°, with no warning
 * from the compiler and nothing wrong in the arithmetic. It survived a build,
 * which is the whole reason this project verifies on hardware.
 *
 * An int macro is something the preprocessor can actually compare. The enum is
 * derived from it below, and the _Static_assert keeps the two from drifting —
 * that is a real risk now that the same fact has two names.
 */
#define PET_ROTATION_DEG 270

#if   PET_ROTATION_DEG == 0
#  define PET_ROTATION LV_DISPLAY_ROTATION_0
#elif PET_ROTATION_DEG == 90
#  define PET_ROTATION LV_DISPLAY_ROTATION_90
#elif PET_ROTATION_DEG == 180
#  define PET_ROTATION LV_DISPLAY_ROTATION_180
#elif PET_ROTATION_DEG == 270
#  define PET_ROTATION LV_DISPLAY_ROTATION_270
#else
#  error "PET_ROTATION_DEG must be 0, 90, 180 or 270"
#endif

/* _Static_assert CAN see enum values — it runs after the preprocessor, which is
 * exactly the difference that caused the bug above. */
_Static_assert((PET_ROTATION_DEG == 0   && PET_ROTATION == LV_DISPLAY_ROTATION_0)   ||
               (PET_ROTATION_DEG == 90  && PET_ROTATION == LV_DISPLAY_ROTATION_90)  ||
               (PET_ROTATION_DEG == 180 && PET_ROTATION == LV_DISPLAY_ROTATION_180) ||
               (PET_ROTATION_DEG == 270 && PET_ROTATION == LV_DISPLAY_ROTATION_270),
               "PET_ROTATION_DEG and PET_ROTATION disagree");

#define PET_SCREEN_W  BSP_LCD_V_RES   /* 448 — the long edge, across */
#define PET_SCREEN_H  BSP_LCD_H_RES   /* 368 — the short edge, down   */

/* Defined further down, used by pet_status_task and apply_sleep, both of which
 * sit above them. */
static void sleep_tick(void);
static void teach_tick(void);
static void apply_mood(uint8_t mood);
static void add_breathe(lv_obj_t *obj);
static void chrome_timeout_cb(lv_timer_t *t);
static void apply_chrome_wake(void);

/*
 * MILESTONE 1 NOTE — display-only bring-up.
 *
 * The BSP's bsp_display_start() also inits the FT3168 touch and calls abort()
 * if that fails. On this board the touch has NO reset line (BSP_LCD_TOUCH_RST =
 * NC) and its I2C init is intermittent, so bsp_display_start() boot-loops. We
 * don't need touch for the face (voice + IMU + phone are the pet's real inputs),
 * so we bring up display + LVGL directly via the BSP's public bsp_display_new()
 * + esp_lvgl_port — a stable image, no touch abort. Touch is a later, separate
 * task. Still fully portable: bsp_display_new/BSP_LCD_* come from the BSP.
 */
/*
 * WHICH BOARD IS THIS? Waveshare ships two revisions under one product name.
 *
 *   V1  SH8601 panel + FT3168 touch at I2C 0x38
 *   V2  CO5300 panel + CST820 touch at I2C 0x15
 *
 * Scanned on both: the ONLY difference on the bus is that one
 * address. Codec, IO expander, PMIC, RTC and IMU are identical, as are every
 * display pin and the 368x448 geometry. So the touch controller is the board's
 * name tag, and the panel — which is on write-only QSPI and cannot be probed —
 * is chosen from it.
 *
 * THIS IS A PROXY, and worth stating plainly: nothing here reads the panel. If
 * Waveshare ever ships a third mix, this infers the wrong driver and the screen
 * goes black in exactly the silent way that cost a day on 2026-08-30. That is
 * why the detected variant is logged loudly at boot rather than inferred
 * quietly — the log line is the thing that makes the next surprise cheap.
 */
static bool s_board_v2;                       /* CO5300 + CST820 */
static esp_lcd_panel_io_handle_t s_panel_io;  /* V2 only; V1's lives in the BSP */

/* One register read against a candidate address, on a throwaway device handle.
 * Returns false if the chip is absent, asleep or simply does not answer. */
static bool board_id_read(i2c_master_bus_handle_t bus, uint8_t addr,
                          uint8_t reg, uint8_t *out)
{
    i2c_device_config_t dcfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = addr,
        .scl_speed_hz    = TOUCH_I2C_HZ,
    };
    i2c_master_dev_handle_t dev = NULL;
    if (i2c_master_bus_add_device(bus, &dcfg, &dev) != ESP_OK) {
        return false;
    }
    esp_err_t err = i2c_master_transmit_receive(dev, &reg, 1, out, 1, 100);
    i2c_master_bus_rm_device(dev);
    return err == ESP_OK;
}

/*
 * THE BOARD REMEMBERS WHICH BOARD IT IS, and the reason is not caching.
 *
 * The panel driver must be chosen before the display comes up, and the only
 * thing that distinguishes the two revisions is a touch controller on I2C. But
 * ANY I2C TRANSACTION BEFORE THE DISPLAY IS UP KILLS THE FT3168 on a V1 board
 * for the rest of the boot — measured, and narrowed to the transaction itself:
 *
 *   nothing before display init            touch answers on attempt 2
 *   500 ms delay, no I2C                   touch answers on attempt 2
 *   bsp_i2c_init() alone, no transactions  touch answers on attempt 2
 *   ONE read of 0x15 — an address that     touch dead for the whole boot,
 *   does not even exist on a V1            and absent from a full bus scan
 *
 * Reading an address that is not there breaking a different chip points at the
 * touch controller being unpowered until the display rail comes up, with the
 * bus clocking latching it. Whatever the mechanism, the rule is firm: do not
 * clock I2C before the panel is up.
 *
 * So the variant is read from NVS, which needs no bus at all, and verified
 * against the hardware once the display is safely up. A board that has never
 * run this firmware assumes V1, and pet_board_variant_verify() corrects it.
 */
#define BOARD_NVS_NAMESPACE "pet_board"
#define BOARD_NVS_KEY       "is_v2"

static void pet_board_variant_load(void)
{
    nvs_handle_t h;
    uint8_t stored = 0;

    if (nvs_open(BOARD_NVS_NAMESPACE, NVS_READONLY, &h) != ESP_OK) {
        ESP_LOGW(TAG, "board variant unknown (no NVS) - assuming V1, will verify");
        return;
    }
    esp_err_t err = nvs_get_u8(h, BOARD_NVS_KEY, &stored);
    nvs_close(h);

    if (err != ESP_OK) {
        ESP_LOGW(TAG, "board variant not recorded yet - assuming V1, will verify");
        return;
    }
    s_board_v2 = (stored != 0);
    ESP_LOGW(TAG, "board %s from NVS - %s panel",
             s_board_v2 ? "V2" : "V1", s_board_v2 ? "CO5300" : "SH8601");
}

/*
 * Confirm the guess against the hardware, now that I2C is safe to use.
 *
 * A wrong guess cannot be fixed in place — the panel is already built — so this
 * records the truth and restarts. That costs one reboot the first time a given
 * board ever runs this firmware, and nothing on every boot after. A visible
 * restart is much the better failure: the alternative is a board that boots
 * perfectly and shows a black screen, which is the thing that started all this.
 */
static void pet_board_variant_verify(void)
{
    i2c_master_bus_handle_t bus = bsp_i2c_get_handle();
    if (bus == NULL && bsp_i2c_init() == ESP_OK) {
        bus = bsp_i2c_get_handle();
    }
    if (bus == NULL) {
        ESP_LOGE(TAG, "no I2C bus - cannot verify the board variant");
        return;
    }

    /* Retry: neither controller is reliably awake when first reached. Ask 0x15
     * only — a clean read means V2, and silence means V1, so the FT3168 never
     * has to be disturbed to reach a conclusion. */
    bool is_v2 = false;
    for (int attempt = 0; attempt < TOUCH_PROBE_TRIES; attempt++) {
        uint8_t id = 0;
        if (board_id_read(bus, TOUCH_ADDR_CST820, CST_REG_CHIP_ID, &id)) {
            ESP_LOGW(TAG, "CST820 at 0x%02x, id 0x%02x (attempt %d) - this is a V2",
                     TOUCH_ADDR_CST820, id, attempt + 1);
            is_v2 = true;
            break;
        }
        vTaskDelay(pdMS_TO_TICKS(TOUCH_PROBE_DELAY_MS));
    }

    /* Logged either way. Until now a V1 conclusion was SILENCE — ten failed
     * probes and no line — so "the detector ran and said V1" and "the detector
     * never got there" looked identical in a log, which is exactly the
     * ambiguity that made the 1.2 s reset below hard to place. */
    ESP_LOGW(TAG, "variant probe: %s (assumed %s)",
             is_v2 ? "V2" : "V1", s_board_v2 ? "V2" : "V1");

    nvs_handle_t h;
    if (nvs_open(BOARD_NVS_NAMESPACE, NVS_READWRITE, &h) == ESP_OK) {
        nvs_set_u8(h, BOARD_NVS_KEY, is_v2 ? 1 : 0);
        nvs_commit(h);
        nvs_close(h);
    }

    if (is_v2 != s_board_v2) {
        ESP_LOGE(TAG, "BOARD IS %s BUT %s WAS ASSUMED - recorded, restarting to "
                      "bring up the right panel",
                 is_v2 ? "V2" : "V1", s_board_v2 ? "V2" : "V1");
        vTaskDelay(pdMS_TO_TICKS(100));   /* let the log drain */
        esp_restart();
    }
}

/*
 * The CO5300 init sequence, taken verbatim from Waveshare's own BSP 2.0.3
 * rather than reconstructed from a datasheet. Do not tidy it: the register
 * order is the vendor's and the 100 ms after SLPOUT (0x11) is load-bearing.
 */
static const co5300_lcd_init_cmd_t co5300_init_cmds[] = {
    {0xFE, (uint8_t[]){0x00}, 1, 0},
    {0xC4, (uint8_t[]){0x80}, 1, 0},
    {0x3A, (uint8_t[]){0x55}, 1, 0},
    {0x35, (uint8_t[]){0x00}, 1, 0},
    {0x53, (uint8_t[]){0x20}, 1, 0},
    {0x51, (uint8_t[]){0xFF}, 1, 0},
    {0x63, (uint8_t[]){0xFF}, 1, 0},
    {0x2A, (uint8_t[]){0x00, 0x00, 0x01, 0x6F}, 4, 0},
    {0x2B, (uint8_t[]){0x00, 0x00, 0x01, 0xBF}, 4, 0},
    {0x11, (uint8_t[]){0x00}, 0, 100},
    {0x29, (uint8_t[]){0x00}, 0, 0},
};

/* The V2 panel's first column is 16, not 0. Waveshare applies this gap only
 * when it finds the CST820, which is the same proxy this firmware uses.
 * Miss it and the image is drawn 16 px off the edge rather than absent — a
 * failure that looks like a layout bug, not a driver one. */
#define CO5300_X_GAP 16

static esp_err_t pet_display_new_v2(esp_lcd_panel_handle_t *ret_panel,
                                    esp_lcd_panel_io_handle_t *ret_io)
{
    const spi_bus_config_t buscfg = CO5300_PANEL_BUS_QSPI_CONFIG(
        BSP_LCD_PCLK, BSP_LCD_DATA0, BSP_LCD_DATA1, BSP_LCD_DATA2, BSP_LCD_DATA3,
        BSP_LCD_H_RES * BSP_LCD_V_RES * BSP_LCD_BITS_PER_PIXEL / 8);
    ESP_RETURN_ON_ERROR(spi_bus_initialize(BSP_LCD_SPI_NUM, &buscfg, SPI_DMA_CH_AUTO),
                        TAG, "spi_bus_initialize");

    const esp_lcd_panel_io_spi_config_t io_config =
        CO5300_PANEL_IO_QSPI_CONFIG(BSP_LCD_CS, NULL, NULL);
    ESP_RETURN_ON_ERROR(esp_lcd_new_panel_io_spi((esp_lcd_spi_bus_handle_t)BSP_LCD_SPI_NUM,
                                                 &io_config, &s_panel_io),
                        TAG, "panel_io_spi");

    co5300_vendor_config_t vendor_config = {
        .init_cmds      = co5300_init_cmds,
        .init_cmds_size = sizeof(co5300_init_cmds) / sizeof(co5300_init_cmds[0]),
        .flags = { .use_qspi_interface = 1 },
    };
    const esp_lcd_panel_dev_config_t panel_config = {
        .reset_gpio_num = BSP_LCD_RST,          /* still NC on V2 - SWRESET only */
        .rgb_ele_order  = LCD_RGB_ELEMENT_ORDER_RGB,
        .bits_per_pixel = BSP_LCD_BITS_PER_PIXEL,
        .vendor_config  = &vendor_config,
    };
    ESP_RETURN_ON_ERROR(esp_lcd_new_panel_co5300(s_panel_io, &panel_config, ret_panel),
                        TAG, "new_panel_co5300");

    ESP_RETURN_ON_ERROR(esp_lcd_panel_reset(*ret_panel), TAG, "panel_reset");
    ESP_RETURN_ON_ERROR(esp_lcd_panel_init(*ret_panel), TAG, "panel_init");
    ESP_RETURN_ON_ERROR(esp_lcd_panel_set_gap(*ret_panel, CO5300_X_GAP, 0), TAG, "set_gap");
    ESP_RETURN_ON_ERROR(esp_lcd_panel_disp_on_off(*ret_panel, true), TAG, "disp_on");

    /* esp_lvgl_port asserts on a NULL io_handle, so this is not optional
     * bookkeeping — returning only the panel boot-loops the board. */
    *ret_io = s_panel_io;
    return ESP_OK;
}

/*
 * THE CO5300 WANTS EVEN FLUSH WINDOWS, and this is what "the face glitches"
 * turned out to be.
 *
 * Waveshare's V2 BSP registers exactly this rounder and their V1 BSP has none,
 * which is how it is attributable to the panel rather than to LVGL: an area
 * starting on an odd column or an odd row is misread by the controller, so a
 * partial redraw of a blinking eye lands skewed while a full-screen redraw
 * looks fine. Snap x1/y1 down to even and x2/y2 up to odd, making every
 * flushed window even-aligned and even-sized.
 *
 * V2 only, deliberately: V1 has run without it since M1 and an SH8601 does not
 * ask for it, so adding it there would be an untested change to a working board.
 */
static void co5300_rounder_cb(lv_event_t *e)
{
    lv_area_t *area = (lv_area_t *)lv_event_get_param(e);

    area->x1 = (area->x1 >> 1) << 1;
    area->y1 = (area->y1 >> 1) << 1;
    area->x2 = ((area->x2 >> 1) << 1) + 1;
    area->y2 = ((area->y2 >> 1) << 1) + 1;
}

/*
 * Brightness, for whichever panel is up.
 *
 * bsp_display_brightness_set() drives the BSP's OWN static panel handle, which
 * only bsp_display_new() ever sets. On a V2 board that handle stays NULL and
 * every call returns ESP_ERR_INVALID_STATE — so sleep dimming, wake and the
 * PWR power-off would all quietly stop working while the pet looked fine.
 * Everything that sets brightness goes through here instead.
 */
esp_err_t pet_display_brightness(int percent)
{
    if (!s_board_v2) {
        return bsp_display_brightness_set(percent);
    }
    if (s_panel_io == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    if (percent < 0 || percent > 100) {
        return ESP_ERR_INVALID_ARG;
    }
    /* Same 0x51 write the BSP makes, with the QSPI command framing the panel
     * expects (0x02 in the top byte, command in the second). */
    uint8_t param = (uint8_t)(percent * 255 / 100);
    uint32_t cmd  = (0x02u << 24) | (0x51u << 8);
    return esp_lcd_panel_io_tx_param(s_panel_io, cmd, &param, 1);
}

static lv_display_t *pet_display_start(void)
{
    esp_lcd_panel_handle_t panel = NULL;
    esp_lcd_panel_io_handle_t io = NULL;
    bsp_display_config_t bcfg = {0};

    pet_board_variant_load();

    if (s_board_v2) {
        /* V2 cannot go through the BSP at all: BSP 1.1.2 builds an SH8601 and
         * BSP 2.x builds a CO5300, neither builds both, and the pet has to run
         * on either board from one binary. V1 therefore keeps the exact call it
         * always made — an untested regression there is the thing to avoid. */
        ESP_ERROR_CHECK(pet_display_new_v2(&panel, &io));
    } else {
        ESP_ERROR_CHECK(bsp_display_new(&bcfg, &panel, &io)); /* SH8601, panel on */
    }

    /* I2C is safe now that the panel is up. Confirm the guess, and restart if
     * it was wrong — see pet_board_variant_verify(). */
    pet_board_variant_verify();

    /*
     * Settle, then re-assert display-on and brightness.
     *
     * HONEST STATUS: this did NOT fix the black-screen bug, and is kept only
     * because it is cheap and correct hygiene on a panel with no reset line.
     * Do not read it as the fix.
     *
     * The theory it was built on — that the driver's 80 ms software-reset delay
     * (esp_lcd_sh8601.c) is too short for a controller that wants nearer 120, so
     * DISPON and brightness get dropped — is DISPROVEN. If that were it, a plain
     * warm reset would clear it. It does not: `tools/reset.py` pulses EN with no
     * flashing and the screen stays black. **Only removing power recovers it.**
     *
     * Which means the panel reaches a state SWRESET cannot clear, and no amount
     * of re-issuing commands at init will help. What is known: every AXP2101 rail
     * reads enabled, a board whose panel works reports a byte-identical rail
     * dump, and on one V1 board the fault became permanent through a full power
     * removal. So the cause is downstream of the PMIC.
     */
    vTaskDelay(pdMS_TO_TICKS(150));
    esp_lcd_panel_disp_on_off(panel, true);
    pet_display_brightness(80);

    lvgl_port_cfg_t port_cfg = ESP_LVGL_PORT_INIT_CONFIG();
    ESP_ERROR_CHECK(lvgl_port_init(&port_cfg));

    /*
     * The draw buffers must be in INTERNAL RAM, and they must be small.
     *
     * This looks like it is only about where some memory lives. It is actually
     * the fix for the freeze that needed a power cycle, so it is worth being
     * exact about the mechanism before anyone moves it back to PSRAM for the
     * space.
     *
     * esp_ptr_dma_capable() is a range check against SOC_DMA_LOW/HIGH
     * (0x3FC88000..0x3FD00000), which is internal DRAM only — PSRAM on the S3
     * lives at 0x3C000000 and does not qualify. So with the buffers in PSRAM,
     * spi_master's setup_priv_desc() takes its bounce path on *every* flush: it
     * calls heap_caps_aligned_alloc(..., MALLOC_CAP_DMA) for a CONTIGUOUS
     * internal block the size of the transaction and memcpys the whole area
     * into it. When that allocation fails it does `goto clean_up` and returns
     * ESP_ERR_NO_MEM without logging anything, which surfaces only as
     * "panel_io_spi_tx_color(395): spi transmit (queue) color failed".
     *
     * LVGL has already set flushing = 1 by then, esp_lvgl_port ignores the
     * return value of esp_lcd_panel_draw_bitmap, so lv_display_flush_ready() is
     * never called and the next refresh spins in wait_for_flushing() forever.
     * That is the dead screen: talk button unresponsive, text frozen, no blink.
     *
     * It needs a *large* flush to fail, which is why it took a real reply to
     * trigger — a long message wraps to more lines than an animating eye, so the
     * invalidated area is far bigger. And it needs a fragmented heap, which is
     * why it took a conversation first. Free internal heap was never the
     * measure that mattered: 90 kB was free when this fired, but the largest
     * contiguous block was 31744 B.
     *
     * Internal buffers are DMA-capable as-is, so the bounce path is skipped
     * altogether: no per-flush allocation to fail, and no per-flush memcpy of
     * the whole area either. 20 lines rather than 80 keeps the permanent cost to
     * 2 * 368 * 20 * 2 = 29 kB, on a board where internal RAM is the binding
     * constraint; the only price is more, smaller flushes per frame.
     */
    const lvgl_port_display_cfg_t disp_cfg = {
        .io_handle = io,
        .panel_handle = panel,
        .buffer_size = BSP_LCD_H_RES * 20,   /* partial buffer, double-buffered */
        .double_buffer = true,
        .hres = BSP_LCD_H_RES,
        .vres = BSP_LCD_V_RES,
        .monochrome = false,
        .flags = {
            .buff_spiram = false,  /* MUST stay internal — see above */
            .swap_bytes  = true,   /* SH8601 expects byte-swapped RGB565 */
            .sw_rotate   = true,
        },
    };
    lv_display_t *disp = lvgl_port_add_disp(&disp_cfg);
    if (disp != NULL && s_board_v2) {
        lv_display_add_event_cb(disp, co5300_rounder_cb,
                                LV_EVENT_INVALIDATE_AREA, NULL);
    }
    if (disp == NULL) {
        /* Silent NULL here would leave every later lv_* call operating on
         * nothing, which looks exactly like the freeze this sizing fixes. */
        ESP_LOGE(TAG, "draw buffer allocation failed - display will not work");
    }

    /*
     * THE PET IS LANDSCAPE. The panel is wired portrait and cannot turn itself.
     *
     * The design has always drawn the pet in landscape and this was the one
     * surface still portrait, so the face was squeezed into the wrong aspect
     * ratio: 368x448 is 0.82, the design's 240x200 panel is 1.20, and rotated
     * the device is 448x368 = 1.22. That is a 1.4% difference against a 46%
     * one — the mocks were never portrait, and the comments claiming 368x448
     * was "the Waveshare proportion" were quoting the unrotated panel.
     *
     * IT HAS TO BE SOFTWARE. esp_lcd_sh8601.c answers swap_xy with
     * "swap_xy is not supported by this panel", so the MADCTL route does not
     * exist here. lvgl_port_disp_rotation_update() returns early when
     * sw_rotate is set and therefore never calls it — which is why this is
     * safe rather than a log full of errors.
     *
     * AND IT COSTS NO MEMORY, which is the thing worth checking on this board.
     * `.sw_rotate` was already true, and the port allocates its rotation
     * buffer at add_disp() time from that flag alone, sized `buffer_size`, not
     * the screen. It has therefore been allocated on every boot already; the
     * line below only changes what the flush path does with it. Nothing new is
     * asked of the internal heap, so this cannot reopen the freeze above.
     *
     * The cost is real but it is CPU: every flushed area is rotated by
     * lv_draw_sw_rotate() before it goes out.
     */
    if (disp != NULL) {
        lv_display_set_rotation(disp, PET_ROTATION);
        ESP_LOGI(TAG, "display rotated: panel %dx%d -> screen %dx%d",
                 BSP_LCD_H_RES, BSP_LCD_V_RES, PET_SCREEN_W, PET_SCREEN_H);
    }

    s_disp = disp;

    return disp;
}

/*
 * Touch, brought up separately from the display.
 *
 * The M1 note above is about bsp_display_start(), which wraps touch init in
 * ESP_ERROR_CHECK and aborts the firmware when the FT3168 does not answer.
 * bsp_touch_new() is the same init WITHOUT that wrapper — it returns an error
 * like any other call — so the panel is reachable after all; what was unusable
 * was the bundling, not the hardware.
 *
 * Probed first anyway, the same way the ES8311 is in pet_mic.c: this chip has no
 * reset line (BSP_LCD_TOUCH_RST is NC) and shares the marginal I2C bus that logs
 * a pull-up warning every boot, so a silent controller must cost us touch and
 * nothing else.
 */


static i2c_master_dev_handle_t s_touch_dev;

/*
 * Touch, read straight off I2C rather than through esp_lcd_touch.
 *
 * The BSP wires this panel to the FT5x06 driver, which talks over
 * esp_lcd_panel_io_i2c — a transport built for display controllers, with a
 * control-phase byte and command/param widths that this chip does not accept.
 * Every read through it fails with "i2c transaction failed", which is what made
 * touch look broken since M1 and what aborted the firmware the first time it
 * was switched on (lvgl_port_touchpad_read asserts on a failed read, and the
 * whole UI went down with it).
 *
 * The chip itself is completely healthy. Reading the same registers directly
 * returns vendor 0x11 (FocalTech) and chip 0x64, so all that was ever wrong was
 * the transport. Two registers is the entire protocol we need, which is far
 * less code than making the panel-io layer behave.
 */
static esp_err_t touch_read_reg(uint8_t reg, uint8_t *out, size_t len)
{
    if (s_touch_dev == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    return i2c_master_transmit_receive(s_touch_dev, &reg, 1, out, len, 100);
}

/* LVGL input callback. A failed read reports "not pressed" rather than
 * asserting — on a bus this marginal, a glitch must not take the pet down. */
static void touch_read_cb(lv_indev_t *indev, lv_indev_data_t *data)
{
    LV_UNUSED(indev);
    data->state = LV_INDEV_STATE_RELEASED;

    uint8_t points = 0;
    if (touch_read_reg(FT_REG_POINTS, &points, 1) != ESP_OK) {
        return;
    }
    if ((points & 0x0F) == 0) {
        return;
    }

    uint8_t p[4];
    if (touch_read_reg(FT_REG_TOUCH1, p, sizeof(p)) != ESP_OK) {
        return;
    }

    /* 12-bit coordinates; the top nibble of XH carries the event flags. */
    int32_t px = (int32_t)(((p[0] & 0x0F) << 8) | p[1]);
    int32_t py = (int32_t)(((p[2] & 0x0F) << 8) | p[3]);

    /*
     * REPORT PANEL COORDINATES, RAW. **DO NOT ROTATE THEM HERE.**
     *
     * The screen is rotated (see PET_ROTATION_DEG) and the FT3168 reports in the
     * panel's own space, so this looks exactly like the place to apply the
     * inverse transform. It is not, and doing so is a bug that took four flashes
     * to pin down: **LVGL already rotates pointer input for you**, in
     * indev_pointer_proc() —
     *
     *     lv_display_rotate_point(i->disp, &data->point);   // lv_indev.c
     *
     * and lv_display_rotate_point()'s 270° case is `x = y; y = hor_res - x - 1`,
     * which is precisely the mapping one would derive by hand. Supplying an
     * already-rotated point therefore gets it rotated TWICE, landing it a
     * quarter turn away from the finger.
     *
     * That failure is genuinely nasty to read, because the double rotation still
     * lands somewhere on the screen: taps keep working, the screen-wide
     * double-tap-to-feed keeps firing, and only a small target — the talk button
     * — is unreachable. It even survives a hit test done in this function, since
     * at that moment the point has been rotated once and looks correct; LVGL
     * rotates it again afterwards.
     *
     * Worth being precise about the earlier wrong turn: grepping lv_indev.c for
     * "rotation" finds only the two-finger gesture recogniser and suggests LVGL
     * does none of this. The function is called lv_display_rotate_point, so it
     * does not match that search. Absence of a grep hit is not absence of a
     * feature.
     */
    data->point.x = px;
    data->point.y = py;
    data->state = LV_INDEV_STATE_PRESSED;
}

/*
 * One line naming everything on the bus, every boot. KEPT ON PURPOSE.
 *
 * It was written as throwaway instrumentation for the V1/V2 survey and earned a
 * permanent place twice in one day: it is what showed that the two board
 * revisions differ by exactly one address, and it is what showed V1's FT3168
 * had vanished from 0x38 — the regression that cost the most on that branch.
 * On hardware that changes underneath you without saying so, a boot line
 * listing what actually answered is worth more than its cost.
 *
 * SAFE TO RUN HERE, and only here: this is called from pet_touch_start(), which
 * runs after the panel is up. Do NOT move it earlier. Clocking any I2C
 * transaction before the display rail comes up kills V1 touch for the whole
 * boot. Narrowed by elimination, one variable per flash: a 500 ms delay is
 * harmless, bringing the bus up without transacting is harmless, and a single
 * register read of an address with no chip behind it kills a different chip.
 *
 * Read-only: i2c_master_probe() sends an address and looks for the ACK, and
 * nothing here writes a register.
 */
static void pet_i2c_scan(i2c_master_bus_handle_t bus)
{
    char line[256];
    int  n = 0;
    int  found = 0;

    n += snprintf(line + n, sizeof(line) - n, "i2c scan:");
    for (uint8_t addr = 0x08; addr <= 0x77 && n < (int)sizeof(line) - 8; addr++) {
        if (i2c_master_probe(bus, addr, 50 /* ms */) == ESP_OK) {
            n += snprintf(line + n, sizeof(line) - n, " %02x", addr);
            found++;
        }
    }
    if (found == 0) {
        ESP_LOGE(TAG, "i2c scan: NOTHING ACKed - bus is dead, not a variant");
    } else {
        ESP_LOGW(TAG, "%s  (%d device%s)", line, found, found == 1 ? "" : "s");
    }
}

static void pet_touch_start(lv_display_t *disp)
{
    i2c_master_bus_handle_t bus = bsp_i2c_get_handle();
    if (bus == NULL && bsp_i2c_init() == ESP_OK) {
        bus = bsp_i2c_get_handle();
    }
    if (bus == NULL) {
        ESP_LOGE(TAG, "no I2C bus - touch disabled");
        return;
    }

    pet_i2c_scan(bus);

    i2c_device_config_t dcfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = s_board_v2 ? TOUCH_ADDR_CST820 : TOUCH_ADDR_FT3168,
        /* 100 kHz, not the bus default of 400: this bus logs a pull-up warning
         * on every boot, and there is no reason to run the slowest, least
         * critical device on it at full speed. */
        .scl_speed_hz    = TOUCH_I2C_HZ,
    };
    if (i2c_master_bus_add_device(bus, &dcfg, &s_touch_dev) != ESP_OK) {
        ESP_LOGE(TAG, "touch add_device failed - touch disabled");
        return;
    }

    /*
     * Confirm it is really the touch controller before trusting its data — and
     * retry, because it is not always awake yet.
     *
     * A single probe here succeeded on one boot and failed on the next, which
     * is exactly the "intermittent touch init" this board was written off for
     * at M1. The controller takes a moment to come up and there is no reset
     * line to prod it with, so the only option is to wait and ask again.
     * Failing on the first attempt gives up on hardware that is merely slow.
     */
    const uint8_t id_reg  = s_board_v2 ? CST_REG_CHIP_ID : FT_REG_VENDOR;
    const uint8_t addr    = s_board_v2 ? TOUCH_ADDR_CST820 : TOUCH_ADDR_FT3168;
    const char   *part    = s_board_v2 ? "CST820" : "FocalTech";

    uint8_t id = 0;
    bool found = false;
    for (int attempt = 0; attempt < TOUCH_PROBE_TRIES; attempt++) {
        /*
         * V1 checks the vendor byte against 0x11 because FocalTech documents it.
         * V2 accepts ANY successful read of the chip-id register, deliberately:
         * Espressif's own cst816s driver only logs that value and never tests
         * it, so there is no vetted constant to compare against and inventing
         * one would reject working hardware. The address already ACKed during
         * variant verification, so a clean read here is the evidence. The id is
         * logged so it can be written down from a real board rather than a
         * datasheet; it reads 0xB7 on the board this was developed against.
         */
        if (touch_read_reg(id_reg, &id, 1) == ESP_OK && (s_board_v2 || id == 0x11)) {
            found = true;
            if (attempt > 0) {
                ESP_LOGW(TAG, "touch answered on attempt %d", attempt + 1);
            }
            break;
        }
        vTaskDelay(pdMS_TO_TICKS(TOUCH_PROBE_DELAY_MS));
    }

    if (!found) {
        ESP_LOGE(TAG, "no %s controller at 0x%02x after %d tries "
                      "(last id 0x%02x) - touch disabled",
                 part, addr, TOUCH_PROBE_TRIES, id);
        i2c_master_bus_rm_device(s_touch_dev);
        s_touch_dev = NULL;
        return;
    }

    lvgl_port_lock(0);
    lv_indev_t *indev = lv_indev_create();
    if (indev != NULL) {
        lv_indev_set_type(indev, LV_INDEV_TYPE_POINTER);
        lv_indev_set_read_cb(indev, touch_read_cb);
        lv_indev_set_display(indev, disp);
    }
    lvgl_port_unlock();

    if (indev == NULL) {
        ESP_LOGE(TAG, "lv_indev_create failed - touch disabled");
        return;
    }

    s_touch_ok = true;
    ESP_LOGI(TAG, "touch up - %s at 0x%02x, chip id 0x%02x", part, addr, id);
}

/*
 * Re-log what came up, a few seconds after boot.
 *
 * This board logs over the S3's native USB, which re-enumerates when the chip
 * resets — so the host cannot open the port until roughly a second in, and
 * every line before that is lost. Display and touch both initialise inside that
 * window, which makes exactly the bring-up you most want to see the part you
 * cannot. Repeated rather than one-shot because a capture often starts later
 * still.
 */
static void pet_status_task(void *arg)
{
    vTaskDelay(pdMS_TO_TICKS(4000));
    ESP_LOGI(TAG, "ready: display=1 touch=%d", s_touch_ok);
    /* Here rather than at PMIC init: early boot logs do not survive the S3's
     * native-USB re-enumeration, and the first version of this probe printed
     * into a port the host could not open yet. */
    pet_pwr_battery_probe();

    /*
     * WHICH SLOW CLOCK DID WE ACTUALLY GET?
     *
     * This is the question light sleep turns on. sdkconfig asks for a source;
     * the chip silently falls back to the internal RC when the hardware is not
     * there, and the warning it prints doing so lands in the first ~1.2 s that
     * this board's native USB cannot deliver. So it is asked at runtime, where
     * the answer survives.
     *
     * XTAL32K means a 32.768 kHz crystal is fitted and BLE could keep accurate
     * time through light sleep. RC_SLOW means it cannot, whatever sdkconfig
     * claims — which is the difference between "light sleep is switched off"
     * and "light sleep is unavailable on this hardware".
     */
    const char *slow_src = "unknown";
    switch (rtc_clk_slow_src_get()) {
    case SOC_RTC_SLOW_CLK_SRC_RC_SLOW:      slow_src = "internal RC (~150 kHz)";  break;
    case SOC_RTC_SLOW_CLK_SRC_XTAL32K:      slow_src = "EXTERNAL 32.768 kHz XTAL"; break;
    case SOC_RTC_SLOW_CLK_SRC_RC_FAST_D256: slow_src = "internal RC_FAST/256";     break;
    default: break;
    }
    ESP_LOGW(TAG, "rtc slow clock: %s", slow_src);
    /* Logged from here rather than at display bring-up: nothing printed before
     * ~1.2 s survives the native-USB re-enumeration. The largest *contiguous*
     * block is the number that mattered for the flush failure, not the total. */
    ESP_LOGI(TAG, "heap: %u B internal free, %u largest block",
             (unsigned)heap_caps_get_free_size(MALLOC_CAP_INTERNAL),
             (unsigned)heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL));

    /*
     * Watch for the LVGL task stopping, from a task that cannot stop with it.
     */
    uint32_t prev_beats = s_lvgl_beats;
    uint32_t stalled_ticks = 0;
    uint32_t secs = 0;
    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(1000));
        const uint32_t beats = s_lvgl_beats;

        /*
         * Backstop: unstick a display flush that was never acknowledged.
         *
         * A flush that fails to reach the bus leaves LVGL's flushing flag set
         * with no completion callback ever coming, and lv_timer_handler then
         * spins in wait_for_flushing forever — a dead screen needing a power
         * cycle. The draw buffers being internal (see pet_display_start) removes
         * the cause we found, but the failure mode is silent and total, so it is
         * worth a net underneath it: a pet that glitches a frame beats one that
         * has to be unplugged.
         *
         * lv_display_flush_ready only clears that flag and is called from an ISR
         * in normal operation, so calling it from here is safe. The heartbeat
         * ticks regardless of what is on screen, so a stalled count means the
         * LVGL task is not running — never merely an idle UI.
         */
        if (beats == prev_beats) {
            if (++stalled_ticks >= 3 && s_disp != NULL) {
                ESP_LOGE(TAG, "LVGL stalled %lu s - forcing flush_ready. This "
                              "should not happen; capture the log around it.",
                         (unsigned long)stalled_ticks);
                lvgl_port_flush_ready(s_disp);
            }
        } else {
            stalled_ticks = 0;
        }

        /* A terse liveness line every 30 s. The whole point of the freeze that
         * cost this project a session was that there was no way to tell a live
         * UI from a dead one without watching the screen. */
        if (++secs % 30 == 0) {
            /* Battery goes on the SAME line as the liveness counters, on
             * purpose: the whole question this exists to answer is "what was
             * the pet doing while the charge went down", and that is only
             * answerable if both are in one place with one timestamp. */
            uint8_t pct = 0xFF; uint16_t mv = 0; bool chg = false;
            const bool have_batt = pet_pwr_battery(&pct, &mv, &chg);

            /*
             * Push a Condition notification when the charge has actually moved.
             *
             * Condition is otherwise notified only when the SIMULATION changes,
             * which at real rates is roughly twice an hour — far too coarse to
             * plot a discharge against. Gated on a real change rather than sent
             * every 30 s so an idle, charged pet still says nothing: the point
             * of the notify-on-change design is that a quiet pet is quiet.
             *
             * 20 mV is a little over the ADC's own wobble and about a minute of
             * discharge, so a trace has enough points to have a slope.
             */
            /* Repeated for the first few ticks, not logged once at boot: the
             * probe has now been missed five times because nothing can attach
             * to the S3's native USB before ~1.2 s and a hand power-cycle puts
             * the host seconds behind that. Bounded so it does not become
             * permanent noise. */
            static int probe_ticks;
            if (probe_ticks < 5) {
                probe_ticks++;
                pet_pwr_battery_probe();
            }

            static uint8_t  last_pct = 0xFF;
            static uint16_t last_mv;
            if (have_batt &&
                (pct != last_pct ||
                 (mv > last_mv ? mv - last_mv : last_mv - mv) >= 20)) {
                last_pct = pct;
                last_mv  = mv;
                pet_ble_notify_condition();
            }

            ESP_LOGI(TAG, "alive: lvgl %lu, %u B internal free, %u largest block, "
                          "%s, batt %d%% %u mV%s",
                     (unsigned long)beats,
                     (unsigned)heap_caps_get_free_size(MALLOC_CAP_INTERNAL),
                     (unsigned)heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL),
                     pet_is_asleep() ? "asleep" : "awake",
                     have_batt ? (pct == 0xFF ? -1 : (int)pct) : -1,
                     (unsigned)mv, chg ? " CHG" : "");
        }

        /* Checked every second rather than on the 30 s line: the timeout should
         * be honoured to the second it expires, not rounded up to half a
         * minute. Runs here because this task cannot wedge. */
        sleep_tick();
        /* After sleep_tick, never before: it decides whether the pet is awake,
         * and a demonstration queued for a pet that is about to dim would be
         * torn down again on the next pass. */
        teach_tick();

        prev_beats = beats;
    }
}

/* DigitalPet palette (from DESIGN.md): dark bg, cyan, pink. AMOLED loves black. */
/*
 * THE PET'S FACE IS THE BRAND GOLD, as of 2026-08-09.
 *
 * It was 0x00E5FF cyan, and that cyan was never chosen: it came from
 * placeholder text in an early draft of DESIGN.md — literally
 * `*[e.g., #FF4081 (Pink)]*` — which the firmware adopted. It appears in no
 * colour scheme on either phone surface, and DESIGN.md §4 has named the fix for
 * months: "the pet should move to the app's palette; two #defines".
 *
 * 0xF5A623 is PetGold, which is `primary` in BOTH the light and dark schemes,
 * so the pet's face, the app's panel and the notification are now one colour
 * rather than three surfaces guessing.
 *
 * Both constraints from the design system still hold: the face is ONE colour,
 * because each additional fill is another LVGL object on a device where
 * internal RAM is the binding constraint; and the background stays pure black,
 * because unlit AMOLED pixels cost no battery and this device is measured.
 */
/*
 * THE FACE COMES FROM A SET NOW, not from these defines.
 *
 * `pet_faces.h` is generated from `design-system/faces/` — see that
 * directory's README for why the design system owns a face and the code no
 * longer does. `s_set` is whichever set is active; it is never NULL, because
 * index 0 is a compiled-in fallback and selection refuses anything it does not
 * recognise.
 *
 * The two colour macros below are kept, and kept named the same, because they
 * are used all over this file for things that are not the face — the mic
 * button, the teach ripples, the spoken line. They now read through the set, so
 * a theme reaches those too rather than leaving a gold button on a pink pet.
 * Anything already drawn keeps its old colour until pet_face_set_apply()
 * restyles it, which is why that function exists.
 */
static const pet_face_set_t *s_set = &pet_face_sets[0];

#define PET_COLOR_EYE   lv_color_hex(s_set->color_eye)
#define PET_COLOR_MOUTH lv_color_hex(s_set->color_mouth)
#define PET_COLOR_PANEL lv_color_hex(s_set->color_panel)
#define PET_COLOR_CONTROL lv_color_hex(s_set->color_control)

/* How loud the talk button is when it is NOT recording. The ring and the
 * dot share it: dimming one and leaving the other at full strength is the
 * mistake this constant exists to stop being made twice. */
#define PET_CONTROL_IDLE_OPA LV_OPA_40

/* -1 in a set means "as round as it goes"; LVGL spells that 0x7FFF. */
static inline int32_t face_radius(int16_t r)
{
    return r < 0 ? LV_RADIUS_CIRCLE : r;
}

/*
 * WHICH SET IS ACTIVE SURVIVES A POWER CYCLE.
 *
 * Stored as the set's ID STRING, not its index. An index would be one byte
 * smaller and would silently mean a different face the first time a set was
 * added to the middle of the list — the pet would come back wearing someone
 * else's face and nothing would look wrong in the code.
 */
#define FACE_NVS_NAMESPACE "petface"
#define FACE_NVS_KEY       "setid"

static void face_set_store(const char *id)
{
    nvs_handle_t h;
    if (nvs_open(FACE_NVS_NAMESPACE, NVS_READWRITE, &h) != ESP_OK) {
        ESP_LOGE(TAG, "nvs_open failed - the face set will not survive a reboot");
        return;
    }
    if (nvs_set_str(h, FACE_NVS_KEY, id) == ESP_OK) {
        nvs_commit(h);
    }
    nvs_close(h);
}

/* The set with this id, or NULL. Deliberately not "or the default": callers
 * need to tell "you asked for something I do not have" from "here is a set". */
static const pet_face_set_t *face_set_find(const char *id)
{
    if (id == NULL) {
        return NULL;
    }
    for (int i = 0; i < PET_FACE_SET_COUNT; i++) {
        if (strcmp(pet_face_sets[i].id, id) == 0) {
            return &pet_face_sets[i];
        }
    }
    return NULL;
}

/* The old literal, retained only as documentation of where the gold came from:
 * #define PET_COLOR_EYE lv_color_hex(0xF5A623)  PetGold, `primary` on the phone */
/*
 * The pet's spoken line. It was 0xFF4081 — the literal pink from that same
 * placeholder — and it is NOT the face, so it does not take the face's gold.
 *
 * This is text on a dark surface, and the palette already has a colour for
 * that: OnDarkSurface, the dark scheme's `onSurface`. Gold is the identity and
 * belongs to the face; making the speech gold too would say the words are the
 * pet rather than what it said. The app does the same thing in the transcript —
 * only YOUR bubble is gold, and everything read is onSurface.
 *
 * Derived from the palette rather than chosen, which is the honest description:
 * DESIGN.md §7.3 said these two needed "values nobody has chosen", and the face
 * was a real choice while this one falls out of where the text sits.
 */
#define PET_COLOR_TEXT  lv_color_hex(s_set->color_text) /* was 0xE8E6F0, OnDarkSurface */

/* The link dot. Taken from android/…/ui/theme/Color.kt so the two surfaces
 * agree — SuccessGreen and ErrorRed. PET_COLOR_EYE and PET_COLOR_TEXT above are
 * from the same file as of 2026-08-09; the placeholder cyan and pink they used
 * to hold were the last two colours in this product that were in no scheme. */
#define PET_COLOR_LINK_UP   lv_color_hex(0x4CAF50)  /* SuccessGreen */
#define PET_COLOR_LINK_DOWN lv_color_hex(0xFF4C4C)  /* ErrorRed     */

/* Eye geometry (open state) and resting vertical position — the active set's,
 * not constants. EYE_W/EYE_H were 74/96 and EYE_Y -56, which are now
 * classic.json's neutral face and its eyeOffsetY. */
#define EYE_W  (s_set->face[PET_FACE_NEUTRAL].eye_w)
#define EYE_H  (s_set->face[PET_FACE_NEUTRAL].eye_h)
#define EYE_Y  (s_set->eye_off_y)

/* The eyes carry most of the expression, so moods resize them as well as the
 * mouth. Changing only the mouth (140x18 "happy" vs 120x16 "neutral") was not
 * perceptible on the panel. Blinking animates between the current open height
 * and closed, so the open height has to be per-mood state, not a constant. */
static lv_obj_t *s_eyes[2];
/* Not initialised from EYE_W/EYE_H any more: those read through the active set
 * now, which is a runtime expression and cannot initialise a static. Both are
 * written by apply_mood() before the eyes are ever drawn. */
/* Which face the animations should move like. Written by apply_mood()
 * before it re-arms them; the two helpers read it rather than taking a
 * parameter, because eyes_apply_shape() re-arms the blink from three
 * different places. */
static pet_face_slot_t s_face_slot = PET_FACE_NEUTRAL;
/* PER EYE, because the two are allowed to differ now — an asymmetric face reads
 * as queasy in a way two identical eyes cannot. On the sick face, the one that
 * uses it, these are the two spirals' diameters and strokes. Index 0 is left. */
static int32_t   s_eye_open_w[2];
static int32_t   s_eye_open_h[2];
/* Where the eyes sit, gaze included. The blink callback re-aligns on every
 * frame and has to land on the same line the mood chose, so this is resolved
 * once per mood rather than recomputed from the set in three places. */
static int32_t   s_eye_y;

/* An expression is a reaction, not a state: it decays back to neutral so the
 * pet does not sit there looking startled forever. Kept in firmware rather than
 * scheduled from the phone so the face still settles if BLE drops. */
#define MOOD_TIMEOUT_MS 10000

/*
 * THE CHROME GETS OUT OF THE WAY.
 *
 * The talk button and the spoken line are useful when you are talking to the
 * pet and clutter the rest of the time — and the rest of the time is nearly all
 * of it, because this thing sits on a shelf being looked at. DESIGN.md §6 says
 * the face is the primary state display; two controls parked on top of it are
 * two things competing with the only thing that matters.
 *
 * Shorter than the sleep timeout on purpose. Hiding the chrome is cosmetic and
 * instantly reversible, so it can afford to be eager; sleeping stops the
 * animations and is a bigger claim about what the pet is doing.
 */
#define CHROME_TIMEOUT_MS 9000
static lv_timer_t *s_mood_timer;
static lv_timer_t *s_chrome_timer;
static bool        s_chrome_shown = true;

/* --- LVGL animation callbacks (transform-free: no render layers) --- */

/* Blink: shrink the eye's height and re-center it (closes toward its middle). */
static void anim_eye_blink_cb(void *var, int32_t h)
{
    lv_obj_t *eye = (lv_obj_t *)var;
    int x = (int)(intptr_t)lv_obj_get_user_data(eye); /* stored x, gaze included */
    lv_obj_set_height(eye, h);
    lv_obj_align(eye, LV_ALIGN_CENTER, x, s_eye_y);
}

/* Breathe: a plain style translate (no layer, safe with a partial buffer). */
static void anim_translate_y_cb(void *var, int32_t v)
{
    lv_obj_set_style_translate_y((lv_obj_t *)var, v, 0);
}

static void add_blink(lv_obj_t *eye)
{
    lv_anim_t a;
    lv_anim_init(&a);
    lv_anim_set_var(&a, eye);
    lv_anim_set_exec_cb(&a, anim_eye_blink_cb);
    /* Open height is per-mood; a sleepy eye blinks from its half-shut size. */
    const int idx = (eye == s_eyes[1]) ? 1 : 0;
    lv_anim_set_values(&a, s_eye_open_h[idx], 10);
    /*
     * THE RHYTHM IS PER-FACE NOW, from the set's data.
     *
     * Every mood used to blink on the same 5400 ms and breathe on the same
     * 1900 ms, so a dying pet and a delighted one moved identically. A sick pet
     * that blinks fast and breathes shallow reads as ill before you have looked
     * at its mouth, and it costs nothing: the animation was already running.
     *
     * The cycle is the shut time plus the gap. Halving the lid movement keeps a
     * blink feeling like a blink rather than a slow wink at long cycles.
     */
    const pet_face_geom_t *g = &s_set->face[s_face_slot];
    const int32_t shut = g->shut_ms > 0 ? g->shut_ms / 2 : 90;
    const int32_t gap = g->blink_ms > 2 * shut ? g->blink_ms - 2 * shut : 3200;
    lv_anim_set_duration(&a, shut);
    lv_anim_set_playback_duration(&a, shut);
    lv_anim_set_repeat_count(&a, LV_ANIM_REPEAT_INFINITE);
    lv_anim_set_repeat_delay(&a, gap);
    lv_anim_start(&a);
}

/* --- spiral eyes -----------------------------------------------------------
 *
 * The face the pet wears when it is ill: two counter-wound spirals, turning.
 *
 * WHY A POLYLINE AND NOT A ROTATION TRANSFORM. LVGL will rotate an object for
 * you with `transform_rotation`, which would spin a static spiral for nothing —
 * and a transformed object is rendered through an intermediate layer that LVGL
 * allocates. **This is the board where a silent allocation failure in the draw
 * path froze the screen** — PSRAM draw buffers forced a per-flush DMA bounce
 * allocation that failed silently — and every animation in this
 * file is deliberately transform-free for that reason; the callbacks above say
 * so in their own header. Rewriting 49 points costs one sinf and one cosf per
 * frame and allocates nothing.
 *
 * THE SHAPE IS STILL GENERATED. What happens here is a rotation and a vertical
 * squash of a table pet_faces.h computed once for all five renderers — the
 * device does not derive the curve, any more than it derives the arc of a
 * smile. See arc_of() in tools/gen-faces.py for why that matters.
 */
static lv_point_precise_t s_spiral_pts[2][PET_SPIRAL_MAX_POINTS];
/* Where each eye is in its turn, in degrees, and how far open it is: 100 is
 * open, 0 is shut. **The blink SQUASHES a spiral rather than shrinking it** —
 * a lid closing over a pattern flattens it, and scaling it down instead reads
 * as the pet's eyes retreating into its head. */
static int32_t s_spiral_deg[2];
static int32_t s_spiral_open[2] = { 100, 100 };

static void spiral_rebuild(int i)
{
    lv_obj_t *obj = s_eye_spirals[i];
    const pet_face_geom_t *g = &s_set->face[s_face_slot];
    const pet_spiral_t *sp = pet_spiral_of(g->eye_spiral_turns);
    if (obj == NULL || sp == NULL || sp->n > PET_SPIRAL_MAX_POINTS) {
        return;
    }
    /* eye_w is the outer DIAMETER when the eyes are spirals and eye_h the
     * stroke — the same reinterpretation a curved eye makes of its height. The
     * pet grows, so both carry the stage scale like every other size. */
    const int pct = s_set->stage_pct[pet_sim_stage()];
    const int32_t r = (i == 0 ? g->eye_w : g->eye_rw) * pct / 200;
    const int32_t thick = (i == 0 ? g->eye_h : g->eye_rh) * pct / 100;
    const int32_t half = r + thick / 2 + 1;   /* the object's own centre */
    const float a = (float)s_spiral_deg[i] * (float)M_PI / 180.0f;
    const float ca = cosf(a), sa = sinf(a);
    const float squash = (float)s_spiral_open[i] / 100.0f;
    const int16_t *pts = (i == 0) ? sp->left : sp->right;
    for (uint16_t k = 0; k < sp->n; k++) {
        const float x = (float)pts[2 * k] * r / PET_SPIRAL_UNIT;
        const float y = (float)pts[2 * k + 1] * r / PET_SPIRAL_UNIT;
        /* Rotate, THEN squash. The other order spins an already-flattened
         * spiral around its centre, which reads as a wobble rather than as an
         * eye closing over something. */
        s_spiral_pts[i][k].x = (lv_value_precise_t)(half + (x * ca - y * sa));
        s_spiral_pts[i][k].y = (lv_value_precise_t)(half + (x * sa + y * ca) * squash);
    }
    lv_obj_set_size(obj, 2 * half, 2 * half);
    lv_obj_set_style_line_width(obj, thick, 0);
    /* Re-set rather than mutated in place: lv_line holds the pointer, and this
     * is also what invalidates the object so the new points are drawn. */
    lv_line_set_points(obj, s_spiral_pts[i], sp->n);
}

static int spiral_index(void *var)
{
    return ((lv_obj_t *)var == s_eye_spirals[1]) ? 1 : 0;
}

static void anim_spiral_spin_cb(void *var, int32_t deg)
{
    const int i = spiral_index(var);
    s_spiral_deg[i] = deg;
    spiral_rebuild(i);
}

static void anim_spiral_blink_cb(void *var, int32_t open)
{
    const int i = spiral_index(var);
    s_spiral_open[i] = open;
    spiral_rebuild(i);
}

/* The turn. 0 to 360 and straight back to 0, with no playback leg: a spiral
 * that unwound as it re-wound would rock rather than spin, and 360 IS 0, so
 * the restart is invisible. */
static void add_spin(lv_obj_t *obj)
{
    const pet_face_geom_t *g = &s_set->face[s_face_slot];
    lv_anim_t a;
    lv_anim_init(&a);
    lv_anim_set_var(&a, obj);
    lv_anim_set_exec_cb(&a, anim_spiral_spin_cb);
    lv_anim_set_values(&a, 0, 360);
    lv_anim_set_duration(&a, g->spin_ms > 0 ? g->spin_ms : 2600);
    lv_anim_set_repeat_count(&a, LV_ANIM_REPEAT_INFINITE);
    lv_anim_start(&a);
}

/* The blink, on the same rhythm add_blink() uses — the shut time plus the gap,
 * from the face's own data — but closing a squash instead of a height. */
static void add_spiral_blink(lv_obj_t *obj)
{
    const pet_face_geom_t *g = &s_set->face[s_face_slot];
    const int32_t shut = g->shut_ms > 0 ? g->shut_ms / 2 : 90;
    const int32_t gap = g->blink_ms > 2 * shut ? g->blink_ms - 2 * shut : 3200;
    lv_anim_t a;
    lv_anim_init(&a);
    lv_anim_set_var(&a, obj);
    lv_anim_set_exec_cb(&a, anim_spiral_blink_cb);
    lv_anim_set_values(&a, 100, 8);
    lv_anim_set_duration(&a, shut);
    lv_anim_set_playback_duration(&a, shut);
    lv_anim_set_repeat_count(&a, LV_ANIM_REPEAT_INFINITE);
    lv_anim_set_repeat_delay(&a, gap);
    lv_anim_start(&a);
}

/* Resize the eyes for the current mood and restart blinking against the new
 * open height (the running animation still holds the old values, so it has to
 * be replaced rather than left to fight the new size). */
static void eyes_apply_shape(void)
{
    /*
     * A dead pet does not blink and does not breathe.
     *
     * This is the part of the dead face that actually reads as dead. A still
     * shape is just a shape; it is the blink and the slow rise and fall that the
     * eye interprets as alive, so removing them says more than any drawing of
     * closed eyes does. Both animations are deleted rather than paused, because
     * a paused animation still holds the object at whatever value it stopped on.
     */
    /* Asleep counts the same as dead here: the blink and the breathe are the
     * whole reason this firmware repaints continuously, and stopping them is
     * most of what sleeping is for. */
    /*
     * "Does not move" is DATA now, not a list of conditions.
     *
     * This was `dead || asleep`, which meant the rule lived here and the sets
     * could not express it. A face with blink_ms of 0 does not blink and one
     * with breathe_ms of 0 does not breathe, so the dead face carries its own
     * stillness — and a set can make any state as still as it likes without
     * touching this file.
     */
    const pet_face_geom_t *fg = &s_set->face[s_face_slot];
    const bool still = (fg->breathe_ms <= 0) || pet_sim_is_dead() || pet_is_asleep();
    const bool no_blink = (fg->blink_ms <= 0) || still;

    for (int i = 0; i < 2; i++) {
        lv_obj_t *eye = s_eyes[i];
        if (!eye) {
            continue;
        }
        lv_anim_delete(eye, anim_eye_blink_cb);
        /* The gaze moves both eyes, so the stored x is rewritten here rather
         * than only at creation — a mood that looks away has to move them. */
        const int32_t gx = s_set->face[s_face_slot].gaze_x;
        const int32_t x = (i == 0 ? -s_set->eye_off_x : s_set->eye_off_x) + gx;
        lv_obj_set_user_data(eye, (void *)(intptr_t)x);
        /*
         * A CURVED EYE IS A DIFFERENT WIDGET, chosen here rather than drawn as
         * a very flat lozenge — which is what "squinted" used to come out as,
         * and why the happy face never read as the "^ ^" its own comment in
         * apply_mood() has always claimed.
         */
        const pet_face_geom_t *eg = &s_set->face[s_face_slot];
        lv_obj_t *ea = s_eye_arcs[i];
        lv_obj_t *so = s_eye_spirals[i];
        const pet_spiral_t *spi = pet_spiral_of(eg->eye_spiral_turns);
        const bool spiralling = (spi != NULL) && (so != NULL);
        /* The spin and the squash belong to the spiral and to nothing else, so
         * they are torn down on every mood change and rebuilt only if this
         * face wants them. A spin left running against a hidden object is the
         * dead pet's heartbeat all over again: invisible, and still waking
         * LVGL every frame. */
        if (so != NULL) {
            lv_anim_delete(so, anim_spiral_spin_cb);
            lv_anim_delete(so, anim_spiral_blink_cb);
        }
        if (spiralling) {
            /*
             * SPIRAL EYES: the illness, and the third of the three forms an eye
             * can take. The pet used to say "sick" with two thin lids, which is
             * also roughly what it says when it is sleepy, asleep or dead — the
             * one state the user is meant to ACT on looked like the three they
             * cannot. A spiral is worn by nothing else at any size.
             */
            lv_obj_add_flag(eye, LV_OBJ_FLAG_HIDDEN);
            if (ea != NULL) {
                lv_obj_add_flag(ea, LV_OBJ_FLAG_HIDDEN);
            }
            lv_obj_remove_flag(so, LV_OBJ_FLAG_HIDDEN);
            s_spiral_deg[i] = 0;
            s_spiral_open[i] = 100;
            spiral_rebuild(i);
            lv_obj_align(so, LV_ALIGN_CENTER, x, s_eye_y);
            if (!still) {
                add_spin(so);
            }
            if (!no_blink) {
                add_spiral_blink(so);
            }
        } else if (eg->eye_arc_r > 0 && ea != NULL) {
            const int pct2 = s_set->stage_pct[pet_sim_stage()];
            const int32_t box = 2 * eg->eye_arc_r * pct2 / 100;
            lv_obj_add_flag(eye, LV_OBJ_FLAG_HIDDEN);
            lv_obj_remove_flag(ea, LV_OBJ_FLAG_HIDDEN);
            lv_obj_set_size(ea, box, box);
            /* The eye's HEIGHT is the stroke when it curves — one concept, the
             * same relationship mouth thickness has to mouth width. The blink
             * animates it, so it comes from s_eye_open_h rather than the set. */
            lv_obj_set_style_arc_width(ea, s_eye_open_h[i], LV_PART_INDICATOR);
            lv_obj_set_style_arc_rounded(ea, true, LV_PART_INDICATOR);
            lv_arc_set_bg_angles(ea, eg->eye_arc_start, eg->eye_arc_end);
            lv_arc_set_angles(ea, eg->eye_arc_start, eg->eye_arc_end);
            lv_obj_align(ea, LV_ALIGN_CENTER, x,
                         s_eye_y + eg->eye_arc_dy * pct2 / 100);
        } else {
            if (ea != NULL) {
                lv_obj_add_flag(ea, LV_OBJ_FLAG_HIDDEN);
            }
            lv_obj_remove_flag(eye, LV_OBJ_FLAG_HIDDEN);
        }
        if (!spiralling && so != NULL) {
            lv_obj_add_flag(so, LV_OBJ_FLAG_HIDDEN);
        }
        lv_obj_set_size(eye, s_eye_open_w[i], s_eye_open_h[i]);
        lv_obj_align(eye, LV_ALIGN_CENTER, x, s_eye_y);
        if (still) {
            lv_anim_delete(eye, anim_translate_y_cb);
            lv_obj_set_style_translate_y(eye, 0, 0);
        } else {
            /* Re-armed, not left running: the breathe's period and depth are
             * per-face now, so a mood change that did not restart it would
             * leave the pet moving like the mood it just left. */
            lv_anim_delete(eye, anim_translate_y_cb);
            add_breathe(eye);
        }
        if (!no_blink && !spiralling) {
            add_blink(eye);
        }
    }

    /* Both mouths: one of them is hidden, but a hidden object with a live
     * animation still wakes LVGL every frame, and the point of the dead face is
     * that nothing about it moves. */
    lv_obj_t *const mouths[] = { s_mouth, s_mouth_arc,
                                 s_eye_arcs[0], s_eye_arcs[1],
                                 s_eye_spirals[0], s_eye_spirals[1] };
    for (size_t i = 0; i < sizeof(mouths) / sizeof(mouths[0]); i++) {
        if (mouths[i] == NULL) {
            continue;
        }
        lv_anim_delete(mouths[i], anim_translate_y_cb);
        if (still) {
            lv_obj_set_style_translate_y(mouths[i], 0, 0);
        } else {
            add_breathe(mouths[i]);
        }
    }
}

/* --- teaching a gesture (DESIGN.md §5.5) -----------------------------------
 *
 * The pet demonstrates the gesture rather than captioning it. That choice is
 * forced as much as chosen: §6.4 says the pet's library is geometry, not
 * widgets, and it has no icon set, no room for a sentence, and a user who may
 * not read English. A rhythm, though, is geometry — and the rhythm is exactly
 * the part of these two gestures that cannot be guessed. A tap is obvious; that
 * it must be TWO taps is not. Motion is obvious; that it must be SUSTAINED
 * motion is not.
 *
 * So: the double tap is two ripples in the rhythm of a double tap, and the shake
 * is a mark moving side to side without stopping. Each demonstration is the
 * thing it is teaching, played at the speed you would have to do it.
 *
 * PLACEMENT IS THE PART TO CHECK ON HARDWARE. It sits below the mouth and above
 * the text line, which is free space on the 448x368 screen — but a ripple that is
 * meant to say "tap the pet" is arguably in the wrong place anywhere except on
 * the face, and that is a judgement to make by looking at it, not in a header.
 */
#define TEACH_Y            110      /* below the mouth (+40), clear of the text */
#define TEACH_RIPPLE_MIN    20
#define TEACH_RIPPLE_MAX    84
#define TEACH_RIPPLE_MS    420      /* one tap's ripple */
#define TEACH_TAP_GAP_MS   300      /* the gap that makes it a DOUBLE tap */
#define TEACH_REST_MS     2200      /* before the demonstration repeats */
#define TEACH_DOT_SIZE      26
#define TEACH_SHAKE_X       70
#define TEACH_SHAKE_MS     170      /* one direction; a shake you could copy */

static lv_obj_t *s_teach[2];
static uint8_t   s_teach_shown = PET_TEACH_NONE;

/* Ripple: the mark grows and fades, the way a touch does under a finger. Size
 * and opacity are driven off the same value so they cannot drift apart. */
static void anim_teach_ripple_cb(void *var, int32_t d)
{
    lv_obj_t *o = (lv_obj_t *)var;
    lv_obj_set_size(o, d, d);
    lv_obj_align(o, LV_ALIGN_CENTER, 0, TEACH_Y);

    const int32_t span = TEACH_RIPPLE_MAX - TEACH_RIPPLE_MIN;
    const int32_t gone = d - TEACH_RIPPLE_MIN;
    lv_obj_set_style_bg_opa(o, (lv_opa_t)(LV_OPA_70 - (LV_OPA_70 * gone) / span), 0);
}

static void anim_teach_slide_cb(void *var, int32_t x)
{
    lv_obj_set_style_translate_x((lv_obj_t *)var, x, 0);
}

static void add_breathe(lv_obj_t *obj)
{
    lv_anim_t a;
    lv_anim_init(&a);
    lv_anim_set_var(&a, obj);
    lv_anim_set_exec_cb(&a, anim_translate_y_cb);
    const pet_face_geom_t *g = &s_set->face[s_face_slot];
    lv_anim_set_values(&a, g->breathe_from, g->breathe_to);
    lv_anim_set_duration(&a, g->breathe_ms);
    lv_anim_set_playback_duration(&a, g->breathe_ms);
    lv_anim_set_repeat_count(&a, LV_ANIM_REPEAT_INFINITE);
    lv_anim_set_path_cb(&a, lv_anim_path_ease_in_out);
    lv_anim_start(&a);
}

/* Expression decayed — go back to resting. Runs on the LVGL task, so it hands
 * the change to the UI queue rather than re-entering apply_mood (and the LVGL
 * lock) from inside a timer callback. */
static void mood_timeout_cb(lv_timer_t *timer)
{
    const uint8_t resting = pet_sim_baseline_mood();

    lv_timer_pause(timer);
    pet_set_mood(resting);

    /*
     * Tell the phone the face reverted, so its idea of the expression does not
     * drift from what is on screen — but only for values it understands.
     * PET_MOOD_SAD and PET_MOOD_SICK are pet-local (see pet.h) and the phone's
     * enum stops at 3, so sending either would decode as garbage.
     *
     * Nothing is lost by not sending them: since v4 the phone learns both from
     * the Condition characteristic, which carries the scores and the sick flag
     * directly. Mood is the phone telling the pet how to look; Condition is the
     * pet telling the phone how it is. A resting face is the second thing.
     */
    if (resting <= PET_MOOD_SURPRISED) {
        pet_ble_notify(PET_EVT_MOOD, &resting, sizeof(resting));
    }
}

/* Talk button geometry. The tap target is padded well beyond the drawn circle
 * because a fingertip is much larger than a 64px dot. */
#define MIC_BTN_SIZE      64
#define MIC_DOT_SIZE      26
#define MIC_BTN_TOUCH_PAD 20

/* Runs on the LVGL task. pet_mic_set_listening only flips a flag and signals
 * the mic worker, so this never blocks the UI. */
static void mic_btn_cb(lv_event_t *e)
{
    LV_UNUSED(e);
    pet_activity();
    const bool listening = pet_mic_is_listening();
    ESP_LOGI(TAG, "talk button -> %s", listening ? "stop" : "start");
    /* The button is a child with its own handler, so a press never reaches
     * face_tap_cb and would not otherwise restart the clock — the chrome could
     * vanish seconds after you deliberately used it. */
    pet_chrome_wake();
    pet_mic_set_listening(!listening);
}

/*
 * DOUBLE tapping the pet feeds it — satiety +1.
 *
 * Single tap was far too easy to trigger by accident: putting the pet down,
 * brushing the screen, catching it while picking it up. A double tap is
 * unambiguous and costs the user nothing.
 *
 * Bound to the screen rather than a widget, so anywhere that is not the talk
 * button counts. The talk button consumes its own clicks, so the two do not
 * collide. A double tap on a SICK pet cures instead of feeding — you tend to
 * the most pressing need, and curing costs you the feed.
 *
 * LVGL does emit LV_EVENT_DOUBLE_CLICKED, but it is not used here: it fires
 * alongside a first CLICKED, so a "single tap does nothing" rule still has to be
 * written by hand. Timing two clicks keeps that logic in one place.
 */
/*
 * 600 ms, and measured rather than guessed.
 *
 * LV_EVENT_CLICKED fires on RELEASE, so this window spans first-release to
 * second-release — it therefore has to contain the whole second press, not just
 * the gap between taps. 400 ms was the first guess and was too tight for that;
 * every tap now logs its interval so the number comes from real fingers.
 */
#define DOUBLE_TAP_MS 600

/* Taps seen and pairs matched, for the HUD — a single tap is otherwise
 * completely invisible, which makes "it didn't register" impossible to tell
 * apart from "it registered and was refused". */
static volatile uint32_t s_taps;
static volatile uint32_t s_double_taps;

/*
 * A sustained shake both wakes the pet AND plays with it, unlike a tap, which
 * only wakes. The gesture is already unambiguous — a full second of continuous
 * 1 g oscillation is not something that happens by accident — so there is
 * nothing to disambiguate by consuming it, and making someone shake the thing
 * twice would be obtuse.
 *
 * The hold came down from three seconds to one on 2026-08-11; SHAKE_HOLD_MS in
 * pet_imu.c has the reasoning and the false positive to watch for.
 */
static void on_shake(void)
{
    pet_activity();
    pet_sim_play();
}

static void face_tap_cb(lv_event_t *e)
{
    LV_UNUSED(e);
    /*
     * A tap on a sleeping pet wakes it and is CONSUMED — it does not also count
     * toward a double tap. Waking something up and feeding it are different
     * intentions, and a first touch after five minutes away is much more likely
     * to be "hello" than the first half of a feed.
     */
    /*
     * A tap brings the chrome back — and does NOT consume itself doing it.
     *
     * Deliberately unlike the sleeping case below. Waking is a real state
     * change and worth a whole tap; the chrome is cosmetic and instantly
     * reversible, so consuming the tap would make feeding a hidden pet three
     * touches instead of two and protect against nothing. The reveal is a free
     * side effect of a tap that still counts.
     */
    pet_chrome_wake();

    if (pet_is_asleep()) {
        pet_activity();
        return;
    }
    pet_activity();

    static int64_t last_tap_us;
    const int64_t now_us = esp_timer_get_time();
    const int64_t gap_ms = last_tap_us == 0 ? -1 : (now_us - last_tap_us) / 1000;

    s_taps++;
    ESP_LOGI(TAG, "tap %lu (%lld ms since last, window %d)",
             (unsigned long)s_taps, (long long)gap_ms, DOUBLE_TAP_MS);

    if (last_tap_us != 0 && gap_ms < DOUBLE_TAP_MS) {
        last_tap_us = 0;          /* consumed: a third tap starts a new pair */
        s_double_taps++;
        /*
         * You tend to the most pressing need. A sick pet is being actively
         * harmed, so the same gesture that would have fed it cures it instead —
         * and curing therefore COSTS the feed, which is the cheapest honest
         * price to put on it.
         *
         * Note what this does not do: it does not tell the phone anything. If
         * the user is still in the app, the next usage poll reports overuse and
         * the pet is ill again within the minute. See pet_sim_cure().
         */
        if (pet_sim_is_sick()) {
            ESP_LOGI(TAG, "double tap -> cure");
            pet_sim_cure();
        } else {
            ESP_LOGI(TAG, "double tap -> feed");
            pet_sim_feed();
        }
        return;
    }
    last_tap_us = now_us;
}

/* Feeds the stall backstop in pet_status_task(); see s_lvgl_beats. */
static void lvgl_heartbeat_cb(lv_timer_t *t)
{
    LV_UNUSED(t);
    s_lvgl_beats++;
}

/*
 * TEMPORARY on-screen debug HUD (`idf.py build -DPET_DEBUG_HUD=1`).
 *
 * Phase 1's two gestures can only be tested by hand, and reading the scores off
 * a serial cable while shaking the thing is awkward. This puts them on the pet
 * itself, along with the live accelerometer reading — so SHAKE_LSB can be tuned
 * by shaking and watching, rather than by guessing and re-flashing.
 *
 * It PULLS from getters on an LVGL timer rather than having pet_sim or pet_imu
 * push to it. Those run on their own tasks, and anything they pushed would have
 * to go through the UI queue to avoid taking the LVGL lock off-task (see the
 * note in pet.h). Pulling from the LVGL task sidesteps that entirely.
 *
 * Delete with the rest of phase 1's scaffolding once the loop is tuned.
 */
#ifndef PET_DEBUG_HUD
#define PET_DEBUG_HUD 0
#endif

#if PET_DEBUG_HUD
static lv_obj_t *s_hud;

static const char *mood_name(uint8_t m)
{
    switch (m) {
    case PET_MOOD_HAPPY:     return "HAPPY";
    case PET_MOOD_SLEEPY:    return "SLEEPY";
    case PET_MOOD_SURPRISED: return "SURPRISE";
    case PET_MOOD_SAD:       return "SAD";
    case PET_MOOD_SICK:      return "SICK";
    case PET_MOOD_DEAD:      return "DEAD";
    default:                 return "NEUTRAL";
    }
}

static const char *stage_name(uint8_t stage)
{
    switch (stage) {
    case PET_STAGE_EGG:   return "egg";
    case PET_STAGE_CHILD: return "child";
    case PET_STAGE_TEEN:  return "teen";
    default:              return "adult";
    }
}

/* "  dies in 9h" while the death clock is running, "  DEAD" once it has, and
 * nothing at all when neither score is empty. */
static const char *death_note(char *buf, size_t n)
{
    if (pet_sim_is_dead()) {
        return "  DEAD";
    }
    const int64_t left = pet_sim_death_due_seconds();
    if (left < 0) {
        return "";
    }
    if (left >= 3600) {
        snprintf(buf, n, "  dies in %lldh", (long long)(left / 3600));
    } else {
        snprintf(buf, n, "  dies in %lldm", (long long)(left / 60));
    }
    return buf;
}

/* "batt 63% 3.84V +" — the trailing + means charging. Percent is the AXP2101's
 * own gauge, which needed a full discharge before it read anything sensible, so
 * the raw millivolts are shown beside it rather than trusted blindly. */
static const char *batt_note(char *buf, size_t n)
{
    uint8_t pct = 0xFF; uint16_t mv = 0; bool chg = false;
    if (!pet_pwr_battery(&pct, &mv, &chg)) {
        return "batt --";
    }
    if (pct > 100) {
        snprintf(buf, n, "batt ?%% %u.%02uV%s", mv / 1000, (mv % 1000) / 10, chg ? " +" : "");
    } else {
        snprintf(buf, n, "batt %u%% %u.%02uV%s", pct, mv / 1000, (mv % 1000) / 10,
                 chg ? " +" : "");
    }
    return buf;
}

static void hud_tick_cb(lv_timer_t *t)
{
    LV_UNUSED(t);
    if (s_hud == NULL) {
        return;
    }
    /* Not while asleep. This label repaints four times a second forever, which
     * on its own would keep LVGL flushing through the whole sleep and undo most
     * of what sleeping is for — the debug tool quietly cancelling the feature
     * it is there to observe. */
    if (pet_is_asleep()) {
        return;
    }
    uint8_t sat = 0, hap = 0;
    pet_sim_get(&sat, &hap);
    int32_t peak = 0, thr = 0, held = 0, target = 0;
    pet_imu_debug(&peak, NULL, &thr, &held, &target);
    int32_t feed_cd = 0, play_cd = 0;
    pet_sim_cooldowns(&feed_cd, &play_cd);

    /* The shake bar tracks the HOLD, not the instantaneous
     * magnitude — the hold is the part that is invisible without it, and a bar
     * that fills as you shake is the only way to discover the gesture. */
    const int filled = (target > 0) ? (int)((held < target ? held : target) * 10 / target) : 0;
    char bar[11];
    for (int i = 0; i < 10; i++) bar[i] = i < filled ? '=' : '.';
    bar[10] = '\0';

    char feed_s[16], play_s[16];
    if (feed_cd > 0) snprintf(feed_s, sizeof(feed_s), "%lds", (long)(feed_cd / 1000 + 1));
    else             snprintf(feed_s, sizeof(feed_s), "ready");
    if (play_cd > 0) snprintf(play_s, sizeof(play_s), "%lds", (long)(play_cd / 1000 + 1));
    else             snprintf(play_s, sizeof(play_s), "ready");

    char death_buf[32];
    char batt_buf[32];
    char buf[288];
    snprintf(buf, sizeof(buf),
             "sat %u  hap %u  %s%s%s\nshake [%s] %ld.%lds\nfeed %s   play %s\ntaps %lu  misses %u\n%s %lldh%s\n%s",
             sat, hap, mood_name(pet_sim_baseline_mood()),
             pet_sim_is_calling() ? "  CALLING" : "",
             /* Shown even though the face already says SICK: the face is what
              * the user reads, this is what says the *phone* is still
              * reporting it — which is the difference between a cure that
              * stuck and one that is about to be undone on the next poll. */
             pet_sim_is_sick() ? "  SICK" : "",
             bar, (long)(held / 1000), (long)((held % 1000) / 100),
             feed_s, play_s,
             (unsigned long)s_taps, pet_sim_care_mistakes(),
             /* Stage, age, and the death countdown — the last of which is
              * otherwise completely invisible. A twelve-hour window that
              * shortens with care mistakes cannot be judged, or even believed,
              * without being able to watch it run. */
             stage_name(pet_sim_stage()), (long long)(pet_sim_age_seconds() / 3600),
             death_note(death_buf, sizeof(death_buf)),
             batt_note(batt_buf, sizeof(batt_buf)));
    lv_label_set_text(s_hud, buf);
}

static void hud_create(lv_obj_t *parent)
{
    s_hud = lv_label_create(parent);
    lv_obj_set_style_text_color(s_hud, lv_color_hex(0x808080), 0);
    lv_obj_set_style_text_align(s_hud, LV_TEXT_ALIGN_CENTER, 0);
    lv_label_set_text(s_hud, "sat -  hap -");
    lv_obj_align(s_hud, LV_ALIGN_TOP_MID, 0, 8);
    /* Not clickable, so a tap anywhere still reaches face_tap_cb. */
    lv_obj_clear_flag(s_hud, LV_OBJ_FLAG_CLICKABLE);
    lv_timer_create(hud_tick_cb, 400, NULL);
}
#endif /* PET_DEBUG_HUD */

/* Pure-LVGL pet face — no board specifics, fully portable. */
static void pet_face_create(lv_obj_t *parent)
{
    lv_obj_set_style_bg_color(parent, PET_COLOR_PANEL, 0);
    lv_obj_set_style_bg_opa(parent, LV_OPA_COVER, 0);

    /*
     * Double-tap anywhere on the pet to feed it; see face_tap_cb.
     *
     * The handler is on the screen, and every child below is made NON-clickable
     * so taps reach it. lv_obj_create() sets LV_OBJ_FLAG_CLICKABLE by default,
     * so an eye or the mouth would otherwise swallow a tap that landed on it —
     * harmless for a single tap, but it breaks a double-tap whose two halves
     * land on different objects. The talk button is the deliberate exception: it
     * keeps its clicks.
     */
    lv_obj_add_flag(parent, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(parent, face_tap_cb, LV_EVENT_CLICKED, NULL);

    /* Two blinking, breathing eyes (direct children — known-good structure). */
    for (int i = 0; i < 2; i++) {
        int x = (i == 0) ? -s_set->eye_off_x : s_set->eye_off_x;
        lv_obj_t *eye = lv_obj_create(parent);
        lv_obj_remove_style_all(eye);
        lv_obj_remove_flag(eye, LV_OBJ_FLAG_CLICKABLE);   /* taps belong to the face */
        lv_obj_set_size(eye, EYE_W, EYE_H);   /* apply_mood resizes per eye */
        lv_obj_set_style_radius(eye, face_radius(s_set->eye_radius), 0);
        lv_obj_set_style_bg_color(eye, PET_COLOR_EYE, 0);
        lv_obj_set_style_bg_opa(eye, LV_OPA_COVER, 0);
        lv_obj_align(eye, LV_ALIGN_CENTER, x, EYE_Y);
        lv_obj_set_user_data(eye, (void *)(intptr_t)x);
        s_eyes[i] = eye;              /* moods resize these; see apply_mood */

        /* The same eye, curved. "^ ^" is a stroke, not a squashed lozenge, and
         * a filled shape cannot be one however thin it gets. */
        lv_obj_t *ea = lv_arc_create(parent);
        lv_obj_remove_style_all(ea);
        lv_obj_remove_flag(ea, LV_OBJ_FLAG_CLICKABLE);
        lv_obj_remove_flag(ea, LV_OBJ_FLAG_SCROLLABLE);
        lv_obj_set_style_arc_color(ea, PET_COLOR_EYE, LV_PART_INDICATOR);
        lv_obj_set_style_arc_opa(ea, LV_OPA_TRANSP, LV_PART_MAIN);
        lv_obj_set_style_bg_opa(ea, LV_OPA_TRANSP, LV_PART_KNOB);
        lv_obj_set_style_pad_all(ea, 0, LV_PART_KNOB);
        lv_obj_add_flag(ea, LV_OBJ_FLAG_HIDDEN);
        add_breathe(ea);
        s_eye_arcs[i] = ea;

        /* And the same eye as a SPIRAL — an lv_line, because LVGL has no
         * spiral and the shape arrives as a table of points from the same
         * generator the arc angles do. Third form, same trade as the other
         * two: it exists from boot and spends nearly all its life hidden,
         * because allocating a widget during a mood change is what this board
         * has already frozen over once. */
        lv_obj_t *so = lv_line_create(parent);
        lv_obj_remove_style_all(so);
        lv_obj_remove_flag(so, LV_OBJ_FLAG_CLICKABLE);
        lv_obj_set_style_line_color(so, PET_COLOR_EYE, 0);
        lv_obj_set_style_line_opa(so, LV_OPA_COVER, 0);
        lv_obj_set_style_line_rounded(so, true, 0);
        lv_obj_add_flag(so, LV_OBJ_FLAG_HIDDEN);
        add_breathe(so);
        s_eye_spirals[i] = so;

        add_blink(eye);
        add_breathe(eye);
    }

    /* Smile (breathes with the eyes) */
    lv_obj_t *mouth = lv_obj_create(parent);
    lv_obj_remove_style_all(mouth);
    lv_obj_remove_flag(mouth, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_size(mouth, s_set->face[PET_FACE_NEUTRAL].mouth_w,
                    s_set->face[PET_FACE_NEUTRAL].mouth_thick);
    lv_obj_set_style_radius(mouth, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_color(mouth, PET_COLOR_MOUTH, 0);
    lv_obj_set_style_bg_opa(mouth, LV_OPA_COVER, 0);
    lv_obj_align(mouth, LV_ALIGN_CENTER, 0, s_set->mouth_off_y);
    add_breathe(mouth);
    s_mouth = mouth;

    /*
     * THE CURVED MOUTH, a second object that exists so a smile can be a smile.
     *
     * A bar and an arc are different LVGL widgets, and swapping an object's
     * type at runtime means deleting and recreating it inside apply_mood() —
     * on a device where a failed allocation is how the screen froze once. Both
     * exist from boot instead and one of them is always hidden. Two objects is
     * the cheaper trade: the measured cost is ~50 ns per rotated pixel, and an
     * idle hidden object costs nothing per frame.
     *
     * lv_arc is a WIDGET, so it arrives with a knob and a background arc it
     * would happily draw over the pet's face. Both are removed here, the same
     * way the eyes have their style stripped.
     */
    lv_obj_t *arc = lv_arc_create(parent);
    lv_obj_remove_style_all(arc);
    lv_obj_remove_flag(arc, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_remove_flag(arc, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_style_arc_color(arc, PET_COLOR_MOUTH, LV_PART_INDICATOR);
    lv_obj_set_style_arc_opa(arc, LV_OPA_TRANSP, LV_PART_MAIN);   /* no track */
    lv_obj_set_style_bg_opa(arc, LV_OPA_TRANSP, LV_PART_KNOB);    /* no knob  */
    lv_obj_set_style_pad_all(arc, 0, LV_PART_KNOB);
    lv_obj_add_flag(arc, LV_OBJ_FLAG_HIDDEN);
    add_breathe(arc);   /* the same rise and fall as the rest of the face */
    s_mouth_arc = arc;

    /*
     * Link dot, top-left. Small and out of the way: it is a status, and the
     * pet is a character rather than a dashboard.
     *
     * Explicitly NOT clickable, like every other child of the face. lv_obj_create
     * sets LV_OBJ_FLAG_CLICKABLE by default, and a child that swallows a tap is
     * how a double-tap whose two halves land on different objects silently stops
     * feeding the pet — already paid for once, in phase 1.
     */
    lv_obj_t *link = lv_obj_create(parent);
    lv_obj_remove_style_all(link);
    lv_obj_remove_flag(link, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_size(link, 12, 12);
    lv_obj_set_style_radius(link, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_color(link, PET_COLOR_LINK_DOWN, 0);
    lv_obj_set_style_bg_opa(link, LV_OPA_COVER, 0);
    lv_obj_align(link, LV_ALIGN_TOP_LEFT, 16, 16);
    s_link_dot = link;

    /* Text line — updated by the phone over BLE. Wraps long messages. */
    lv_obj_t *label = lv_label_create(parent);
    lv_obj_remove_flag(label, LV_OBJ_FLAG_CLICKABLE);
    lv_label_set_long_mode(label, LV_LABEL_LONG_WRAP);
    /* PET_SCREEN_W, not BSP_LCD_H_RES: the screen is rotated, and the panel's
     * own width would waste 80px of the line the pet speaks on. */
    lv_obj_set_width(label, PET_SCREEN_W - 40);
    lv_obj_set_style_text_align(label, LV_TEXT_ALIGN_CENTER, 0);
    lv_label_set_text(label, "hi, i'm your pet");
    lv_obj_set_style_text_color(label, PET_COLOR_TEXT, 0);
    lv_obj_align(label, LV_ALIGN_BOTTOM_MID, 0, -24);
    s_text_label = label;

    /*
     * Talk button.
     *
     * The pet's whole point is that you talk to *it*, but until now every
     * conversation had to be started from the phone — which meant unlocking it
     * and opening an app to speak to the thing sitting in front of you. This is
     * the pet becoming the interface.
     *
     * Top-right: the face owns the middle of the 448x368 screen and the reply text
     * runs full-width along the bottom, so that corner is the only place a
     * control does not sit on top of something.
     */
    lv_obj_t *btn = lv_obj_create(parent);
    lv_obj_remove_style_all(btn);
    lv_obj_set_size(btn, MIC_BTN_SIZE, MIC_BTN_SIZE);
    lv_obj_set_style_radius(btn, LV_RADIUS_CIRCLE, 0);
    /*
     * A CONTROL, NOT AN EXPRESSION.
     *
     * This was a hardcoded 0x14202A with a full-strength 3px ring in the face's
     * own colour. Both were chosen against a black panel and never revisited,
     * and on a pale set the result was a dark disc with a hard dark ring — a
     * hole punched in the pet's face, pulling the eye away from the thing the
     * screen is actually for.
     *
     * The fill is the set's own `control` colour now, a small step from the
     * panel rather than a contrast to it, and the generator refuses a set whose
     * button exceeds 4.5:1 against its panel. The ring is thinner and dimmed:
     * it still says "this is pressable" without competing with the face.
     */
    lv_obj_set_style_bg_color(btn, PET_COLOR_CONTROL, 0);
    lv_obj_set_style_bg_opa(btn, LV_OPA_COVER, 0);
    lv_obj_set_style_border_color(btn, PET_COLOR_EYE, 0);
    lv_obj_set_style_border_opa(btn, LV_OPA_40, 0);
    lv_obj_set_style_border_width(btn, 2, 0);
    lv_obj_align(btn, LV_ALIGN_TOP_RIGHT, -18, 18);
    /* The drawn circle is deliberately smaller than the tap target: a finger on
     * a screen this size is far wider than the affordance it is aiming at. */
    lv_obj_set_ext_click_area(btn, MIC_BTN_TOUCH_PAD);
    lv_obj_add_flag(btn, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(btn, mic_btn_cb, LV_EVENT_CLICKED, NULL);
    s_mic_btn = btn;


    /* A filled dot inside, so state reads at a glance from across a desk. */
    lv_obj_t *dot = lv_obj_create(btn);
    lv_obj_remove_style_all(dot);
    lv_obj_set_size(dot, MIC_DOT_SIZE, MIC_DOT_SIZE);
    lv_obj_set_style_radius(dot, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_color(dot, PET_COLOR_EYE, 0);
    /* Dimmed like the ring. At full strength on a pale set this is a hard dark
     * disc inside a near-white button — the loudest thing on the screen, and
     * the pet is what the screen is for. */
    lv_obj_set_style_bg_opa(dot, PET_CONTROL_IDLE_OPA, 0);
    lv_obj_center(dot);
    s_mic_dot = dot;

    /*
     * The two marks the pet teaches with. Created once and hidden; apply_teach
     * animates them.
     *
     * NON-CLICKABLE, like every other child of the face, and here it matters
     * more than anywhere else: these sit on the pet during the exact moments a
     * user is being invited to tap it, so a mark that swallowed touches would
     * teach the double tap and then eat it. That failure has already been paid
     * for once in phase 1.
     */
    for (int i = 0; i < 2; i++) {
        lv_obj_t *t = lv_obj_create(parent);
        lv_obj_remove_style_all(t);
        lv_obj_remove_flag(t, LV_OBJ_FLAG_CLICKABLE);
        lv_obj_add_flag(t, LV_OBJ_FLAG_HIDDEN);
        lv_obj_set_size(t, TEACH_RIPPLE_MIN, TEACH_RIPPLE_MIN);
        lv_obj_set_style_radius(t, LV_RADIUS_CIRCLE, 0);
        lv_obj_set_style_bg_color(t, PET_COLOR_EYE, 0);
        lv_obj_set_style_bg_opa(t, LV_OPA_70, 0);
        lv_obj_align(t, LV_ALIGN_CENTER, 0, TEACH_Y);
        s_teach[i] = t;
    }

    /* Heartbeat: proves whether the LVGL task is still running its timers,
     * so a wedge can be located in time against what audio was doing. */
    lv_timer_create(lvgl_heartbeat_cb, 500, NULL);

    /* Created paused; apply_mood resumes it whenever a non-neutral expression
     * arrives. Kept alive for the process lifetime rather than made one-shot,
     * because LVGL frees a timer once its repeat count runs out. */
    s_mood_timer = lv_timer_create(mood_timeout_cb, MOOD_TIMEOUT_MS, NULL);
    s_chrome_timer = lv_timer_create(chrome_timeout_cb, CHROME_TIMEOUT_MS, NULL);
    if (s_mood_timer != NULL) {
        lv_timer_pause(s_mood_timer);
    }
}

/* --- API used by the BLE layer (pet.h) -------------------------------------
 *
 * pet_set_text()/pet_set_mood() are called from the NimBLE host task. They must
 * NOT block there: taking the LVGL lock on the BLE host task stalls the whole
 * BLE stack whenever LVGL happens to be mid-render, which shows up as random
 * disconnects. So they only enqueue; s_ui_task applies the change under the
 * lock on its own thread.
 */

typedef enum { PET_UI_TEXT, PET_UI_MOOD, PET_UI_LISTENING, PET_UI_SLEEP,
               PET_UI_LINK, PET_UI_TEACH, PET_UI_CHROME } pet_ui_kind_t;

typedef struct {
    pet_ui_kind_t kind;
    char          text[PET_TEXT_MAX];
    uint8_t       mood;
    bool          listening;
    bool          asleep;
    bool          linked;
    uint8_t       teach;
} pet_ui_msg_t;

static QueueHandle_t s_ui_q;

/* --- idle sleep -----------------------------------------------------------
 *
 * A pet on a desk is looked at for a few minutes an hour and left alone for the
 * rest, and for all of that time this firmware was running three infinite
 * animations and repainting the panel continuously. Sleeping is mostly about
 * stopping that; the dimming is the smaller half.
 *
 * WHAT THIS DELIBERATELY DOES NOT DO: turn the panel off. The AMOLED latches a
 * state that only removing power clears (a known open bug), and a pet whose
 * screen never comes back is a worse outcome than a flat battery. Brightness is
 * a backlight-level command on a path that is exercised every boot already, so
 * it cannot reach that bug.
 *
 * The pet also stays VISIBLE. It is asleep, which is a thing a pet does — not
 * switched off, which is a thing an appliance does.
 */
#define SLEEP_AFTER_MS      (5 * 60 * 1000)
#define SLEEP_BRIGHTNESS    10
#define AWAKE_BRIGHTNESS    80

static volatile int64_t s_last_activity_us;
static volatile bool    s_asleep;

bool pet_is_asleep(void) { return s_asleep; }

/* Applied on the UI task, like every other visual change. */
static void apply_sleep(bool asleep)
{
    if (asleep == s_asleep) {
        return;
    }
    s_asleep = asleep;

    /*
     * Brightness first on the way down and last on the way up, so the user
     * never sees the face change at full brightness before it dims, or a dim
     * frame of the woken face.
     */
    /*
     * Under the LVGL lock: brightness is a command to the SH8601 over the same
     * QSPI bus LVGL flushes pixels through, and issuing one mid-flush is a bus
     * conflict with a panel this project already has one unexplained lock-up
     * on. The lock is recursive, so apply_mood taking it again below is fine.
     */
    if (asleep) {
        lvgl_port_lock(0);
        pet_display_brightness(SLEEP_BRIGHTNESS);
        lvgl_port_unlock();
    }

    /* Re-applying the mood is what starts or stops the animations: the sleeping
     * face is drawn with eyes closed and no blink or breathe, exactly as the
     * dead face is, and eyes_apply_shape() reads pet_is_asleep() for that. */
    apply_mood(pet_sim_baseline_mood());

    if (!asleep) {
        lvgl_port_lock(0);
        pet_display_brightness(AWAKE_BRIGHTNESS);
        lvgl_port_unlock();
    }
    ESP_LOGI(TAG, "%s", asleep ? "asleep - dimmed, animations stopped" : "awake");
    /* The phone is told on every condition change anyway, and dozing is NOT
     * one — it is a five-minute idle timeout, not a fact about the pet's life.
     * Quiet hours are reported instead, and the simulation notifies those. */
}

/* Both callers hold the LVGL lock already, or are the timer itself. */
static void chrome_set(bool shown)
{
    s_chrome_shown = shown;
    lv_obj_t *const chrome[] = { s_mic_btn, s_text_label };
    for (size_t i = 0; i < sizeof(chrome) / sizeof(chrome[0]); i++) {
        if (chrome[i] == NULL) {
            continue;
        }
        if (shown) {
            lv_obj_remove_flag(chrome[i], LV_OBJ_FLAG_HIDDEN);
        } else {
            lv_obj_add_flag(chrome[i], LV_OBJ_FLAG_HIDDEN);
        }
    }
}

/* Runs on the UI task, which is where every lv_* call in this file belongs. */
static void apply_chrome_wake(void)
{
    lvgl_port_lock(0);
    if (!s_chrome_shown) {
        chrome_set(true);
    }
    if (s_chrome_timer != NULL) {
        lv_timer_reset(s_chrome_timer);
        lv_timer_resume(s_chrome_timer);
    }
    lvgl_port_unlock();
}

static void chrome_timeout_cb(lv_timer_t *t)
{
    LV_UNUSED(t);
    /*
     * NEVER WHILE LISTENING. The filled ring is the only sign the pet is
     * recording, and hiding it mid-utterance would leave someone talking to a
     * device with no indication it can hear them.
     */
    if (pet_mic_is_listening()) {
        lv_timer_reset(s_chrome_timer);
        return;
    }
    chrome_set(false);
    lv_timer_pause(s_chrome_timer);
}

/*
 * Bring the chrome back and restart its clock.
 *
 * Safe to call from any task: it only enqueues, like every other UI entry
 * point here, because the callers include the NimBLE host task.
 */
void pet_chrome_wake(void)
{
    if (!s_ui_q) {
        return;
    }
    pet_ui_msg_t m = { .kind = PET_UI_CHROME };
    xQueueSend(s_ui_q, &m, 0);
}

void pet_activity(void)
{
    s_last_activity_us = esp_timer_get_time();

    if (!s_asleep || !s_ui_q) {
        return;   /* the common case: awake already, nothing to do */
    }
    pet_ui_msg_t m = { .kind = PET_UI_SLEEP, .asleep = false };
    xQueueSend(s_ui_q, &m, 0);
}

/* Called from the status task, which cannot wedge — see s_lvgl_beats. */
/*
 * WHAT SHOULD HAPPEN TO THE SCREEN — the whole decision, in one table.
 *
 * THIS EXISTS BECAUSE THE CHAIN OF EARLY RETURNS IT REPLACES PRODUCED THREE
 * BUGS IN THIRTY LINES, all of the same shape and all found the hard way:
 *
 *   2026-08-04  a counted call still read as "calling", so a hungry pet never
 *               slept again until it was fed
 *   2026-08-05  `if (s_asleep) return;` at the top meant a sleeping pet could
 *               never wake to call — it beeped at a dark screen for 31 minutes
 *   (and the original) the pet not sleeping at all while a score sat at 0
 *
 * Every one was an early return that silently skipped a case, under a comment
 * asserting the case was handled. A reader checking the comment against the code
 * had to simulate the whole chain to notice; nothing failed, nothing logged, and
 * the wire reported the intended state throughout.
 *
 * So the logic is now a PURE FUNCTION OVER THE FULL STATE SPACE, with no early
 * returns and no side effects, and every combination written out:
 *
 *   | asleep | wants attention | idle >= timeout | ->                 |
 *   |--------|-----------------|-----------------|--------------------|
 *   | yes    | yes             | (irrelevant)    | WAKE               |
 *   | yes    | no              | (irrelevant)    | STAY  (asleep)     |
 *   | no     | yes             | (irrelevant)    | HOLD_AWAKE         |
 *   | no     | no              | yes             | GO_TO_SLEEP        |
 *   | no     | no              | no              | STAY  (awake)      |
 *
 * The caller's `switch` has no `default`, so -Wswitch makes the compiler
 * enforce that every action is handled — which is the guarantee the early-return
 * chain could never offer. Adding a state now breaks the build instead of
 * quietly doing nothing.
 *
 * "Wants attention" is calling ALOUD or sick. is_calling_aloud rather than
 * is_calling because the plain predicate stays true for as long as a score is 0,
 * which is the first bug above. A pet asking for help from a dark screen is
 * asking nobody; one that has stopped asking should be allowed to sleep.
 */
typedef enum {
    SLEEP_STAY = 0,      /* leave it exactly as it is */
    SLEEP_WAKE,          /* asleep, and something needs to be seen */
    SLEEP_GO_TO_SLEEP,   /* awake, idle long enough, nothing to say */
    SLEEP_HOLD_AWAKE,    /* awake and still wanted; push the idle timer */
} sleep_action_t;

static sleep_action_t sleep_decide(bool asleep, bool wants_attention, int64_t idle_ms)
{
    sleep_action_t action;

    if (asleep) {
        action = wants_attention ? SLEEP_WAKE : SLEEP_STAY;
    } else if (wants_attention) {
        action = SLEEP_HOLD_AWAKE;
    } else if (idle_ms >= SLEEP_AFTER_MS) {
        action = SLEEP_GO_TO_SLEEP;
    } else {
        action = SLEEP_STAY;
    }
    return action;
}

static void sleep_tick(void)
{
    if (s_ui_q == NULL) {
        return;   /* nothing to enqueue onto; not a decision about sleep */
    }

    const bool    wants_attention = pet_sim_is_calling_aloud() || pet_sim_is_sick();
    const int64_t idle_ms = (esp_timer_get_time() - s_last_activity_us) / 1000;

    switch (sleep_decide(s_asleep, wants_attention, idle_ms)) {
    case SLEEP_WAKE:
        pet_activity();          /* stamps the timer and queues the wake */
        break;
    case SLEEP_GO_TO_SLEEP: {
        pet_ui_msg_t m = { .kind = PET_UI_SLEEP, .asleep = true };
        xQueueSend(s_ui_q, &m, 0);
        break;
    }
    case SLEEP_HOLD_AWAKE:
        /* Push the idle timer forward, or a pet would fall asleep mid-call. */
        s_last_activity_us = esp_timer_get_time();
        break;
    case SLEEP_STAY:
        break;
    }
}

/*
 * Does the pet need to be teaching a gesture right now? DESIGN.md §5.5.
 *
 * Polled rather than pushed. The hint is a function of how long a need has
 * STOOD, so there is no event to hang it on — nothing happens at the moment it
 * becomes true, which is precisely what makes it the kind of state that
 * otherwise never gets rendered. Once a second is far finer than a hint that
 * turns on after a quarter of an hour needs, and the work is one RTC read.
 *
 * A SLEEPING PET TEACHES NOTHING, and that is not just tidiness: the whole
 * reason sleeping exists is to stop this firmware repainting at a dark screen,
 * and a demonstration animating behind a dimmed panel would hand back the
 * battery the sleep change just bought. It resumes on waking, because this poll
 * keeps running and the need is still there.
 */
static void teach_tick(void)
{
    if (s_ui_q == NULL) {
        return;
    }
    const uint8_t want = pet_is_asleep() ? (uint8_t)PET_TEACH_NONE
                                         : pet_sim_teach_hint();

    static uint8_t last = PET_TEACH_NONE;
    if (want == last) {
        return;
    }
    last = want;

    pet_ui_msg_t m = { .kind = PET_UI_TEACH, .teach = want };
    xQueueSend(s_ui_q, &m, 0);
}

static void apply_text(const char *s)
{
    if (!s_text_label) {
        return;
    }
    lvgl_port_lock(0);
    /* Something to read is the whole reason the line exists, so it comes back
     * whether or not anybody touched the screen. */
    if (!s_chrome_shown) {
        chrome_set(true);
    }
    if (s_chrome_timer != NULL) {
        lv_timer_reset(s_chrome_timer);
        lv_timer_resume(s_chrome_timer);
    }
    lv_label_set_text(s_text_label, s);
    lvgl_port_unlock();
    ESP_LOGI(TAG, "text <- \"%s\"", s);
}

/*
 * Start, change or stop the demonstration. Runs on the UI task under the LVGL
 * lock, like every other visual change.
 *
 * Animations are DELETED rather than paused when the hint goes away, for the
 * reason eyes_apply_shape gives: a paused animation still holds its object at
 * whatever value it stopped on, so a paused ripple is a mysterious circle
 * sitting on the pet's chin. The objects are hidden as well as stopped, because
 * the last frame of a fading ripple is not reliably invisible.
 */
static void apply_teach(uint8_t hint)
{
    if (hint == s_teach_shown || !s_teach[0] || !s_teach[1]) {
        return;
    }
    s_teach_shown = hint;

    lvgl_port_lock(0);

    for (int i = 0; i < 2; i++) {
        lv_anim_delete(s_teach[i], anim_teach_ripple_cb);
        lv_anim_delete(s_teach[i], anim_teach_slide_cb);
        lv_obj_set_style_translate_x(s_teach[i], 0, 0);
        lv_obj_add_flag(s_teach[i], LV_OBJ_FLAG_HIDDEN);
    }

    if (hint == PET_TEACH_FEED) {
        /*
         * Two ripples on one clock. The second is delayed by the gap that makes
         * this a double tap rather than a pulse, and both repeat on the same
         * period so the pair stays a pair — driving them from separate timers
         * would let them drift until the demonstration taught the wrong gesture.
         */
        for (int i = 0; i < 2; i++) {
            lv_obj_remove_flag(s_teach[i], LV_OBJ_FLAG_HIDDEN);
            lv_anim_t a;
            lv_anim_init(&a);
            lv_anim_set_var(&a, s_teach[i]);
            lv_anim_set_exec_cb(&a, anim_teach_ripple_cb);
            lv_anim_set_values(&a, TEACH_RIPPLE_MIN, TEACH_RIPPLE_MAX);
            lv_anim_set_duration(&a, TEACH_RIPPLE_MS);
            lv_anim_set_delay(&a, i * TEACH_TAP_GAP_MS);
            lv_anim_set_repeat_count(&a, LV_ANIM_REPEAT_INFINITE);
            lv_anim_set_repeat_delay(&a, TEACH_REST_MS + TEACH_TAP_GAP_MS
                                         - (i * TEACH_TAP_GAP_MS));
            lv_anim_start(&a);
        }
    } else if (hint == PET_TEACH_PLAY) {
        /* One mark, moving and not stopping — the shake is a HELD gesture, and
         * a demonstration that paused would be teaching a wiggle. */
        lv_obj_remove_flag(s_teach[0], LV_OBJ_FLAG_HIDDEN);
        lv_obj_set_size(s_teach[0], TEACH_DOT_SIZE, TEACH_DOT_SIZE);
        lv_obj_align(s_teach[0], LV_ALIGN_CENTER, 0, TEACH_Y);
        lv_obj_set_style_bg_opa(s_teach[0], LV_OPA_70, 0);

        lv_anim_t a;
        lv_anim_init(&a);
        lv_anim_set_var(&a, s_teach[0]);
        lv_anim_set_exec_cb(&a, anim_teach_slide_cb);
        lv_anim_set_values(&a, -TEACH_SHAKE_X, TEACH_SHAKE_X);
        lv_anim_set_duration(&a, TEACH_SHAKE_MS);
        lv_anim_set_playback_duration(&a, TEACH_SHAKE_MS);
        lv_anim_set_repeat_count(&a, LV_ANIM_REPEAT_INFINITE);
        lv_anim_set_path_cb(&a, lv_anim_path_ease_in_out);
        lv_anim_start(&a);
    }

    lvgl_port_unlock();

    ESP_LOGI(TAG, "teaching: %s",
             hint == PET_TEACH_FEED ? "double tap to feed"
             : hint == PET_TEACH_PLAY ? "shake to play"
             : "nothing");
}

static void apply_mood(uint8_t mood)
{
    if (!s_mouth) {
        return;
    }

    /*
     * Sickness outranks whatever expression was asked for. DESIGN.md §1 ranks
     * dead > sick > a transient emoji mood > the baseline, and this is where
     * that ranking is enforced: without it the pet grins through an illness
     * every time a reply happens to contain a smiley, and the one face the user
     * is supposed to act on is the one most easily painted over.
     *
     * Done here rather than at the call sites so every path inherits it — the
     * phone's Mood writes, the expression decay, and the simulation's own
     * updates all arrive through this function.
     */
    if (pet_sim_is_sick()) {
        mood = PET_MOOD_SICK;
    }

    /* And death outranks sickness, for the same reason one level up: DESIGN.md
     * §1 ranks dead above everything, and a corpse pulling a queasy face is
     * still a corpse pretending to have opinions. */
    if (pet_sim_is_dead()) {
        mood = PET_MOOD_DEAD;
    }

    /*
     * THE FACE COMES FROM THE ACTIVE SET, not from a switch in this file.
     *
     * This was seven `case` blocks of hand-written numbers, mirrored by hand in
     * Kotlin and again in the notification's XML, with a unit test whose whole
     * job was to fail when the three drifted. All three are generated from
     * `design-system/faces/` now, so they cannot drift — see that
     * directory's README.
     *
     * The first seven slots ARE the PET_MOOD_* values, so the mood indexes the
     * table directly. Anything out of range falls back to the resting face
     * rather than reading past the end: a bad mood byte from a future phone
     * must not be able to walk off this array.
     */
    pet_face_slot_t slot = (mood < PET_FACE_ASLEEP) ? (pet_face_slot_t)mood
                                                    : PET_FACE_NEUTRAL;

    /*
     * Asleep: eyes closed, mouth small. Applied over whatever expression was
     * chosen rather than as another mood value, because sleep is presentation
     * and not a condition — the pet's satiety, sickness and death are all still
     * true underneath, and are what it wakes up showing.
     *
     * Dead outranks it. A dead pet does not sleep.
     */
    if (pet_is_asleep() && !pet_sim_is_dead()) {
        slot = PET_FACE_ASLEEP;
    }

    s_face_slot = slot;
    const pet_face_geom_t *g = &s_set->face[slot];
    int32_t mouth_w = g->mouth_w, mouth_h = g->mouth_thick;

    lvgl_port_lock(0);
    s_eye_open_w[0] = g->eye_w;
    s_eye_open_h[0] = g->eye_h;
    s_eye_open_w[1] = g->eye_rw;
    s_eye_open_h[1] = g->eye_rh;
    s_eye_y = s_set->eye_off_y + g->gaze_y;

    const int pct = s_set->stage_pct[pet_sim_stage()];
    for (int i = 0; i < 2; i++) {
        s_eye_open_w[i] = s_eye_open_w[i] * pct / 100;
        s_eye_open_h[i] = s_eye_open_h[i] * pct / 100;
    }

    /*
     * A BAR OR AN ARC. arc_r of 0 means the set asked for a straight mouth,
     * which is what every face was before curves existed — so `classic` still
     * takes the same path it always did.
     *
     * The radius, the two angles and the circle's centre are all generated;
     * nothing here does trigonometry. Three renderers each deriving the same
     * curve would be three chances to disagree, and the disagreement would be a
     * pet that smiles slightly differently in your hand than on the shelf.
     */
    const bool curved = (g->arc_r > 0) && (mouth_w > 0);
    if (curved) {
        const int32_t box = 2 * g->arc_r * pct / 100;
        lv_obj_add_flag(s_mouth, LV_OBJ_FLAG_HIDDEN);
        lv_obj_remove_flag(s_mouth_arc, LV_OBJ_FLAG_HIDDEN);
        lv_obj_set_size(s_mouth_arc, box, box);
        lv_obj_set_style_arc_width(s_mouth_arc, mouth_h * pct / 100,
                                   LV_PART_INDICATOR);
        lv_obj_set_style_arc_rounded(s_mouth_arc, g->mouth_round ? true : false,
                                     LV_PART_INDICATOR);
        lv_arc_set_bg_angles(s_mouth_arc, g->arc_start, g->arc_end);
        lv_arc_set_angles(s_mouth_arc, g->arc_start, g->arc_end);
        lv_obj_align(s_mouth_arc, LV_ALIGN_CENTER, 0,
                     s_set->mouth_off_y + g->arc_dy * pct / 100);
    } else {
        lv_obj_add_flag(s_mouth_arc, LV_OBJ_FLAG_HIDDEN);
        lv_obj_remove_flag(s_mouth, LV_OBJ_FLAG_HIDDEN);
        lv_obj_set_size(s_mouth, mouth_w * pct / 100, mouth_h * pct / 100);
        lv_obj_set_style_radius(s_mouth, g->mouth_round ? LV_RADIUS_CIRCLE : 0, 0);
        lv_obj_align(s_mouth, LV_ALIGN_CENTER, 0, s_set->mouth_off_y);
    }
    eyes_apply_shape();

    /* Arm (or re-arm) the decay back to the resting face. Each new expression
     * restarts the clock; arriving AT the resting face just stops it.
     *
     * The resting face is the simulation's baseline, not NEUTRAL. Decaying to
     * NEUTRAL would put a contented face on a starving pet ten seconds after
     * every reply — see DESIGN.md §1, "Mood is derived, not stored". */
    if (s_mood_timer != NULL) {
        if (mood == pet_sim_baseline_mood()) {
            lv_timer_pause(s_mood_timer);
        } else {
            lv_timer_reset(s_mood_timer);
            lv_timer_resume(s_mood_timer);
        }
    }
    lvgl_port_unlock();
    ESP_LOGI(TAG, "mood <- %u", mood);
}

/*
 * Push the active set onto everything already drawn.
 *
 * A set change is not just a face: it repaints the panel, the spoken line and
 * the talk button too, because a theme that recoloured the eyes and left a gold
 * button on a pink pet would look like a bug rather than a style. The eyes' x
 * offset lives in each eye's user_data (the blink callback reads it from
 * there), so it has to be rewritten here as well as at creation.
 *
 * The link dot deliberately does NOT follow the set. Green and red mean
 * connected and not; they are the same two colours on the phone, and a theme
 * that could recolour them could make "no phone" unreadable.
 */
static void pet_face_set_apply(void)
{
    lvgl_port_lock(0);

    lv_obj_t *scr = lv_screen_active();
    if (scr != NULL) {
        lv_obj_set_style_bg_color(scr, PET_COLOR_PANEL, 0);
    }
    for (int i = 0; i < 2; i++) {
        if (s_eyes[i] == NULL) {
            continue;
        }
        const int x = (i == 0) ? -s_set->eye_off_x : s_set->eye_off_x;
        lv_obj_set_user_data(s_eyes[i], (void *)(intptr_t)x);
        lv_obj_set_style_bg_color(s_eyes[i], PET_COLOR_EYE, 0);
        lv_obj_set_style_radius(s_eyes[i], face_radius(s_set->eye_radius), 0);
    }
    if (s_mouth != NULL) {
        lv_obj_set_style_bg_color(s_mouth, PET_COLOR_MOUTH, 0);
    }
    for (int i = 0; i < 2; i++) {
        if (s_eye_arcs[i] != NULL) {
            lv_obj_set_style_arc_color(s_eye_arcs[i], PET_COLOR_EYE,
                                       LV_PART_INDICATOR);
        }
        if (s_eye_spirals[i] != NULL) {
            lv_obj_set_style_line_color(s_eye_spirals[i], PET_COLOR_EYE, 0);
        }
    }
    if (s_mouth_arc != NULL) {
        lv_obj_set_style_arc_color(s_mouth_arc, PET_COLOR_MOUTH, LV_PART_INDICATOR);
    }
    if (s_text_label != NULL) {
        lv_obj_set_style_text_color(s_text_label, PET_COLOR_TEXT, 0);
    }
    if (s_mic_btn != NULL) {
        lv_obj_set_style_bg_color(s_mic_btn, PET_COLOR_CONTROL, 0);
        lv_obj_set_style_border_color(s_mic_btn, PET_COLOR_EYE, 0);
        lv_obj_set_style_border_opa(s_mic_btn, PET_CONTROL_IDLE_OPA, 0);
    }
    if (s_mic_dot != NULL) {
        lv_obj_set_style_bg_color(s_mic_dot, PET_COLOR_EYE, 0);
        lv_obj_set_style_bg_opa(s_mic_dot, PET_CONTROL_IDLE_OPA, 0);
    }

    lvgl_port_unlock();

    /* Geometry, the blink and the breathe all come from the set too, and
     * apply_mood() is what applies them. The baseline rather than a remembered
     * mood: a set change is not an expression, and re-asserting a stale
     * transient one here would make switching themes wink at you. */
    apply_mood(pet_sim_baseline_mood());
}

bool pet_face_set_select(const char *id)
{
    const pet_face_set_t *found = face_set_find(id);
    if (found == NULL) {
        ESP_LOGW(TAG, "face set '%s' unknown - staying on '%s'",
                 id ? id : "(null)", s_set->id);
        return false;
    }
    if (found == s_set) {
        return true;
    }
    s_set = found;
    face_set_store(s_set->id);
    pet_face_set_apply();
    ESP_LOGI(TAG, "face set <- %s (%s)", s_set->id, s_set->name);
    return true;
}

const char *pet_face_set_id(void)      { return s_set->id; }
uint8_t     pet_face_set_count(void)   { return PET_FACE_SET_COUNT; }

/*
 * Restore the set chosen last time, before anything is drawn.
 *
 * An id NVS remembers but this firmware no longer has is not an error worth
 * failing on — a set can be removed from the design system between builds. It
 * falls back to index 0 and says so, which is also what the phone assumes when
 * it has not been told yet.
 */
static void pet_face_set_restore(void)
{
    nvs_handle_t h;
    if (nvs_open(FACE_NVS_NAMESPACE, NVS_READONLY, &h) != ESP_OK) {
        return;                      /* never written yet - index 0 stands */
    }
    char id[24] = {0};
    size_t len = sizeof(id);
    const esp_err_t err = nvs_get_str(h, FACE_NVS_KEY, id, &len);
    nvs_close(h);
    if (err != ESP_OK) {
        return;
    }
    const pet_face_set_t *found = face_set_find(id);
    if (found == NULL) {
        ESP_LOGW(TAG, "stored face set '%s' is gone - falling back to '%s'",
                 id, pet_face_sets[0].id);
        return;
    }
    s_set = found;
    ESP_LOGI(TAG, "face set restored: %s (%s)", s_set->id, s_set->name);
}

/* Fill the dot while recording so it is obvious the pet is listening, and give
 * it the same pink as the speech text — the colour already means "the pet is
 * doing something with words". */
static void apply_listening(bool on)
{
    if (!s_mic_btn || !s_mic_dot) {
        return;
    }
    lvgl_port_lock(0);
    /* Listening is the one time this SHOULD be loud: the ring goes to full
     * strength so it is obvious the pet is recording. Idle, it stays dimmed. */
    lv_obj_set_style_bg_color(s_mic_dot, on ? PET_COLOR_TEXT : PET_COLOR_EYE, 0);
    lv_obj_set_style_bg_opa(s_mic_dot, on ? LV_OPA_COVER : PET_CONTROL_IDLE_OPA, 0);
    lv_obj_set_style_border_color(s_mic_btn, on ? PET_COLOR_TEXT : PET_COLOR_EYE, 0);
    lv_obj_set_style_border_opa(s_mic_btn,
                                on ? LV_OPA_COVER : PET_CONTROL_IDLE_OPA, 0);
    lv_obj_set_size(s_mic_dot, on ? MIC_DOT_SIZE + 8 : MIC_DOT_SIZE,
                    on ? MIC_DOT_SIZE + 8 : MIC_DOT_SIZE);
    lv_obj_center(s_mic_dot);
    lvgl_port_unlock();
    ESP_LOGI(TAG, "listening <- %d", on);
}

/* Drains the queue and applies updates. Blocking on the LVGL lock is fine here
 * — this task exists to wait. */
/* --- the link to the phone ------------------------------------------------
 *
 * WHY THE PET DERIVES THIS RATHER THAN BEING TOLD: when the link is down there
 * is nothing to tell it with. Every other thing the phone communicates arrives
 * as a write; "I am gone" is the one message that can never be sent, so it has
 * to be noticed. NimBLE's connect and disconnect events are the only honest
 * source, and pet_ble.c calls this from both.
 *
 * A DOT, NOT A FACE. The pet's expression belongs to the simulation — DESIGN.md
 * §1 decision 1 — and the phone being in another room is not something the pet
 * should feel. An earlier draft of §2.4 had a disconnected pet render "asleep,
 * dimmed", which was written before the pet owned its own life; a pet that
 * played dead because you left your phone upstairs would now be wrong, and
 * indistinguishable from the two states that genuinely mean something.
 *
 * So it is small, in a corner, and always present in both states. Present in
 * both because an indicator that only appears when something is wrong cannot be
 * told apart from one that is broken.
 */
static void apply_link(bool linked)
{
    if (!s_link_dot) {
        return;
    }
    lvgl_port_lock(0);
    /* Connected is deliberately the quieter of the two: it is the normal state
     * and should not draw the eye, while "no phone" is the one worth noticing. */
    lv_obj_set_style_bg_color(s_link_dot,
                              linked ? PET_COLOR_LINK_UP : PET_COLOR_LINK_DOWN, 0);
    lv_obj_set_style_bg_opa(s_link_dot, linked ? LV_OPA_40 : LV_OPA_COVER, 0);
    lvgl_port_unlock();
    ESP_LOGI(TAG, "link <- %s", linked ? "phone connected" : "no phone");
}

static void pet_ui_task(void *arg)
{
    pet_ui_msg_t m;

    for (;;) {
        if (xQueueReceive(s_ui_q, &m, portMAX_DELAY) != pdTRUE) {
            continue;
        }
        switch (m.kind) {
        case PET_UI_TEXT:      apply_text(m.text);           break;
        case PET_UI_MOOD:      apply_mood(m.mood);           break;
        case PET_UI_LISTENING: apply_listening(m.listening); break;
        case PET_UI_SLEEP:     apply_sleep(m.asleep);         break;
        case PET_UI_LINK:      apply_link(m.linked);          break;
        case PET_UI_TEACH:     apply_teach(m.teach);          break;
        case PET_UI_CHROME:    apply_chrome_wake();            break;
        }
    }
}

/* --- Public API: enqueue only, never block the caller. --- */

void pet_set_text(const char *s)
{
    if (!s_ui_q || !s) {
        return;
    }
    /* A reply arriving is attention: the pet is being talked to, even if
     * nobody has touched it. Waking here is what stops a conversation being
     * held with a dimmed screen. */
    pet_activity();
    pet_ui_msg_t m = { .kind = PET_UI_TEXT };
    snprintf(m.text, sizeof(m.text), "%s", s);
    if (xQueueSend(s_ui_q, &m, 0) != pdTRUE) {   /* full: drop, don't stall BLE */
        ESP_LOGW(TAG, "UI queue full, dropped text update");
    }
}

void pet_set_link(bool connected)
{
    if (!s_ui_q) {
        return;
    }
    /* Deliberately NOT counted as attention: a phone reconnecting in the
     * user's pocket is not somebody looking at the pet, and waking on it would
     * have the screen come up every time the link flapped. */
    pet_ui_msg_t m = { .kind = PET_UI_LINK, .linked = connected };
    if (xQueueSend(s_ui_q, &m, 0) != pdTRUE) {
        ESP_LOGW(TAG, "UI queue full, dropped link update");
    }
}

void pet_set_listening_ui(bool on)
{
    if (!s_ui_q) {
        return;
    }
    pet_ui_msg_t m = { .kind = PET_UI_LISTENING, .listening = on };
    if (xQueueSend(s_ui_q, &m, 0) != pdTRUE) {
        ESP_LOGW(TAG, "UI queue full, dropped listening update");
    }
}

/*
 * v4 Status — what the PHONE is doing.
 *
 * Kept separate from the expression it used to share a byte with. "Thinking"
 * previously arrived as Mood SLEEPY, indistinguishable from a reply that
 * contained a sleepy emoji; now the pet can tell them apart.
 *
 * Rendered through the same sleepy face for the moment, because a distinct
 * "the phone is working" face is art that does not exist yet. The wire
 * separation is the point — the face can follow without another protocol bump.
 */
void pet_set_status(uint8_t status)
{
    ESP_LOGI(TAG, "status <- %u", status);
    pet_set_mood(status == PET_STATUS_THINKING ? PET_MOOD_SLEEPY
                                               : pet_sim_baseline_mood());
}

void pet_set_mood(uint8_t mood)
{
    if (!s_ui_q) {
        return;
    }
    pet_ui_msg_t m = { .kind = PET_UI_MOOD, .mood = mood };
    if (xQueueSend(s_ui_q, &m, 0) != pdTRUE) {
        ESP_LOGW(TAG, "UI queue full, dropped mood update");
    }
}

/*
 * Demo/test scaffolding: start a capture on a timer so a conversation can be
 * driven end to end without touching the pet — speak at it during the window
 * and the phone does the rest. Off by default; build with
 * `idf.py build -DPET_DEMO_CAPTURE=1`, or flip this to 1 temporarily.
 *
 * Kept in the tree rather than rewritten each time because it is genuinely the
 * fastest way to exercise mic -> BLE -> Whisper -> LLM -> Piper -> speaker, and
 * it was reconstructed twice already.
 */
#ifndef PET_DEMO_CAPTURE
#define PET_DEMO_CAPTURE 0
#endif

#if PET_DEMO_CAPTURE
#define PET_DEMO_FIRST_MS  12000
#define PET_DEMO_PERIOD_MS 45000

static void pet_demo_capture_task(void *arg)
{
    LV_UNUSED(arg);
    vTaskDelay(pdMS_TO_TICKS(PET_DEMO_FIRST_MS));
    for (;;) {
        /* Never interrupt a reply the pet is still speaking, or an utterance
         * already in progress — the window simply moves to the next period. */
        if (!pet_mic_is_listening() && !pet_spk_is_playing()) {
            ESP_LOGW(TAG, "DEMO >>> SPEAK NOW <<< capture starting");
            pet_mic_set_listening(true);
        }
        vTaskDelay(pdMS_TO_TICKS(PET_DEMO_PERIOD_MS));
    }
}
#endif

void app_main(void)
{
    ESP_LOGW(TAG, "reset reason: %s", boot_reset_reason());
    ESP_LOGI(TAG, "DigitalPet booting — panel %dx%d, screen %dx%d",
             BSP_LCD_H_RES, BSP_LCD_V_RES, PET_SCREEN_W, PET_SCREEN_H);

    /*
     * Let the CPU slow down when nothing needs it.
     *
     * 240 MHz is what the LLM-free side of this device needs at its busiest —
     * Opus encoding runs 6 ms inside a 20 ms frame budget — but that is a few
     * seconds of a conversation, not the twenty-three hours of a day the pet
     * spends sitting on a desk. Anything that actually needs the clock takes a
     * performance lock and gets it; the driver layer already does this for SPI,
     * I2C and I2S, which is why this is safe to turn on globally rather than
     * bracketing it around the audio paths by hand.
     *
     * light_sleep_enable stays FALSE. See the note in sdkconfig.defaults: it
     * stops peripherals, and the two subsystems that would notice are the ones
     * this project has already been burned by.
     *
     * Not fatal if it fails — a pet that runs hot is better than one that does
     * not boot, and the log says which happened.
     */
    /*
     * PET_LIGHT_SLEEP: build with -DPET_LIGHT_SLEEP=1 to let the chip enter
     * light sleep when idle. A flag rather than an edit, so the A/B can be run
     * without touching code — and like every other flag here it STICKS IN THE
     * CMAKE CACHE, so set it explicitly back to 0 and check the ELF.
     *
     * Off by default because it is the change most likely to break things that
     * are hard to attribute: the BLE link (whose timing comes off an imprecise
     * internal RC oscillator on this board — see sdkconfig.defaults) and the
     * I2S DMA, whose starvation failures look like a broken button rather than
     * a power setting.
     */
    const esp_pm_config_t pm_cfg = {
        .max_freq_mhz = 240,
        .min_freq_mhz = 80,
        .light_sleep_enable = PET_LIGHT_SLEEP,
    };
    const esp_err_t pm_err = esp_pm_configure(&pm_cfg);
    ESP_LOGI(TAG, "power: DFS %d-%d MHz, light sleep %s (%s)",
             pm_cfg.min_freq_mhz, pm_cfg.max_freq_mhz,
             PET_LIGHT_SLEEP ? "ON" : "off", esp_err_to_name(pm_err));

    /*
     * NVS FIRST, because the face is stored in it.
     *
     * This used to sit just above pet_ble_start(), which is where NimBLE needs
     * it — but the pet now remembers which face set it is wearing, and that has
     * to be known BEFORE anything is drawn. Initialising it after
     * pet_face_create() meant a themed pet booted wearing the default face and
     * changed its mind a second later.
     */
    esp_err_t nv = nvs_flash_init();
    if (nv == ESP_ERR_NVS_NO_FREE_PAGES || nv == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ESP_ERROR_CHECK(nvs_flash_init());
    }
    pet_face_set_restore();

    /* --- board bring-up (display only; see pet_display_start note) --- */
    lv_display_t *disp = pet_display_start();   /* AMOLED + LVGL */
    pet_display_brightness(80);
    pet_touch_start(disp);                      /* never fatal; see the note */
    xTaskCreate(pet_status_task, "pet_status", 3072, NULL, 2, NULL);

    /* --- portable pet UI (guard all lv_* calls with the LVGL port mutex) --- */
    lvgl_port_lock(0);
    pet_face_create(lv_screen_active());
#if PET_DEBUG_HUD
    hud_create(lv_screen_active());
#endif
    lvgl_port_unlock();

    ESP_LOGI(TAG, "pet face up — set '%s' (%s), %u available",
             pet_face_set_id(), s_set->name, (unsigned)pet_face_set_count());


    /* --- UI update pump: must exist before BLE can enqueue into it --- */
    s_ui_q = xQueueCreate(8, sizeof(pet_ui_msg_t));
    if (!s_ui_q) {
        ESP_LOGE(TAG, "UI queue alloc failed — remote updates disabled");
    } else {
        xTaskCreate(pet_ui_task, "pet_ui", 4096, NULL, 4, NULL);
    }

    pet_ble_start();

    /* --- mic (M4). Last, and non-fatal by construction: the face and the BLE
     * link must survive a codec that does not come up. --- */
    pet_mic_start();
    pet_spk_start();

    /* --- the simulation (DESIGN.md §1 phase 1). Entirely local: no phone, no
     * protocol. The RTC must come up first — it is what the pet ages against,
     * and without it the scores simply hold rather than guess. --- */
    pet_rtc_start();
    pet_pwr_start();
    pet_pwr_dump_rails();   /* diagnostic: which rail feeds the AMOLED */
    pet_sim_start();
    pet_imu_start(on_shake);       /* shake for 3 s to play */

#if PET_DEMO_CAPTURE
    /* Demo scaffolding — see PET_DEMO_CAPTURE. Not part of the pet. */
    xTaskCreate(pet_demo_capture_task, "pet_demo", 3072, NULL, 3, NULL);
#endif
}
