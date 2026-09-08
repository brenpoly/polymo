/*
 * PolyMO — BLE peripheral (NimBLE). Protocol v1; see pet_proto.h for the
 * authoritative wire format (UUIDs, capability bits, event types).
 *
 * Advertises as PET_ADV_NAME and exposes one custom GATT service so the Android
 * app (the "brain") can drive the pet, and the pet can report back:
 *
 *   Text   write | read    UTF-8            -> pet_set_text()
 *   Mood   write | read    1 byte           -> pet_set_mood()
 *   Info   read            version + caps   (client reads this first)
 *   Event  notify          pet -> phone     (pet_ble_notify())
 *
 * Text/Mood are readable so a reconnecting client can resync rather than guess
 * what the pet is showing; pet_ble.c caches the last values written for that.
 *
 * Write callbacks run on the NimBLE host task. pet_set_* only enqueue (they do
 * not touch LVGL on this thread), so the BLE host is never blocked on a render.
 */
#include <string.h>
#include "esp_log.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "host/util/util.h"                  /* ble_store_util_delete_peer() */
#include "pet.h"

/* NimBLE's NVS-backed key store. ESP-IDF ships no public header for this entry
 * point, so it is declared by hand — the same way ESP-IDF's own NimBLE examples
 * do it. (ble_store_config.h only declares the read/write/delete internals.) */
void ble_store_config_init(void);

/*
 * THE ADVERTISED NAME, AND IT IS PART OF THE WIRE CONTRACT.
 *
 * MUST match `PetProtocol.DEVICE_NAME` in the app. The 128-bit service UUID
 * does not fit in the 31-byte advertisement alongside flags and name, so it
 * lives in the scan response — and matching a scan-response UUID is unreliable
 * across Android versions. That leaves THIS NAME as the dependable half of the
 * phone's scan filter.
 *
 * Change it on one side only and the failure is a horrible one to read: an
 * already-paired pet goes on working, because reconnects go by address, while
 * *Scan for pets* finds nothing. So pairing looks broken only for NEW pets, and
 * only on a pet that has not been reflashed.
 *
 * ONE DEFINITION because this name was in FOUR places — the GAP name, the
 * advertising field, its own strlen, and a log line. The advertising field is
 * the one the phone actually filters on; the GAP name is the one that LOOKS
 * like the answer. Renaming the obvious one and shipping is exactly the mistake
 * this prevents, and it was live for a moment during the rename.
 *
 * Renamed from "DigitalPet" with the product, 2026-08-27.
 */
#define PET_ADV_NAME "PolyMO"

static const char *TAG = "pet_ble";
static uint8_t own_addr_type;

/* UUIDs come from pet_proto.h so firmware and app cannot drift. */
static const ble_uuid128_t svc_uuid   = PET_UUID128(PET_UUID_SERVICE);
static const ble_uuid128_t text_uuid  = PET_UUID128(PET_UUID_TEXT);
static const ble_uuid128_t mood_uuid  = PET_UUID128(PET_UUID_MOOD);
static const ble_uuid128_t info_uuid  = PET_UUID128(PET_UUID_INFO);
static const ble_uuid128_t event_uuid = PET_UUID128(PET_UUID_EVENT);
static const ble_uuid128_t actl_uuid  = PET_UUID128(PET_UUID_AUDIO_CTL);
static const ble_uuid128_t adat_uuid  = PET_UUID128(PET_UUID_AUDIO_DAT);
static const ble_uuid128_t sctl_uuid  = PET_UUID128(PET_UUID_SPEAK_CTL);
static const ble_uuid128_t sdat_uuid  = PET_UUID128(PET_UUID_SPEAK_DAT);
static const ble_uuid128_t stat_uuid  = PET_UUID128(PET_UUID_STATUS);
static const ble_uuid128_t cond_uuid  = PET_UUID128(PET_UUID_CONDITION);
static const ble_uuid128_t scrn_uuid  = PET_UUID128(PET_UUID_SCREEN);
static const ble_uuid128_t cmd_uuid   = PET_UUID128(PET_UUID_COMMAND);
static const ble_uuid128_t clock_uuid = PET_UUID128(PET_UUID_CLOCK);
static const ble_uuid128_t face_uuid  = PET_UUID128(PET_UUID_FACESET);
static const ble_uuid128_t quiet_uuid = PET_UUID128(PET_UUID_QUIET);

/* Condition is notified as well as readable, so the phone learns the pet got
 * hungry without polling for it. */
static uint16_t s_cond_handle;
static uint16_t s_face_handle;

/* Fill the Condition payload from the simulation. One place, so a read and a
 * notification cannot drift apart. */
static void cond_payload(uint8_t out[PET_COND_LEN])
{
    uint8_t sat = 0, hap = 0;
    pet_sim_get(&sat, &hap);
    const uint16_t misses = pet_sim_care_mistakes();
    out[0] = sat;
    out[1] = hap;
    out[2] = (uint8_t)((pet_sim_is_calling() ? PET_COND_CALLING : 0) |
                       (pet_sim_is_sick()    ? PET_COND_SICK    : 0) |
                       (pet_sim_is_dead()    ? PET_COND_DEAD    : 0) |
                       (pet_sim_is_quiet_hours() ? PET_COND_QUIET : 0));
    out[3] = (uint8_t)(misses & 0xFF);
    out[4] = (uint8_t)(misses >> 8);
    out[5] = pet_sim_stage();                        /* v6 */

    /* v7. Unknown is 0xFF and 0 mV, which a reader must not mistake for a flat
     * battery — the PMIC may not have answered, and the gauge reads 0xFF while
     * it is still settling after a cold start. */
    uint8_t  pct = 0xFF;
    uint16_t mv  = 0;
    pet_pwr_battery(&pct, &mv, NULL);
    out[6] = pct;
    out[7] = (uint8_t)(mv & 0xFF);
    out[8] = (uint8_t)(mv >> 8);
}

/* Live connection + the Event value handle, needed to send notifications. */
static uint16_t s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
static uint16_t s_event_handle;
static uint16_t s_audio_handle;

/* Last values the phone set — served back on read so a reconnecting client can
 * resync. Not read from LVGL (that would mean cross-task access to UI state). */
/* +1 for the NUL: PET_TEXT_MAX is the wire limit, so a maximal write must fit. */
static char    s_text[PET_TEXT_MAX + 1] = "hi, i'm your pet";
static uint8_t s_mood;

static void advertise(void);
/* Defined below on_sync, which is the only caller — see the note there
 * for why it cannot run at registration time. */
static void verify_gatt_table(void);

/* Build the Info payload: [version, caps_lo, caps_hi]. */
static void info_payload(uint8_t out[PET_INFO_LEN])
{
    out[0] = PET_PROTO_VERSION;
    out[1] = (uint8_t)(PET_CAPABILITIES & 0xFF);
    out[2] = (uint8_t)((PET_CAPABILITIES >> 8) & 0xFF);
}

/* GATT read/write handler for all characteristics. */
static int chr_access(uint16_t conn_handle, uint16_t attr_handle,
                      struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    const ble_uuid_t *uuid = ctxt->chr->uuid;

    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        if (ble_uuid_cmp(uuid, &info_uuid.u) == 0) {
            uint8_t info[PET_INFO_LEN];
            info_payload(info);
            return os_mbuf_append(ctxt->om, info, sizeof(info)) == 0
                       ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
        }
        if (ble_uuid_cmp(uuid, &text_uuid.u) == 0) {
            return os_mbuf_append(ctxt->om, s_text, strlen(s_text)) == 0
                       ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
        }
        if (ble_uuid_cmp(uuid, &mood_uuid.u) == 0) {
            return os_mbuf_append(ctxt->om, &s_mood, sizeof(s_mood)) == 0
                       ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
        }
        if (ble_uuid_cmp(uuid, &cond_uuid.u) == 0) {
            uint8_t p[PET_COND_LEN];
            cond_payload(p);
            return os_mbuf_append(ctxt->om, p, sizeof(p)) == 0
                       ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
        }
        if (ble_uuid_cmp(uuid, &quiet_uuid.u) == 0) {
            /* What the pet ACTUALLY holds — it may have refused what was sent,
             * and the phone shows this rather than its own request. */
            uint16_t from = 0, to = 0;
            pet_sim_get_quiet_hours(&from, &to);
            const uint8_t p[PET_QUIET_LEN] = {
                (uint8_t)(from & 0xFF), (uint8_t)(from >> 8),
                (uint8_t)(to & 0xFF),   (uint8_t)(to >> 8),
            };
            return os_mbuf_append(ctxt->om, p, sizeof(p)) == 0
                       ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
        }
        if (ble_uuid_cmp(uuid, &face_uuid.u) == 0) {
            /* The id the pet is ACTUALLY wearing, which is the whole point of
             * this being readable: the phone never has to assume. */
            const char *id = pet_face_set_id();
            return os_mbuf_append(ctxt->om, id, strlen(id)) == 0
                       ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
        }
        return BLE_ATT_ERR_UNLIKELY;
    }

    if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        if (ble_uuid_cmp(uuid, &text_uuid.u) == 0) {
            uint16_t len = 0;
            if (ble_hs_mbuf_to_flat(ctxt->om, s_text, sizeof(s_text) - 1, &len) != 0) {
                return BLE_ATT_ERR_UNLIKELY;
            }
            s_text[len] = '\0';
            pet_set_text(s_text);
            return 0;
        }

        if (ble_uuid_cmp(uuid, &mood_uuid.u) == 0) {
            uint8_t mood = 0;
            uint16_t len = 0;
            if (ble_hs_mbuf_to_flat(ctxt->om, &mood, sizeof(mood), &len) != 0 || len < 1) {
                return BLE_ATT_ERR_UNLIKELY;
            }
            s_mood = mood;
            pet_set_mood(mood);
            /* Echo back so every subscribed client stays in sync, not just the
             * one that made the change. */
            pet_ble_notify(PET_EVT_MOOD, &mood, sizeof(mood));
            return 0;
        }

        if (ble_uuid_cmp(uuid, &stat_uuid.u) == 0) {
            uint8_t st = 0;
            uint16_t len = 0;
            if (ble_hs_mbuf_to_flat(ctxt->om, &st, sizeof(st), &len) != 0 || len < 1) {
                return BLE_ATT_ERR_UNLIKELY;
            }
            /* Only enqueues; must not block the NimBLE host (see the file note). */
            pet_set_status(st);
            return 0;
        }

        if (ble_uuid_cmp(uuid, &scrn_uuid.u) == 0) {
            uint8_t lvl = 0;
            uint16_t len = 0;
            if (ble_hs_mbuf_to_flat(ctxt->om, &lvl, sizeof(lvl), &len) != 0 || len < 1) {
                return BLE_ATT_ERR_UNLIKELY;
            }
            /* v5. The phone reports; the pet decides what it means. Cheap
             * enough for the NimBLE host task — see the note at the top. */
            pet_sim_set_screen_overuse(lvl == PET_SCREEN_OVERUSE);
            return 0;
        }

        if (ble_uuid_cmp(uuid, &face_uuid.u) == 0) {
            /*
             * v9. An unknown id is NOT an error: a phone built against a newer
             * design system will ask for sets this firmware does not have, and
             * failing the write would make that look like a broken link. The
             * pet keeps the face it has and notifies what that actually is, so
             * the app corrects itself rather than displaying a lie.
             */
            char id[PET_FACESET_ID_MAX + 1] = {0};
            uint16_t len = 0;
            if (ble_hs_mbuf_to_flat(ctxt->om, id, PET_FACESET_ID_MAX, &len) != 0) {
                return BLE_ATT_ERR_UNLIKELY;
            }
            id[len] = '\0';
            pet_face_set_select(id);
            pet_ble_notify_face_set();
            return 0;
        }

        if (ble_uuid_cmp(uuid, &quiet_uuid.u) == 0) {
            uint8_t b[PET_QUIET_LEN] = {0};
            uint16_t len = 0;
            if (ble_hs_mbuf_to_flat(ctxt->om, b, sizeof(b), &len) != 0 ||
                len < PET_QUIET_LEN) {
                return BLE_ATT_ERR_UNLIKELY;
            }
            /* A refusal is not an error: the pet keeps what it had and the
             * phone reads back the truth. See pet_sim_set_quiet_hours. */
            pet_sim_set_quiet_hours((uint16_t)(b[0] | (b[1] << 8)),
                                    (uint16_t)(b[2] | (b[3] << 8)));
            return 0;
        }

        if (ble_uuid_cmp(uuid, &clock_uuid.u) == 0) {
            /* v8: seconds since local midnight, uint32 little-endian.
             *
             * Seconds-into-the-day rather than an epoch, deliberately. The
             * pet needs to know it is 9pm; it has no use at all for the date,
             * and sending one would invite somebody to set the RTC from it —
             * which shifts every persisted timestamp and ages a saved pet by
             * months. This payload cannot be misused that way. */
            uint8_t b[4] = {0};
            uint16_t len = 0;
            if (ble_hs_mbuf_to_flat(ctxt->om, b, sizeof(b), &len) != 0 || len < 4) {
                return BLE_ATT_ERR_UNLIKELY;
            }
            const uint32_t tod = (uint32_t)b[0] | ((uint32_t)b[1] << 8) |
                                 ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
            pet_sim_set_time_of_day(tod);
            return 0;
        }

        if (ble_uuid_cmp(uuid, &cmd_uuid.u) == 0) {
            uint8_t cmd = 0;
            uint16_t len = 0;
            if (ble_hs_mbuf_to_flat(ctxt->om, &cmd, sizeof(cmd), &len) != 0 || len < 1) {
                return BLE_ATT_ERR_UNLIKELY;
            }
            if (cmd == PET_CMD_RESET) {
                /* Refused while alive, and refused HERE rather than trusting the
                 * phone to have asked first. The app does confirm — but the pet
                 * owns its own life, and "are you sure" is not something it
                 * should have to take another device's word for. */
                if (!pet_sim_is_dead()) {
                    ESP_LOGW(TAG, "reset refused - the pet is alive");
                    return BLE_ATT_ERR_UNLIKELY;
                }
                ESP_LOGW(TAG, "reset requested by the phone");
                pet_sim_reset();
            }
            return 0;
        }

        if (ble_uuid_cmp(uuid, &actl_uuid.u) == 0) {
            uint8_t ctl = 0;
            uint16_t len = 0;
            if (ble_hs_mbuf_to_flat(ctxt->om, &ctl, sizeof(ctl), &len) != 0 || len < 1) {
                return BLE_ATT_ERR_UNLIKELY;
            }
            /* Only sets a flag and signals the mic task — must not block the
             * NimBLE host here (see the note at the top of this file). */
            pet_mic_set_listening(ctl == PET_AUDIO_CTL_START);
            return 0;
        }

        if (ble_uuid_cmp(uuid, &sctl_uuid.u) == 0) {
            uint8_t ctl = 0;
            uint16_t len = 0;
            if (ble_hs_mbuf_to_flat(ctxt->om, &ctl, sizeof(ctl), &len) != 0 || len < 1) {
                return BLE_ATT_ERR_UNLIKELY;
            }
            switch (ctl) {
            case PET_SPEAK_BEGIN: pet_spk_begin(); break;
            case PET_SPEAK_END:   pet_spk_end();   break;
            default:              pet_spk_abort(); break;
            }
            return 0;
        }

        if (ble_uuid_cmp(uuid, &sdat_uuid.u) == 0) {
            /* [seq][opus...]. The sequence byte is not used for playback — the
             * pet plays what arrives in order and BLE writes cannot reorder —
             * but it stays in the format so both directions look the same and
             * a dropped write is visible if this ever grows concealment. */
            uint8_t  buf[PET_AUDIO_HDR_LEN + PET_AUDIO_MAX_FRAME];
            uint16_t len = 0;
            if (ble_hs_mbuf_to_flat(ctxt->om, buf, sizeof(buf), &len) != 0 ||
                len <= PET_AUDIO_HDR_LEN) {
                return BLE_ATT_ERR_UNLIKELY;
            }
            pet_spk_push(&buf[PET_AUDIO_HDR_LEN],
                         (uint8_t)(len - PET_AUDIO_HDR_LEN));
            return 0;
        }
    }

    return BLE_ATT_ERR_UNLIKELY;
}

/*
 * Tell the phone how the pet is. Safe to call from the simulation task — this
 * only hands the buffer to NimBLE, exactly as pet_ble_notify does.
 *
 * Silently does nothing when nobody is connected, which is the normal case: the
 * pet lives its life whether or not a phone is listening (DESIGN.md §1).
 */
void pet_ble_notify_condition(void)
{
    if (s_conn_handle == BLE_HS_CONN_HANDLE_NONE || s_cond_handle == 0) {
        return;
    }
    uint8_t p[PET_COND_LEN];
    cond_payload(p);
    struct os_mbuf *om = ble_hs_mbuf_from_flat(p, sizeof(p));
    if (om != NULL) {
        ble_gatts_notify_custom(s_conn_handle, s_cond_handle, om);
    }
}

/*
 * Tell the phone which face the pet is wearing.
 *
 * Called after every write to the face characteristic INCLUDING the ones that
 * were refused, which is the point: a phone that asked for a set this firmware
 * does not have gets told what actually happened rather than being left to
 * assume its request landed.
 */
void pet_ble_notify_face_set(void)
{
    if (s_conn_handle == BLE_HS_CONN_HANDLE_NONE || s_face_handle == 0) {
        return;
    }
    const char *id = pet_face_set_id();
    struct os_mbuf *om = ble_hs_mbuf_from_flat(id, strlen(id));
    if (om != NULL) {
        ble_gatts_notify_custom(s_conn_handle, s_face_handle, om);
    }
}

void pet_ble_notify(pet_evt_t evt, const void *payload, uint8_t len)
{
    if (s_conn_handle == BLE_HS_CONN_HANDLE_NONE || s_event_handle == 0) {
        return;                                  /* nobody connected */
    }
    if (len > 0 && payload == NULL) {
        return;
    }

    uint8_t frame[PET_EVT_HDR_LEN + 64];
    if (len > sizeof(frame) - PET_EVT_HDR_LEN) {
        ESP_LOGW(TAG, "event 0x%02x payload too big (%u)", evt, len);
        return;
    }
    frame[0] = (uint8_t)evt;
    frame[1] = len;
    if (len) {
        memcpy(&frame[PET_EVT_HDR_LEN], payload, len);
    }

    struct os_mbuf *om = ble_hs_mbuf_from_flat(frame, PET_EVT_HDR_LEN + len);
    if (om == NULL) {
        return;
    }
    /* Silently no-ops if the client has not subscribed to notifications. */
    ble_gatts_notify_custom(s_conn_handle, s_event_handle, om);
}

/* One encoded frame, already carrying its sequence byte. Returns 0 on success.
 * Never blocks on anything slow: the caller is the mic task, which has a 20 ms
 * budget per frame before the codec DMA starts overflowing. */
int pet_ble_notify_audio(const uint8_t *frame, uint8_t len)
{
    if (s_conn_handle == BLE_HS_CONN_HANDLE_NONE || s_audio_handle == 0) {
        return -1;
    }

    struct os_mbuf *om = ble_hs_mbuf_from_flat(frame, len);
    if (om == NULL) {
        return -1;   /* mbuf pool exhausted — caller counts the drop */
    }
    return ble_gatts_notify_custom(s_conn_handle, s_audio_handle, om);
}

static const struct ble_gatt_svc_def gatt_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &svc_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]){
            {
                .uuid = &text_uuid.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP |
                         BLE_GATT_CHR_F_READ,
            },
            {
                .uuid = &mood_uuid.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP |
                         BLE_GATT_CHR_F_READ,
            },
            {
                .uuid = &info_uuid.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_READ,
            },
            {
                .uuid = &event_uuid.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_NOTIFY,
                .val_handle = &s_event_handle,   /* needed to send notifies */
            },
            {
                .uuid = &actl_uuid.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
            },
            {
                .uuid = &adat_uuid.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_NOTIFY,
                .val_handle = &s_audio_handle,
            },
            {
                .uuid = &sctl_uuid.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
            },
            {
                .uuid = &stat_uuid.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
            },
            {
                .uuid = &cond_uuid.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY,
                .val_handle = &s_cond_handle,
            },
            {
                /* v6: the reset command. Write-with-response only — an
                 * irreversible action must not be fire-and-forget, and the
                 * error status is how the phone learns a reset was refused
                 * because the pet is still alive. */
                .uuid = &cmd_uuid.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_WRITE,
            },
            {
                /* v8: the local wall clock, for quiet hours. Re-sent on
                 * every connect, so DST and RTC drift correct themselves. */
                .uuid = &clock_uuid.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
            },
            {
                /* v5: screen time in. Write-with-response, unlike the audio
                 * data paths — this arrives once a minute, not fifty times a
                 * second, and a report the pet silently never received is
                 * exactly the failure the level-not-edge design is avoiding. */
                .uuid = &scrn_uuid.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
            },
            {
                /* Write-without-response: an acknowledged write per 20 ms frame
                 * would halve the achievable rate for no benefit, since a lost
                 * frame is a click rather than a correctness problem. */
                .uuid = &sdat_uuid.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
            },
            {
                /* v10: quiet hours. Readable because the pet may refuse a
                 * window, and the phone must show what it actually holds. */
                .uuid = &quiet_uuid.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE |
                         BLE_GATT_CHR_F_WRITE_NO_RSP,
            },
            {
                /* v9: the face set. Read + write + notify, and all three are
                 * load-bearing — the phone reads what the pet is wearing on
                 * connect, writes to ask for another, and is notified what
                 * actually happened, including when the answer is "no". */
                .uuid = &face_uuid.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE |
                         BLE_GATT_CHR_F_WRITE_NO_RSP | BLE_GATT_CHR_F_NOTIFY,
                .val_handle = &s_face_handle,
            },
            {0}
        },
    },
    {0}
};

static int gap_event(struct ble_gap_event *event, void *arg)
{
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        ESP_LOGI(TAG, "connect %s",
                 event->connect.status == 0 ? "established" : "failed");
        if (event->connect.status == 0) {
            s_conn_handle = event->connect.conn_handle;
            /* Announce who we are. The client may not have subscribed yet, in
             * which case this is dropped — it should read Info explicitly. */
            uint8_t info[PET_INFO_LEN];
            info_payload(info);
            pet_ble_notify(PET_EVT_READY, info, sizeof(info));
            pet_set_link(true);
        } else {
            advertise();
        }
        break;
    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "disconnected (reason 0x%x); re-advertising",
                 event->disconnect.reason);
        s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
        pet_set_link(false);
        /* Nobody left to send audio to. Without this a phone that dropped
         * mid-utterance would leave the mic capturing forever. */
        pet_mic_set_listening(false);
        pet_spk_abort();
        advertise();
        break;
    case BLE_GAP_EVENT_SUBSCRIBE:
        ESP_LOGI(TAG, "subscribe: handle=%u notify=%d",
                 event->subscribe.attr_handle, event->subscribe.cur_notify);
        break;
    case BLE_GAP_EVENT_REPEAT_PAIRING: {
        /* The peer wants to pair again but we still hold an old bond for it —
         * i.e. the phone was re-flashed/forgot, or our NVS was wiped. Drop our
         * stale record and let the new pairing proceed, instead of failing with
         * an authentication error the user can only fix by hunting through
         * Bluetooth settings. */
        struct ble_gap_conn_desc desc;
        if (ble_gap_conn_find(event->repeat_pairing.conn_handle, &desc) == 0) {
            ble_store_util_delete_peer(&desc.peer_id_addr);
            ESP_LOGW(TAG, "repeat pairing: dropped stale bond, retrying");
        }
        return BLE_GAP_REPEAT_PAIRING_RETRY;
    }
    case BLE_GAP_EVENT_ENC_CHANGE:
        /* status != 0 means the peer holds a bond we don't have (or vice
         * versa). Log it plainly — this is the failure that looks like a
         * spurious "incorrect PIN" on the phone. */
        ESP_LOGI(TAG, "encryption change: status=%d", event->enc_change.status);
        break;
    case BLE_GAP_EVENT_MTU:
        /* Text longer than 20 bytes needs this to have grown past the 23-byte
         * default; Android normally requests ~517. */
        ESP_LOGI(TAG, "MTU now %d", event->mtu.value);
        break;
    case BLE_GAP_EVENT_ADV_COMPLETE:
        advertise();
        break;
    default:
        break;
    }
    return 0;
}

static void advertise(void)
{
    struct ble_hs_adv_fields fields = {0};
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.name = (uint8_t *)PET_ADV_NAME;
    fields.name_len = strlen(PET_ADV_NAME);
    fields.name_is_complete = 1;
    int rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv_set_fields rc=%d", rc);
        return;
    }

    /* Put the 128-bit service UUID in the scan response (no room in adv data). */
    struct ble_hs_adv_fields rsp = {0};
    rsp.uuids128 = (ble_uuid128_t *)&svc_uuid;
    rsp.num_uuids128 = 1;
    rsp.uuids128_is_complete = 1;
    ble_gap_adv_rsp_set_fields(&rsp);

    struct ble_gap_adv_params advp = {0};
    advp.conn_mode = BLE_GAP_CONN_MODE_UND;   /* connectable */
    advp.disc_mode = BLE_GAP_DISC_MODE_GEN;    /* general discoverable */
    rc = ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER, &advp,
                           gap_event, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv_start rc=%d", rc);
        return;
    }
    ESP_LOGI(TAG, "advertising as %s", PET_ADV_NAME);
}

static void on_sync(void)
{
    /*
     * HERE, not after ble_gatts_add_svcs().
     *
     * add_svcs only QUEUES the definitions; handles are not assigned until the
     * host starts, which happens before this callback. Run any earlier and
     * ble_gatts_find_chr() fails for every characteristic, and the check
     * reports the entire table missing on every single boot.
     *
     * That is not a cosmetic difference. The noise is what made this check
     * worthless: eleven identical errors every boot trained the reader to skip
     * them, so when it correctly reported that 'faceset' really was missing —
     * the one time it was right — the signal was already buried. A check that
     * always fails is worse than no check, because it costs the credibility of
     * the one that would have caught the bug.
     */
    verify_gatt_table();

    ble_hs_util_ensure_addr(0);
    ble_hs_id_infer_auto(0, &own_addr_type);
    advertise();
}

static void on_reset(int reason)
{
    ESP_LOGW(TAG, "BLE host reset, reason=%d", reason);
}

static void host_task(void *param)
{
    nimble_port_run();               /* returns only on nimble_port_stop() */
    nimble_port_freertos_deinit();
}

/*
 * Check that every characteristic PET_CAPABILITIES claims is actually in the
 * table — because a capability bit and an attribute are different things.
 *
 * v4 shipped announcing caps=799 with both of its new bits set while Status and
 * Condition were missing from gatt_svcs entirely: the caps come from a #define
 * and the table is written by hand, so nothing connected them and the handshake
 * looked perfect. It cost a session and a wrong caching theory. The check is
 * three lines and turns that whole class of mistake into a boot-time log.
 *
 * Logged rather than fatal. A pet with one characteristic missing is still a
 * pet, and refusing to boot over it would be a worse failure than saying so.
 */
static void verify_gatt_table(void)
{
    static const struct { const char *name; const ble_uuid128_t *uuid; uint16_t cap; }
    claimed[] = {
        { "text",      &text_uuid, PET_CAP_TEXT   },
        { "mood",      &mood_uuid, PET_CAP_MOOD   },
        { "event",     &event_uuid, PET_CAP_EVENTS },
        { "audioctl",  &actl_uuid, PET_CAP_MIC    },
        { "speakctl",  &sctl_uuid, PET_CAP_SPEAKER },
        { "status",    &stat_uuid, PET_CAP_STATUS },
        { "condition", &cond_uuid, PET_CAP_SIM    },
        { "screen",    &scrn_uuid, PET_CAP_SCREEN },
        { "command",   &cmd_uuid,  PET_CAP_LIFE   },
        { "clock",     &clock_uuid, PET_CAP_CLOCK },
        { "faceset",   &face_uuid, PET_CAP_FACESET },
        { "quiet",     &quiet_uuid, PET_CAP_QUIET },
    };

    for (size_t i = 0; i < sizeof(claimed) / sizeof(claimed[0]); i++) {
        if ((PET_CAPABILITIES & claimed[i].cap) == 0) {
            continue;               /* not claimed, so not expected in the table */
        }
        uint16_t def_handle = 0, val_handle = 0;
        if (ble_gatts_find_chr(&svc_uuid.u, &claimed[i].uuid->u,
                               &def_handle, &val_handle) != 0) {
            ESP_LOGE(TAG, "caps claim '%s' but it is NOT in the GATT table",
                     claimed[i].name);
        }
    }
    ESP_LOGI(TAG, "protocol v%d caps=0x%03x, GATT table checked",
             PET_PROTO_VERSION, (unsigned)PET_CAPABILITIES);
}

void pet_ble_start(void)
{
    if (nimble_port_init() != 0) {
        ESP_LOGE(TAG, "nimble_port_init failed");
        return;
    }

    ble_svc_gap_init();
    ble_svc_gatt_init();

    if (ble_gatts_count_cfg(gatt_svcs) != 0 ||
        ble_gatts_add_svcs(gatt_svcs) != 0) {
        ESP_LOGE(TAG, "GATT registration failed");
        return;
    }

    ble_svc_gap_device_name_set(PET_ADV_NAME);

    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.reset_cb = on_reset;

    /* Persist pairing keys to NVS (CONFIG_BT_NIMBLE_NVS_PERSIST). Without this
     * bonds are RAM-only and every reboot orphans the phone's saved bond, which
     * the phone reports as a bond loss and which breaks encrypted reconnects. */
    ble_store_config_init();

    nimble_port_freertos_init(host_task);
    ESP_LOGI(TAG, "BLE peripheral started");
}
