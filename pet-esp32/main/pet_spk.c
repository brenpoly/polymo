/*
 * DigitalPet — ES8311 speaker output and Opus downlink (M5).
 *
 * The phone brackets an utterance with SpeakCtl BEGIN/END and writes Opus
 * frames to SpeakDat in between. Frames queue here; playback starts once
 * PET_SPEAK_PREBUFFER of them have landed, because BLE writes arrive in bursts
 * while the DAC needs a steady feed.
 *
 * What step 1's acoustic self-test measured on this board, and why the code is
 * shaped the way it is:
 *
 *   just after open    -17.9 dBFS   enabling the PA thumps audibly
 *   settled idle       -63.9 dBFS   an open, silent speaker is inaudible
 *   writing silence    -35.9 dBFS   driving I2S couples ~28 dB into the ADC
 *   500 Hz tone         -7.4 dBFS   +25 dB in-band: the speaker works
 *
 * So the codec is opened once per *utterance*, not per frame or per sentence —
 * every open costs a pop. And playback refuses to start while the mic is
 * capturing: not because the codec must be closed, but because driving it costs
 * ~28 dB of mic SNR on top of the acoustic echo between two transducers a
 * couple of centimetres apart.
 */
#include <math.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "freertos/idf_additions.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "bsp/esp-bsp.h"
#include "esp_codec_dev.h"
#include "esp_audio_dec.h"
#include "esp_audio_dec_default.h"
#include "esp_opus_dec.h"
#include "pet.h"

static const char *TAG = "pet_spk";

#define SPK_SAMPLE_RATE   PET_AUDIO_SAMPLE_RATE
#define SPK_FRAME_SAMPLES PET_AUDIO_FRAME_SAMPLES
#define SPK_FRAME_BYTES   (SPK_FRAME_SAMPLES * sizeof(int16_t))

/* 0..100. The step-1 self-test put a -8 dBFS tone at -7.4 dBFS on the pet's own
 * mic at this setting, so there is headroom either way. */
#define SPK_VOLUME        70

/* One encoded frame in the queue. Sized to what the protocol permits rather
 * than to the 60 B the encoder currently emits: anything larger would be
 * silently dropped by pet_spk_push, which is a much worse failure than using a
 * little more RAM. */
#define SPK_SLOT_BYTES    PET_AUDIO_MAX_FRAME

/* ~3 s of audio. A TTS sentence arrives far faster than it plays back, so this
 * has to hold most of one: at 3 kB/s consumed against a link several times
 * quicker, the buffer absorbs nearly the whole sentence. 150 * 161 B ~= 24 kB. */
#define SPK_QUEUE_FRAMES  150

/*
 * Silence written after the last real frame, to flush the I2S DMA.
 *
 * esp_codec_dev_write returns as soon as samples are in the DMA buffers, not
 * when they have been clocked out, and the BSP's channel holds
 * dma_desc_num(6) * dma_frame_num(240) = 1440 samples = 90 ms at 16 kHz.
 * Closing the codec disables the channel and discards that, which cut the last
 * ~90 ms off the end of every reply.
 *
 * 6 frames is 120 ms, comfortably more than the buffer holds. Writes block once
 * the DMA is full, so this is self-timing: by the time they return the real
 * audio has been played. It also gives the PA a silent tail to switch off into
 * rather than a hard cut.
 */
#define SPK_DRAIN_FRAMES  6

/*
 * Give up on an utterance that stops being fed.
 *
 * play_utterance() otherwise only exits on END or ABORT, so a phone that sends
 * BEGIN and then goes away — crashing, being force-stopped, losing the link
 * mid-sentence — leaves this spinning forever with s_playing stuck true. The
 * pet then refuses every recording request, because half-duplex believes the
 * speaker is still busy, and the symptom is that the talk button silently stops
 * working. Recovering needs a reboot, which is not something a pet should ask
 * for.
 *
 * 3 s is far longer than any legitimate gap: the phone paces to stay ~2 s ahead
 * and the prebuffer is 300 ms.
 */
#define SPK_STARVE_TIMEOUT_MS 3000

/* Decoder stack. Decode is far lighter than encode — the Opus encoder needs
 * ~21.7 kB (see pet_mic.c) against ~8 kB measured here — and internal RAM is
 * the scarce resource on this board, so this is sized from the measurement with
 * headroom rather than copied from the mic. It is logged every utterance. */
#define SPK_TASK_STACK    16384

typedef struct {
    uint8_t len;
    uint8_t data[SPK_SLOT_BYTES];
} spk_frame_t;

static esp_codec_dev_handle_t s_spk;
static QueueHandle_t          s_q;
static volatile bool          s_playing;     /* an utterance is in progress */
static volatile bool          s_ended;       /* END seen; drain and stop     */

bool pet_spk_is_playing(void)
{
    return s_playing;
}

/* --- Opus decoder ---------------------------------------------------------- */

static esp_audio_dec_handle_t s_dec;

static bool decoder_open(void)
{
    esp_opus_dec_cfg_t opus_cfg = {
        .sample_rate     = SPK_SAMPLE_RATE,
        .channel         = ESP_AUDIO_MONO,
        .frame_duration  = ESP_OPUS_DEC_FRAME_DURATION_20_MS,
        /* The phone sends bare Opus packets, exactly as the pet's own uplink
         * emits them. Self-delimited framing is a different wire format and
         * would decode to noise. */
        .self_delimited  = false,
    };

    esp_audio_dec_cfg_t cfg = {
        .type   = ESP_AUDIO_TYPE_OPUS,
        .cfg    = &opus_cfg,
        .cfg_sz = sizeof(opus_cfg),
    };

    if (esp_audio_dec_open(&cfg, &s_dec) != ESP_AUDIO_ERR_OK) {
        ESP_LOGE(TAG, "opus decoder open failed");
        s_dec = NULL;
        return false;
    }
    return true;
}

static void decoder_close(void)
{
    if (s_dec != NULL) {
        esp_audio_dec_close(s_dec);
        s_dec = NULL;
    }
}

/* --- Public API, called from the NimBLE host task -------------------------- */

void pet_spk_begin(void)
{
    if (s_q == NULL || s_playing) {
        return;
    }
    if (pet_mic_is_listening()) {
        /* Half-duplex backstop. The phone owns both ends and should never ask
         * for this, but honouring it would cost ~28 dB of mic SNR. */
        ESP_LOGW(TAG, "BEGIN ignored - mic is capturing");
        return;
    }

    xQueueReset(s_q);
    s_ended   = false;
    s_playing = true;
}

void pet_spk_push(const uint8_t *frame, uint8_t len)
{
    if (!s_playing || s_q == NULL || len == 0 || len > SPK_SLOT_BYTES) {
        return;
    }
    spk_frame_t f;
    f.len = len;
    memcpy(f.data, frame, len);

    /* Never block the BLE host. A full queue means the phone outran ~4 s of
     * buffer, which is a pacing bug worth seeing rather than hiding. */
    if (xQueueSend(s_q, &f, 0) != pdTRUE) {
        ESP_LOGW(TAG, "speak queue full, dropped a frame");
    }
}

void pet_spk_end(void)
{
    s_ended = true;
}

void pet_spk_abort(void)
{
    if (s_q != NULL) {
        xQueueReset(s_q);
    }
    s_ended   = true;
    s_playing = false;
}

/* --- Playback -------------------------------------------------------------- */

static void play_utterance(void)
{
    static int16_t pcm[SPK_FRAME_SAMPLES];
    spk_frame_t    f;

    if (!decoder_open()) {
        s_playing = false;
        return;
    }

    if (esp_codec_dev_open(s_spk, &(esp_codec_dev_sample_info_t){
            .bits_per_sample = 16,
            .channel         = 1,
            .channel_mask    = 0,
            .sample_rate     = SPK_SAMPLE_RATE,
            .mclk_multiple   = 0,
        }) != ESP_CODEC_DEV_OK) {
        ESP_LOGE(TAG, "codec open failed - dropping utterance");
        decoder_close();
        s_playing = false;
        return;
    }
    esp_codec_dev_set_out_vol(s_spk, SPK_VOLUME);

    const int64_t t0      = esp_timer_get_time();
    uint32_t      played  = 0;
    uint32_t      starved = 0;
    uint64_t      dec_us  = 0;

    for (;;) {
        /* Wait briefly for a frame. A short timeout rather than portMAX_DELAY
         * so END can be noticed even if the queue happens to be empty. */
        if (xQueueReceive(s_q, &f, pdMS_TO_TICKS(PET_AUDIO_FRAME_MS * 2)) != pdTRUE) {
            if (s_ended || !s_playing) {
                break;      /* nothing left and no more coming */
            }
            if (++starved * PET_AUDIO_FRAME_MS * 2 >= SPK_STARVE_TIMEOUT_MS) {
                ESP_LOGW(TAG, "no frames for %d ms - abandoning utterance",
                         SPK_STARVE_TIMEOUT_MS);
                break;
            }
            continue;
        }
        if (!s_playing) {
            break;          /* aborted */
        }

        esp_audio_dec_in_raw_t  in  = { .buffer = f.data, .len = f.len };
        esp_audio_dec_out_frame_t out = {
            .buffer = (uint8_t *)pcm,
            .len    = sizeof(pcm),
        };

        int64_t d0 = esp_timer_get_time();
        esp_audio_err_t err = esp_audio_dec_process(s_dec, &in, &out);
        dec_us += (uint64_t)(esp_timer_get_time() - d0);

        if (err != ESP_AUDIO_ERR_OK) {
            ESP_LOGE(TAG, "decode failed (%d) - ending utterance", err);
            break;
        }

        if (esp_codec_dev_write(s_spk, pcm, out.decoded_size) != ESP_CODEC_DEV_OK) {
            ESP_LOGE(TAG, "write failed - ending utterance");
            break;
        }
        played++;
    }

    /* Flush the DMA before closing, or the tail is discarded. See
     * SPK_DRAIN_FRAMES for why this is silence rather than a delay. */
    memset(pcm, 0, sizeof(pcm));
    for (int i = 0; i < SPK_DRAIN_FRAMES; i++) {
        if (esp_codec_dev_write(s_spk, pcm, sizeof(pcm)) != ESP_CODEC_DEV_OK) {
            break;
        }
    }

    esp_codec_dev_close(s_spk);
    decoder_close();
    s_playing = false;

    const int64_t ms = (esp_timer_get_time() - t0) / 1000;
    ESP_LOGI(TAG, "spoke %lu frames in %lld ms (%lu starved), decode %.2f ms/frame",
             (unsigned long)played, ms, (unsigned long)starved,
             played ? (double)dec_us / 1000.0 / played : 0.0);
    ESP_LOGI(TAG, "stack headroom %u B of %u",
             (unsigned)uxTaskGetStackHighWaterMark(NULL), (unsigned)SPK_TASK_STACK);

    /* Let the phone know it is safe to listen again. */
    const uint8_t done = PET_AUDIO_SPEAK_DONE;
    pet_ble_notify(PET_EVT_AUDIO, &done, sizeof(done));
}

/*
 * The pet's own voice for asking, as distinct from speaking a reply.
 *
 * Two short rising chirps in ONE codec session. One session because every
 * esp_codec_dev_open pops audibly (-17.9 dBFS on the pet's own mic, measured at
 * M5), so a two-note call made of two sessions would click twice.
 *
 * Refuses while the mic is capturing or an utterance is playing: driving the
 * speaker costs ~28 dB of mic SNR, and interrupting a reply to beg for food is
 * worse than waiting a few seconds.
 */
#define BEEP_NOTE_MS   140
#define BEEP_GAP_MS     90
#define BEEP_HZ_LOW    660
#define BEEP_HZ_HIGH   880

void pet_spk_beep(void)
{
    static int16_t pcm[SPK_FRAME_SAMPLES];

    if (s_spk == NULL || s_playing || pet_mic_is_listening()) {
        return;
    }

    if (esp_codec_dev_open(s_spk, &(esp_codec_dev_sample_info_t){
            .bits_per_sample = 16,
            .channel         = 1,
            .channel_mask    = 0,
            .sample_rate     = SPK_SAMPLE_RATE,
            .mclk_multiple   = 0,
        }) != ESP_CODEC_DEV_OK) {
        ESP_LOGE(TAG, "beep: codec open failed");
        return;
    }
    esp_codec_dev_set_out_vol(s_spk, SPK_VOLUME);

    const int note_frames = BEEP_NOTE_MS / PET_AUDIO_FRAME_MS;
    const int gap_frames  = BEEP_GAP_MS  / PET_AUDIO_FRAME_MS;
    const int hz[2] = { BEEP_HZ_LOW, BEEP_HZ_HIGH };

    double phase = 0.0;
    for (int note = 0; note < 2; note++) {
        const double step = 2.0 * M_PI * hz[note] / SPK_SAMPLE_RATE;
        for (int f = 0; f < note_frames; f++) {
            for (int n = 0; n < SPK_FRAME_SAMPLES; n++) {
                pcm[n] = (int16_t)(9000.0 * sin(phase));
                phase += step;
            }
            if (esp_codec_dev_write(s_spk, pcm, sizeof(pcm)) != ESP_CODEC_DEV_OK) {
                goto done;
            }
        }
        if (note == 0) {
            memset(pcm, 0, sizeof(pcm));
            for (int f = 0; f < gap_frames; f++) {
                if (esp_codec_dev_write(s_spk, pcm, sizeof(pcm)) != ESP_CODEC_DEV_OK) {
                    goto done;
                }
            }
        }
    }

done:
    /* Same reason as play_utterance: the write returns when samples reach the
     * DMA, not when they are heard, and closing discards what is still queued. */
    memset(pcm, 0, sizeof(pcm));
    for (int i = 0; i < SPK_DRAIN_FRAMES; i++) {
        if (esp_codec_dev_write(s_spk, pcm, sizeof(pcm)) != ESP_CODEC_DEV_OK) break;
    }
    esp_codec_dev_close(s_spk);
}

static void pet_spk_task(void *arg)
{
    for (;;) {
        if (!s_playing) {
            vTaskDelay(pdMS_TO_TICKS(20));
            continue;
        }

        /* Wait for enough frames to absorb a gap between BLE bursts, but give
         * up if END arrives first — a one-word reply is shorter than the
         * prebuffer and must still play. */
        const int64_t wait_start = esp_timer_get_time();
        while (uxQueueMessagesWaiting(s_q) < PET_SPEAK_PREBUFFER &&
               !s_ended && s_playing &&
               (esp_timer_get_time() - wait_start) < 2000000) {
            vTaskDelay(pdMS_TO_TICKS(10));
        }

        if (s_playing) {
            play_utterance();
        }
    }
}

void pet_spk_start(void)
{
    if (s_spk != NULL) {
        return;
    }

    s_spk = bsp_audio_codec_speaker_init();
    if (s_spk == NULL) {
        ESP_LOGE(TAG, "speaker init returned NULL - no audio out");
        return;
    }

    /* In PSRAM: 150 * 161 B is ~24 kB, and internal RAM is the scarce resource
     * here — the Opus encoder and decoder tasks already want 32 kB and 24 kB of
     * stack between them, which is what starved the first attempt at this.
     * Nothing touches the queue from an ISR, so external RAM is fine. */
    s_q = xQueueCreateWithCaps(SPK_QUEUE_FRAMES, sizeof(spk_frame_t),
                               MALLOC_CAP_SPIRAM);
    if (s_q == NULL) {
        ESP_LOGE(TAG, "speak queue alloc failed - no audio out");
        s_spk = NULL;
        return;
    }

    if (esp_opus_dec_register() != ESP_AUDIO_ERR_OK) {
        ESP_LOGE(TAG, "opus decoder registration failed - no audio out");
        s_spk = NULL;
        return;
    }

    /* Priority 5, same as the mic worker: both must keep up with codec DMA,
     * and half-duplex means they never contend. */
    if (xTaskCreate(pet_spk_task, "pet_spk", SPK_TASK_STACK, NULL, 5, NULL) != pdPASS) {
        ESP_LOGE(TAG, "speak task alloc failed - no audio out");
        s_spk = NULL;
        return;
    }

    ESP_LOGI(TAG, "speaker ready - %d Hz mono, volume %d, %d-frame prebuffer, "
                  "%u B internal heap free",
             SPK_SAMPLE_RATE, SPK_VOLUME, PET_SPEAK_PREBUFFER,
             (unsigned)heap_caps_get_free_size(MALLOC_CAP_INTERNAL));
}
