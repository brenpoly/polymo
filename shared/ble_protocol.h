/**
 * @file ble_protocol.h
 * @brief Shared BLE protocol definitions for Digital Pet audio streaming.
 *
 * This header is shared between the nRF54L15 firmware and the companion
 * mobile application. It defines all UUIDs, packet formats, control opcodes,
 * and status structures used for bidirectional audio streaming over BLE.
 *
 * Protocol Overview:
 *   - Audio TX: Opus-encoded mic audio from device -> phone (Notify)
 *   - Audio RX: Opus-encoded TTS audio from phone -> device (Write No Response)
 *   - Control:  Command/response channel (Write + Indicate)
 *   - Status:   Device state reporting (Read + Notify)
 */

#ifndef BLE_PROTOCOL_H
#define BLE_PROTOCOL_H

#ifdef __ZEPHYR__
#include <zephyr/bluetooth/uuid.h>
#else
/* Non-Zephyr builds: provide the UUID encode macro for portability */
#ifndef BT_UUID_128_ENCODE
#define BT_UUID_128_ENCODE(w32, w1, w2, w3, w48) \
    (((w48) >>  0) & 0xFF), (((w48) >>  8) & 0xFF), \
    (((w48) >> 16) & 0xFF), (((w48) >> 24) & 0xFF), \
    (((w48) >> 32) & 0xFF), (((w48) >> 40) & 0xFF), \
    (((w3)  >>  0) & 0xFF), (((w3)  >>  8) & 0xFF), \
    (((w2)  >>  0) & 0xFF), (((w2)  >>  8) & 0xFF), \
    (((w1)  >>  0) & 0xFF), (((w1)  >>  8) & 0xFF), \
    (((w32) >>  0) & 0xFF), (((w32) >>  8) & 0xFF), \
    (((w32) >> 16) & 0xFF), (((w32) >> 24) & 0xFF)
#endif
#endif

#include <stdint.h>

/* =========================================================================
 * Custom 128-bit UUIDs
 *
 * Base UUID: d7a70001-5b26-4801-9992-0a76e8c12345
 *
 * Service:   d7a70001-5b26-4801-9992-0a76e8c12345
 * Audio TX:  d7a70002-5b26-4801-9992-0a76e8c12345  (Notify - mic to phone)
 * Audio RX:  d7a70003-5b26-4801-9992-0a76e8c12345  (Write NR - TTS to device)
 * Control:   d7a70004-5b26-4801-9992-0a76e8c12345  (Write + Indicate)
 * Status:    d7a70005-5b26-4801-9992-0a76e8c12345  (Read + Notify)
 * ========================================================================= */

/** Digital Pet Audio Service UUID */
#define DPET_SERVICE_UUID_VAL \
    BT_UUID_128_ENCODE(0xd7a70001, 0x5b26, 0x4801, 0x9992, 0x0a76e8c12345ULL)

/** Audio TX characteristic UUID - Opus mic data sent via notifications */
#define DPET_AUDIO_TX_UUID_VAL \
    BT_UUID_128_ENCODE(0xd7a70002, 0x5b26, 0x4801, 0x9992, 0x0a76e8c12345ULL)

/** Audio RX characteristic UUID - Opus TTS data received via write-no-response */
#define DPET_AUDIO_RX_UUID_VAL \
    BT_UUID_128_ENCODE(0xd7a70003, 0x5b26, 0x4801, 0x9992, 0x0a76e8c12345ULL)

/** Control characteristic UUID - Command/response channel */
#define DPET_CONTROL_UUID_VAL \
    BT_UUID_128_ENCODE(0xd7a70004, 0x5b26, 0x4801, 0x9992, 0x0a76e8c12345ULL)

/** Status characteristic UUID - Device state reporting */
#define DPET_STATUS_UUID_VAL \
    BT_UUID_128_ENCODE(0xd7a70005, 0x5b26, 0x4801, 0x9992, 0x0a76e8c12345ULL)

#ifdef __ZEPHYR__
#define DPET_SERVICE_UUID  BT_UUID_DECLARE_128(DPET_SERVICE_UUID_VAL)
#define DPET_AUDIO_TX_UUID BT_UUID_DECLARE_128(DPET_AUDIO_TX_UUID_VAL)
#define DPET_AUDIO_RX_UUID BT_UUID_DECLARE_128(DPET_AUDIO_RX_UUID_VAL)
#define DPET_CONTROL_UUID  BT_UUID_DECLARE_128(DPET_CONTROL_UUID_VAL)
#define DPET_STATUS_UUID   BT_UUID_DECLARE_128(DPET_STATUS_UUID_VAL)
#endif

/* =========================================================================
 * Audio Packet Format
 *
 * All audio data (TX and RX) uses the following packet header prepended
 * to the Opus-encoded payload. Total MTU target: 251 bytes (DLE),
 * ATT overhead: 3 bytes, leaving 248 bytes for ATT value.
 * Packet header: 3 bytes, leaving 241 bytes for Opus payload.
 *
 * Byte layout:
 *   [0..1] seq_num  - Little-endian sequence number (wraps at 65535)
 *   [2]    flags    - Bitfield flags
 *   [3..N] payload  - Opus-encoded audio data
 * ========================================================================= */

/** Audio packet header size in bytes (seq_num: 2 + flags: 1) */
#define AUDIO_HEADER_SIZE  3

/**
 * Maximum audio payload size per BLE packet.
 * Based on 247-byte ATT value (251 MTU - 4 ATT header) minus 3 byte header.
 * We use 244 as effective ATT value to align with common BLE stacks.
 */
#define MAX_AUDIO_PAYLOAD  (244 - AUDIO_HEADER_SIZE)  /* 241 bytes */

/** Audio packet header prepended to all audio characteristic data */
struct __attribute__((packed)) audio_packet_header {
    uint16_t seq_num;     /**< Monotonically increasing sequence number */
    uint8_t  flags;       /**< Bitfield of AUDIO_FLAG_* values */
    uint8_t  payload[];   /**< Opus-encoded audio data (flexible array) */
};

/* Audio flag bit definitions */
/** First packet of a speech segment (after VAD triggers) */
#define AUDIO_FLAG_START    0x01
/** Last packet of a speech segment (after VAD hangover expires) */
#define AUDIO_FLAG_END      0x02
/** Packet contains silence/comfort noise (no speech detected) */
#define AUDIO_FLAG_SILENCE  0x04

/* =========================================================================
 * Control Opcodes
 *
 * Control characteristic uses a simple opcode-based protocol:
 *   Byte 0: Opcode
 *   Byte 1..N: Opcode-specific parameters (if any)
 *
 * Responses are sent via Indications with the same opcode followed
 * by a status byte (0x00 = success, nonzero = error code).
 * ========================================================================= */

/** Start audio streaming from device microphone */
#define CMD_START_STREAM       0x01
/** Stop audio streaming */
#define CMD_STOP_STREAM        0x02
/** Set VAD energy threshold (param: uint16_t threshold, little-endian) */
#define CMD_SET_VAD_THRESHOLD  0x03
/** Request device status report (response via Status characteristic) */
#define CMD_GET_STATUS         0x04
/** Set Opus encoder bitrate (param: uint32_t bitrate, little-endian) */
#define CMD_SET_OPUS_BITRATE   0x05

/* Control response status codes */
#define CTRL_STATUS_SUCCESS    0x00
#define CTRL_STATUS_ERROR      0x01
#define CTRL_STATUS_INVALID    0x02

/* =========================================================================
 * Status Report
 *
 * Read from the Status characteristic or received as a notification
 * when device state changes significantly (e.g., VAD state transition,
 * battery level change).
 * ========================================================================= */

/** Device status report structure */
struct __attribute__((packed)) status_report {
    uint8_t battery_pct;    /**< Battery level as percentage (0-100) */
    uint8_t vad_state;      /**< Current VAD state (see vad_state_t) */
    int8_t  ble_rssi;       /**< BLE connection RSSI in dBm */
    uint8_t firmware_major; /**< Firmware major version */
    uint8_t firmware_minor; /**< Firmware minor version */
};

/* Firmware version constants */
#define FIRMWARE_VERSION_MAJOR  1
#define FIRMWARE_VERSION_MINOR  0

#endif /* BLE_PROTOCOL_H */
