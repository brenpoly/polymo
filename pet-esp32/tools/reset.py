#!/usr/bin/env python3
"""
Plain hard reset of the board — no flashing, no download mode.

Distinguishes "the display dies on any warm reset" from "the display dies
because of something the flashing process does". esptool always enters download
mode, so it cannot tell those apart; this only pulses EN.

    python3 tools/reset.py /dev/cu.usbmodemXXXX

EN is driven by RTS, GPIO0 by DTR. Holding DTR deasserted keeps GPIO0 high, so
the chip comes back up running the application rather than the bootloader.
"""
import sys
import time

import serial

port = sys.argv[1] if len(sys.argv) > 1 else None
if not port:
    sys.exit("usage: reset.py <port>")

# dsrdtr=False so opening the port does not itself pulse the lines.
s = serial.Serial(port, 115200, dsrdtr=False, rtscts=False)
try:
    s.dtr = False   # GPIO0 high: run the app, do not enter download mode
    s.rts = True    # EN low: hold in reset
    time.sleep(0.1)
    s.rts = False   # EN high: run
    time.sleep(0.1)
    print(f"reset {port} (EN pulsed, GPIO0 left high)")
finally:
    s.close()
