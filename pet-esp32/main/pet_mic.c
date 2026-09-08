/*
 * DigitalPet — ES8311 microphone capture and Opus uplink (M4).
 *
 * The pet records only between an explicit START and STOP from the phone
 * (AudioCtl), encodes 20 ms Opus frames, and streams them over AudioDat as they
 * are produced. Streaming rather than buffering means the transfer overlaps
 * with speaking, so an utterance is nearly delivered by the time the user stops
 * talking — while still not being a realtime link, since nothing depends on any
 * single frame arriving on time.
 *
 * Watch a session with:
 *
 *     python3 tools/serlog.py $(ls /dev/cu.usbmodem* | head -1) out.log 20
 *     strings out.log | grep -E "pet_mic"
 *
 * The level meter still logs while listening: room tone sits near -60 dBFS and
 * speech peaks around -19 dBFS, so a session that captured nothing is obvious.
 */
#include <math.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "driver/i2c_master.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "bsp/esp-bsp.h"
#include "esp_codec_dev.h"
#include "esp_audio_enc.h"
#include "esp_audio_enc_default.h"
#include "esp_opus_enc.h"
#include "pet.h"

static const char *TAG = "pet_mic";

/* Wire-format constants live in pet_proto.h so the phone cannot drift from us. */
#define MIC_FRAME_SAMPLES PET_AUDIO_FRAME_SAMPLES        /* 320 = 20 ms @ 16 kHz */
#define MIC_FRAME_BYTES   (MIC_FRAME_SAMPLES * sizeof(int16_t))

/* ES8311 analog mic gain. The capsule is quiet without it: at 0 dB normal
 * speech sits near the noise floor. 30 dB drove peaks to ~85% of full scale
 * from across a desk, which real close-up speech would clip straight through,
 * and clipping is unrecoverable where being quiet is not — Whisper normalises.
 * Worth re-checking against an actual voice rather than played-back speech. */
#define MIC_GAIN_DB       24.0f

/* Opus encoder effort, 0..10. Higher is better quality at the same bitrate but
 * costs CPU, and a frame must encode in well under its own 20 ms or the codec
 * DMA falls behind. Measured on this board: complexity 3 costs 13.2 ms/frame
 * (66% of realtime), complexity 0 costs 6.8 ms (34%). 0 wins — the consumer is
 * a speech recogniser rather than an ear, and M5 still has to fit a decoder
 * alongside this. The cost is logged every utterance; check it if you raise
 * this. */
#define MIC_COMPLEXITY    0

/* Backstop against a phone that starts capture and never stops (crash, ANR,
 * user walks away). Without it the mic would run until the battery died. */
#define MIC_MAX_UTTERANCE_MS 30000

/* --- End-pointing ----------------------------------------------------------
 *
 * Capture stops on its own when the talking stops, so a conversation is "tap,
 * speak" rather than "tap, speak, remember to tap again". Reusing the level
 * meter's RMS, which was built to debug the mic and turns out to be exactly the
 * signal needed.
 *
 * Thresholds are set from what this board actually measures, not from theory:
 * room tone sits at -58 to -62 dBFS and speech runs -46 to -2, so -50 dBFS has
 * roughly 10 dB of margin either side. In a room noisy enough to sit above that
 * line nothing is ever "silent", end-pointing simply never fires, and the user
 * falls back to tapping — degraded, not broken.
 */
#define VAD_THRESHOLD_DBFS  (-50.0f)

/* Silence that ends an utterance. Speech is full of gaps — stops between words
 * run 100-300 ms — so this has to be comfortably longer than a pause inside a
 * sentence, or the pet cuts you off mid-thought. */
#define VAD_HANG_MS         900

/* Consecutive loud frames before we believe speech has begun. One frame is a
 * click or a knock on the desk; three is 60 ms of sound. */
#define VAD_ONSET_FRAMES    3

/* If nothing is ever said, give up rather than recording the room until the
 * 30 s cap. This is the "tapped it by accident" case. */
#define VAD_LEAD_MS         5000

/*
 * Audio discarded right after the codec opens, before anything looks at it.
 *
 * The ES8311 settles for a moment after esp_codec_dev_open, and the measured
 * decay is a clean exponential from full scale down to the noise floor:
 *
 *   frame  0   1   2   3   4   5   6   7   8   9  10  11  12  13  14
 *   dBFS  -7  -2  -7 -13 -18 -23 -29 -34 -40 -45 -51 -56 -60 -64 -65
 *
 * That transient is why every utterance in this project logged `peak 32767`
 * and why the mic gain looked far too hot — the clipping was never speech.
 * It also tripped end-pointing instantly ("speech detected at -8.0 dBFS" in an
 * empty room).
 *
 * 16 frames is 320 ms, past the knee at ~frame 14 with margin. A first guess of
 * 8 was not enough: it still left -40 dBFS, a full 10 dB above the speech
 * threshold, which looks like someone talking. Costs nothing real, since a
 * person taps the button before they start speaking.
 */
#define MIC_SETTLE_FRAMES   16

/* 7-bit I2C address. esp_codec_dev's ES8311_CODEC_DEFAULT_ADDR (0x30) is the
 * 8-bit write form; i2c_master_probe() wants it shifted down. */
#define ES8311_ADDR_7BIT  0x18

/* Log one level line per this many frames — 25 * 20 ms = 2 lines/second. */
#define MIC_LOG_EVERY     25

/* Sized for the Opus encoder, which is by far the biggest consumer here:
 * 4096 overflowed on the first frame, 20480 still overflowed, and the measured
 * high-water usage is ~21.7 kB. The headroom left is logged every utterance. */
#define MIC_TASK_STACK    32768

static esp_codec_dev_handle_t s_mic;
static SemaphoreHandle_t      s_wake;       /* signals a START to the worker */
static volatile bool          s_listening;

/*
 * Is the codec actually on the bus?
 *
 * bsp_audio_codec_microphone_init() is full of assert()/ESP_ERROR_CHECK, so a
 * codec that does not answer takes the whole firmware down in a boot loop —
 * exactly how bsp_display_start() behaves when the FT3168 touch is missing (see
 * the note in pet-esp32.c). The ES8311 sits on that same marginal I2C bus, so
 * probe first and let a silent codec degrade to "no mic" instead.
 */
static bool mic_codec_present(void)
{
    esp_err_t err = bsp_i2c_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "I2C init failed (%s) - mic disabled", esp_err_to_name(err));
        return false;
    }

    i2c_master_bus_handle_t bus = bsp_i2c_get_handle();
    if (bus == NULL) {
        ESP_LOGE(TAG, "no I2C bus handle - mic disabled");
        return false;
    }

    err = i2c_master_probe(bus, ES8311_ADDR_7BIT, 200 /* ms */);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "ES8311 not responding at 0x%02x (%s) - mic disabled",
                 ES8311_ADDR_7BIT, esp_err_to_name(err));
        return false;
    }

    ESP_LOGI(TAG, "ES8311 found at 0x%02x", ES8311_ADDR_7BIT);
    return true;
}

/* Peak and RMS of one frame, both as absolute sample magnitudes. */
static void frame_level(const int16_t *pcm, size_t n, int32_t *peak, float *rms)
{
    int32_t  hi  = 0;
    uint64_t acc = 0;

    for (size_t i = 0; i < n; i++) {
        int32_t s = pcm[i];
        if (s < 0) {
            s = -s;
        }
        if (s > hi) {
            hi = s;
        }
        acc += (uint64_t)s * (uint64_t)s;
    }

    *peak = hi;
    *rms  = (n > 0) ? sqrtf((float)acc / (float)n) : 0.0f;
}

/* A 20-cell bar over -60..0 dBFS, so silence vs speech is obvious at a glance
 * in a log file rather than something to be worked out from numbers. */
static void level_bar(float dbfs, char *out, size_t out_len)
{
    const size_t cells = 20;
    if (out_len < cells + 1) {
        if (out_len > 0) {
            out[0] = '\0';
        }
        return;
    }

    int filled = (int)((dbfs + 60.0f) / 60.0f * (float)cells);
    if (filled < 0) {
        filled = 0;
    }
    if (filled > (int)cells) {
        filled = (int)cells;
    }

    memset(out, '.', cells);
    memset(out, '#', (size_t)filled);
    out[cells] = '\0';
}

/* --- Opus encoder lifetime -------------------------------------------------
 *
 * Opened and closed per utterance rather than held open, so an idle pet is not
 * sitting on the encoder's working memory.
 */

static esp_audio_enc_handle_t s_enc;

static bool encoder_open(void)
{
    esp_opus_enc_config_t opus_cfg = ESP_OPUS_ENC_CONFIG_DEFAULT();
    opus_cfg.sample_rate      = PET_AUDIO_SAMPLE_RATE;
    opus_cfg.channel          = ESP_AUDIO_MONO;
    opus_cfg.bits_per_sample  = ESP_AUDIO_BIT16;
    opus_cfg.bitrate          = PET_AUDIO_BITRATE;
    opus_cfg.frame_duration   = ESP_OPUS_ENC_FRAME_DURATION_20_MS;
    /* VOIP mode is tuned for intelligibility, which is exactly what a speech
     * recogniser downstream cares about. */
    opus_cfg.application_mode = ESP_OPUS_ENC_APPLICATION_VOIP;
    opus_cfg.complexity       = MIC_COMPLEXITY;
    /* No DTX: it would stop emitting during pauses, and Whisper does better
     * with the silence left in than with an utterance spliced together. */
    opus_cfg.enable_dtx       = false;
    opus_cfg.enable_fec       = false;
    opus_cfg.enable_vbr       = false;

    esp_audio_enc_config_t cfg = {
        .type   = ESP_AUDIO_TYPE_OPUS,
        .cfg    = &opus_cfg,
        .cfg_sz = sizeof(opus_cfg),
    };

    if (esp_audio_enc_open(&cfg, &s_enc) != ESP_AUDIO_ERR_OK) {
        ESP_LOGE(TAG, "opus encoder open failed");
        s_enc = NULL;
        return false;
    }

    /* The encoder's idea of a frame must match ours, or we would feed it short
     * buffers and quietly produce garbage. Fail loudly instead. */
    int in_size = 0, out_size = 0;
    esp_audio_enc_get_frame_size(s_enc, &in_size, &out_size);
    if (in_size != MIC_FRAME_BYTES) {
        ESP_LOGE(TAG, "encoder wants %d B/frame, we capture %d - refusing",
                 in_size, (int)MIC_FRAME_BYTES);
        esp_audio_enc_close(s_enc);
        s_enc = NULL;
        return false;
    }
    if (out_size > PET_AUDIO_MAX_FRAME) {
        ESP_LOGE(TAG, "encoder may emit %d B > PET_AUDIO_MAX_FRAME %d",
                 out_size, PET_AUDIO_MAX_FRAME);
        esp_audio_enc_close(s_enc);
        s_enc = NULL;
        return false;
    }
    return true;
}

static void encoder_close(void)
{
    if (s_enc != NULL) {
        esp_audio_enc_close(s_enc);
        s_enc = NULL;
    }
}

/* --- One capture session --------------------------------------------------- */

static void run_utterance(void)
{
    /* [seq][opus...] built in place so the notification is a single copy. */
    static uint8_t  out[PET_AUDIO_HDR_LEN + PET_AUDIO_MAX_FRAME];
    static int16_t  pcm[MIC_FRAME_SAMPLES];

    if (esp_codec_dev_open(s_mic, &(esp_codec_dev_sample_info_t){
            .bits_per_sample = 16,
            .channel         = 1,
            .channel_mask    = 0,
            .sample_rate     = PET_AUDIO_SAMPLE_RATE,
            .mclk_multiple   = 0,
        }) != ESP_CODEC_DEV_OK) {
        ESP_LOGE(TAG, "codec open failed - ignoring START");
        /* Must clear the flag, or the pet believes it is recording forever and
         * every later START is dropped as a no-op. Same failure shape as the
         * speaker's stuck s_playing. */
        s_listening = false;
        return;
    }
    esp_codec_dev_set_in_gain(s_mic, MIC_GAIN_DB);

    if (!encoder_open()) {
        esp_codec_dev_close(s_mic);
        s_listening = false;
        return;
    }

    const uint8_t started = PET_AUDIO_STARTED;
    pet_ble_notify(PET_EVT_AUDIO, &started, sizeof(started));
    pet_set_listening_ui(true);

    uint8_t  seq        = 0;
    bool     logged_toc = false;   /* seq wraps at 256, so it is not the flag */

    /* End-pointing state. */
    bool     speech     = false;   /* has the user actually started talking? */
    unsigned loud_run   = 0;
    uint32_t silent_ms  = 0;
    float    floor_db   = 0.0f;    /* quietest frame seen, for tuning */
    unsigned floor_n    = 0;
    const char *why     = "stopped";
    uint16_t sent       = 0;
    uint32_t dropped    = 0;
    uint64_t enc_us     = 0;      /* total encode time, to check the CPU budget */
    uint32_t enc_bytes  = 0;
    int32_t  peak_all   = 0;
    unsigned n          = 0;
    int32_t  win_peak   = 0;
    float    win_rms    = 0.0f;
    const int64_t t0    = esp_timer_get_time();

    /* Let the codec settle before anything sees the audio — see the note on
     * MIC_SETTLE_FRAMES. Read and throw away: not encoded, not measured. */
    for (int i = 0; i < MIC_SETTLE_FRAMES; i++) {
        if (esp_codec_dev_read(s_mic, pcm, sizeof(pcm)) != ESP_CODEC_DEV_OK) {
            break;
        }
    }

    ESP_LOGI(TAG, "listening");

    while (s_listening) {
        if (esp_codec_dev_read(s_mic, pcm, sizeof(pcm)) != ESP_CODEC_DEV_OK) {
            ESP_LOGE(TAG, "read failed - ending utterance");
            break;
        }

        int32_t peak;
        float   rms;
        frame_level(pcm, MIC_FRAME_SAMPLES, &peak, &rms);
        if (peak > win_peak)  win_peak = peak;
        if (peak > peak_all)  peak_all = peak;
        if (rms  > win_rms)   win_rms  = rms;

        /* --- end-pointing --- */
        const float frame_db = 20.0f * log10f((rms + 1.0f) / 32768.0f);
        if (floor_n++ == 0 || frame_db < floor_db) {
            floor_db = frame_db;
        }

        if (frame_db > VAD_THRESHOLD_DBFS) {
            if (!speech && ++loud_run >= VAD_ONSET_FRAMES) {
                speech = true;
                ESP_LOGI(TAG, "speech detected at %.1f dBFS", (double)frame_db);
            }
            silent_ms = 0;
        } else {
            loud_run = 0;
            silent_ms += PET_AUDIO_FRAME_MS;
        }

        if (speech && silent_ms >= VAD_HANG_MS) {
            why = "silence";
            s_listening = false;
        } else if (!speech &&
                   (esp_timer_get_time() - t0) / 1000 > VAD_LEAD_MS) {
            why = "nothing said";
            s_listening = false;
        }

        esp_audio_enc_in_frame_t  in  = { .buffer = (uint8_t *)pcm, .len = sizeof(pcm) };
        esp_audio_enc_out_frame_t enc = {
            .buffer = &out[PET_AUDIO_HDR_LEN],
            .len    = PET_AUDIO_MAX_FRAME,
        };

        int64_t e0 = esp_timer_get_time();
        esp_audio_err_t err = esp_audio_enc_process(s_enc, &in, &enc);
        enc_us += (uint64_t)(esp_timer_get_time() - e0);

        if (err != ESP_AUDIO_ERR_OK) {
            ESP_LOGE(TAG, "encode failed (%d) - ending utterance", err);
            break;
        }

        if (!logged_toc) {
            logged_toc = true;
            /* The phone feeds these straight to opus_decode(), which wants bare
             * Opus packets — not Ogg pages and not self-delimited frames. This
             * logs the packet header once per utterance as cheap proof we are
             * still emitting what the phone expects, because getting it wrong
             * fails on the far side in a much more confusing way.
             *
             * What this encoder actually produces, measured: `4b 41 05 80`.
             * TOC 0x4b = config 9 (SILK wideband, 20 ms), mono, **code 3**, so
             * the next byte is a frame-count byte: 0x41 = CBR, padding present,
             * 1 frame. Code 3 is not the single-frame code 0 you might expect,
             * but it is an ordinary self-contained Opus packet and opus_decode
             * takes it as-is. The padding is CBR hitting its target exactly —
             * 60 B/frame is 24 kbps at 20 ms, which is where the measured
             * 23.9 kbps comes from. Enabling VBR would shrink pauses a lot if
             * airtime ever matters. */
            uint8_t toc = out[PET_AUDIO_HDR_LEN];
            ESP_LOGI(TAG, "opus TOC 0x%02x (config %u, %s, code %u) len=%lu "
                          "bytes %02x %02x %02x %02x",
                     toc, toc >> 3, (toc & 0x04) ? "stereo" : "mono", toc & 0x03,
                     (unsigned long)enc.encoded_bytes,
                     out[PET_AUDIO_HDR_LEN + 0], out[PET_AUDIO_HDR_LEN + 1],
                     out[PET_AUDIO_HDR_LEN + 2], out[PET_AUDIO_HDR_LEN + 3]);
        }

        out[0] = seq++;
        enc_bytes += enc.encoded_bytes;

        if (pet_ble_notify_audio(out, (uint8_t)(PET_AUDIO_HDR_LEN + enc.encoded_bytes)) == 0) {
            sent++;
        } else {
            /* The sequence number still advanced, so the phone sees the gap and
             * can conceal it rather than splicing the audio shorter. */
            dropped++;
        }

        if (++n >= MIC_LOG_EVERY) {
            float dbfs = 20.0f * log10f((win_rms + 1.0f) / 32768.0f);
            char  bar[24];
            level_bar(dbfs, bar, sizeof(bar));
            ESP_LOGI(TAG, "mic: %6.1f dBFS |%s| sent=%u drop=%lu",
                     (double)dbfs, bar, sent, (unsigned long)dropped);
            win_peak = 0;
            win_rms  = 0.0f;
            n        = 0;
        }

        if ((esp_timer_get_time() - t0) / 1000 > MIC_MAX_UTTERANCE_MS) {
            ESP_LOGW(TAG, "utterance hit the %d ms cap - stopping",
                     MIC_MAX_UTTERANCE_MS);
            why = "cap";
            s_listening = false;
        }
    }

    encoder_close();
    esp_codec_dev_close(s_mic);
    pet_set_listening_ui(false);

    const int64_t  ms     = (esp_timer_get_time() - t0) / 1000;
    const uint16_t frames = (uint16_t)(sent + dropped);

    /* Tell the phone the utterance is complete and how many frames we produced,
     * so it can tell "the user was quiet" apart from "notifications went
     * missing" instead of silently transcribing a hole. */
    uint8_t payload[3] = {
        PET_AUDIO_STOPPED,
        (uint8_t)(frames & 0xFF),
        (uint8_t)(frames >> 8),
    };
    pet_ble_notify(PET_EVT_AUDIO, payload, sizeof(payload));

    ESP_LOGI(TAG,
             "utterance done (%s) - %lld ms, %u frames (%lu dropped), %lu B opus "
             "(%lu bps), peak %ld, floor %.1f dBFS, encode %.2f ms/frame",
             why, ms, frames, (unsigned long)dropped, (unsigned long)enc_bytes,
             ms > 0 ? (unsigned long)(enc_bytes * 8 * 1000 / ms) : 0UL,
             (long)peak_all, (double)floor_db,
             frames > 0 ? (double)enc_us / 1000.0 / frames : 0.0);

    /* Honest sizing for MIC_TASK_STACK rather than a guess that happens to
     * work — the first attempt at 4096 overflowed on the first encode. */
    ESP_LOGI(TAG, "stack headroom %u B of %u",
             (unsigned)uxTaskGetStackHighWaterMark(NULL) , (unsigned)MIC_TASK_STACK);
}

static void pet_mic_task(void *arg)
{
    for (;;) {
        /* Sleeps here between utterances: the codec is closed, so the mic is
         * genuinely off rather than being read and discarded. */
        xSemaphoreTake(s_wake, portMAX_DELAY);
        if (s_listening) {
            run_utterance();
        }
    }
}

bool pet_mic_is_listening(void)
{
    return s_listening;
}

void pet_mic_set_listening(bool on)
{
    if (s_mic == NULL || s_listening == on) {
        return;
    }
    if (on && pet_spk_is_playing()) {
        /* Half-duplex backstop — see pet_spk.c for the measurements. */
        ESP_LOGW(TAG, "START ignored - speaker is playing");
        return;
    }
    s_listening = on;
    if (on) {
        xSemaphoreGive(s_wake);   /* the worker picks it up; STOP just clears the flag */
    }
}

void pet_mic_start(void)
{
    if (s_mic != NULL) {
        return;
    }
    if (!mic_codec_present()) {
        return;   /* pet keeps working, just deaf */
    }

    s_wake = xSemaphoreCreateBinary();
    if (s_wake == NULL) {
        ESP_LOGE(TAG, "semaphore alloc failed - mic disabled");
        return;
    }

    /* Registering only Opus rather than esp_audio_enc_register_default() keeps
     * the other dozen encoders out of the binary. */
    if (esp_opus_enc_register() != ESP_AUDIO_ERR_OK) {
        ESP_LOGE(TAG, "opus encoder registration failed - mic disabled");
        return;
    }

    s_mic = bsp_audio_codec_microphone_init();
    if (s_mic == NULL) {
        ESP_LOGE(TAG, "microphone_init returned NULL - mic disabled");
        return;
    }

    /* Priority 5: above the UI pump (4) so codec DMA is drained promptly,
     * below the NimBLE host task. Reads block on DMA, so it self-paces.
     *
     * The stack is large because the Opus encoder is: 4096 overflowed
     * immediately on the first frame. The headroom left is logged at the end of
     * every utterance — check it before trimming this. */
    xTaskCreate(pet_mic_task, "pet_mic", MIC_TASK_STACK, NULL, 5, NULL);

    ESP_LOGI(TAG, "mic ready - %d Hz mono, %d ms Opus frames @ %d bps, gain %.0f dB",
             PET_AUDIO_SAMPLE_RATE, PET_AUDIO_FRAME_MS, PET_AUDIO_BITRATE,
             (double)MIC_GAIN_DB);
}
