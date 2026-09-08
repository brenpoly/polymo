#!/usr/bin/env python3
"""Non-disruptive serial reader for the ESP32-S3's native USB.

    usage: serlog.py <port-or-glob> <outfile> [seconds]

NEVER TOGGLES DTR/RTS, so it cannot reset the board or leave the S3 in the
bootloader. That property is the reason this script exists instead of
`idf.py monitor`, and it must survive any edit here.

WHY IT RECONNECTS, which is the whole of the 2026-08-05 rewrite. The previous
version treated OSError as "wait a moment and read again":

    except OSError:
        time.sleep(0.05)

When the board re-enumerates — every flash, every power cycle, every time the
pet switches itself off and on — the file descriptor becomes permanently
invalid. The old loop then spun on a dead fd for the rest of its window,
writing nothing, while `pgrep` reported it perfectly healthy and the log file
simply stopped growing. A silent capture is indistinguishable from a quiet pet.

That cost real time twice in one day: once concluding a running board was
switched off, and once losing an hour of a measurement before anyone noticed.
Both were the same mistake — trusting an instrument's silence as evidence.

So this version:

  * RE-RESOLVES THE PORT on every reconnect, because the device name changes
    across re-enumeration (`usbmodem101` vs `usbmodem1101`). Pass a glob.
  * WRITES ITS OWN FAILURES INTO THE LOG, so a gap is visible in the artefact
    rather than only in a terminal nobody kept.
  * SAYS SO WHEN IT HEARS NOTHING for IDLE_WARN_S, because "connected but
    silent" and "disconnected" need telling apart, and neither should look
    like a working capture.
  * APPENDS, never truncates. Re-running it against an existing log is a
    normal thing to do and must not destroy the previous run.
"""
import glob
import os
import sys
import termios
import time

IDLE_WARN_S = 120.0     # longer than the 30 s alive line, with room to spare
REOPEN_WAIT_S = 0.5

port_pattern, outpath = sys.argv[1], sys.argv[2]
duration = float(sys.argv[3]) if len(sys.argv) > 3 else 15.0


def resolve_port():
    """The device now, not the device when we started."""
    if os.path.exists(port_pattern):
        return port_pattern
    matches = sorted(glob.glob(port_pattern))
    if not matches and not any(c in port_pattern for c in "*?["):
        matches = sorted(glob.glob("/dev/cu.usbmodem*"))
    return matches[0] if matches else None


def open_port(path):
    """Open without disturbing the board. Same sequence as the original."""
    fd = os.open(path, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    attrs = termios.tcgetattr(fd)
    attrs[0] = 0                                   # iflag
    attrs[1] = 0                                   # oflag
    attrs[2] = termios.CS8 | termios.CREAD | termios.CLOCAL
    attrs[3] = 0                                   # lflag
    attrs[4] = termios.B115200                     # ispeed
    attrs[5] = termios.B115200                     # ospeed
    termios.tcsetattr(fd, termios.TCSANOW, attrs)
    return fd


def note(out, text):
    """Put serlog's own state into the capture, where it will be read."""
    stamp = time.strftime("%H:%M:%S")
    out.write(f"\n--- serlog {stamp}: {text} ---\n".encode())
    out.flush()


end = time.time() + duration
fd = None
last_data = time.time()
warned_idle = False
warned_no_port = False

with open(outpath, "ab") as out:
    while time.time() < end:
        if fd is None:
            path = resolve_port()
            if path is None:
                # Say it once. A capture that never found a port must not look
                # like a capture that found one and heard nothing — that is the
                # distinction this whole rewrite exists to preserve.
                if not warned_no_port:
                    note(out, f"NO PORT matching {port_pattern} - waiting")
                    warned_no_port = True
                time.sleep(REOPEN_WAIT_S)
                continue
            warned_no_port = False
            try:
                fd = open_port(path)
                note(out, f"attached to {path}")
                last_data, warned_idle = time.time(), False
            except OSError as e:
                time.sleep(REOPEN_WAIT_S)
                continue

        try:
            data = os.read(fd, 4096)
            if data:
                out.write(data)
                out.flush()
                last_data, warned_idle = time.time(), False
            else:
                # EOF on a serial fd means the far side went away.
                raise OSError("eof")
        except BlockingIOError:
            time.sleep(0.02)
        except OSError as e:
            note(out, f"port lost ({e}) - reopening")
            try:
                os.close(fd)
            except OSError:
                pass
            fd = None
            time.sleep(REOPEN_WAIT_S)
            continue

        if not warned_idle and (time.time() - last_data) > IDLE_WARN_S:
            note(out, f"no data for {IDLE_WARN_S:.0f}s - board silent or asleep")
            warned_idle = True

    note(out, "window ended")

if fd is not None:
    os.close(fd)
