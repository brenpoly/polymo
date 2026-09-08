# PolyMO

The product is **PolyMO** (renamed from "Digital Pet", 2026-08-27). The repo
directory, the Android package `com.digitalpet` and the firmware's internal
symbols are deliberately unchanged: renaming the package would be a different
install, losing models, pairing and transcript.

A physical digital pet: an **ESP32-S3 Waveshare Touch-AMOLED-1.8** board running
LVGL, paired over BLE to an **Android app** that does all the thinking on-device
(Whisper → llama.cpp → Piper). You tap the pet, speak at the pet, and the pet
answers out of its own speaker. The phone is compute; it is not a participant.

## Read these first

| Document | What it is |
|---|---|
| [`DESIGN.md`](DESIGN.md) | Product and UX: visual identity, flows, component rules, and **§7, the round trip with Claude Design** — read §7.3 before editing a mock. |

## Layout

```
pet-esp32/      ESP-IDF firmware for the current pet — the display, mic and speaker
android/        the app: BLE, Whisper, llama.cpp, Piper, and the conversation engine
design-system/  vendored copy of the Claude Design files — the sync tests read
                it. tokens/, strings.txt, components.txt, plus faces/ (what the
                pet LOOKS like) and personas/ (what it SAYS). Code is GENERATED
                from the last two — see below.
tools/          design-sync.sh (the front door), gen-faces.py, gen-personas.py,
                design-remote.py   (serlog.py lives in pet-esp32/tools/)
shared/         the wire protocol header both sides build against
DESIGN.md       product/UX
```

The git root is this directory, not `pet-esp32/`. Firmware and app share a wire
protocol (`main/pet_proto.h` ↔ `ble/PetProtocol.kt`) and must change in step.

## Build

```bash
# firmware
. ~/esp/esp-idf/export.sh
cd pet-esp32 && idf.py build
idf.py -p $(ls /dev/cu.usbmodem* | head -1) flash   # port name changes on re-plug

# android — MUST use JDK 21; the system JDK is 25 and Kotlin 2.1 cannot parse it
cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew assembleDebug --offline
```

Tests: `./gradlew testDebugUnitTest --offline` (512, no device needed).
**Write them by mutation** — every test here was checked by breaking the code it
covers and confirming it fails. That practice has already caught two worthless
tests in this repo. The second was a `ContrastTest` assertion
that read the palette file instead of the theme, so rewiring a colour role left
it green.

Most are pure logic. Five are not, and they are the ones that catch what a build
cannot — all five read files rather than calling functions:

| | |
|---|---|
| `ContrastTest` | WCAG ratios over both colour schemes, measured on the schemes themselves |
| `PetLiteralsTest` | scans UI sources, fails on a raw `.dp` or `.sp` |
| `TokenSyncTest` | every colour role, token, radius and type style against `design-system/tokens/` |
| `ComponentRosterTest` | `ui/components/` against `design-system/components.txt` |
| `FaceSyncTest` | the compiled face sets against `design-system/faces/*.json` |

They exist because a visual property that is *arithmetic* never needed eyes.
**`design-system/` and `ui/` are declared test inputs in `app/build.gradle.kts`** —
without that, Gradle skipped the test task as UP-TO-DATE whenever only those
files changed, which is the one case the sync tests exist for.

## Conventions worth knowing before changing anything

- **Verify on hardware, not by building.** Nearly every hard bug in this project
  looked fine in a build and wrong on the board. Record the measurement, not
  just the fix.
- **Commit messages carry the *why*.** They are long here on purpose and are a
  primary record. Write them with `git commit -F <file>` — backticks in
  `-m "…"` get executed by the shell and silently delete text.
- **A comment that is now false is worse than no comment.** Several bugs here
  were prolonged by confident, stale notes. If a change inverts one, fix it in
  the same commit.

## The pet's face and voice are GENERATED — do not hand-edit them

`design-system/faces/*.json` is the source for the firmware's `pet_faces.h`, the
app's `PetFaceSets.kt`, 32 notification drawables and four design-system cards.
`design-system/personas/*.json` is the source for `PetPersonas.kt`, which holds
both the LLM system prompt and every line the pet says without being asked.

```bash
tools/gen-faces.py        # after editing a face set
tools/gen-personas.py     # after editing a persona
```

Both refuse input that breaks a rule, and say which: a happy face must smile, a
dead one has no mouth, sad and sick must not be confusable, a spiral eye must
turn and must not wind tight enough to fill in, a persona may not ask a question
or name several apps aloud, and nothing may restate a `PET_RULES` line. **Those rules are the design, expressed where they can fail a build** —
read the two READMEs before adding a set.

Three things that are NOT a persona's to change, because they belong to the pet
rather than to a character: the reply-length cap (the screen fits six lines), the
no-history rule (no history is sent, so without it the model invents callbacks),
and staying in character. They are appended to every prompt automatically.

**A persona is paired with a face set.** Selecting a personality sets both, so
the pet cannot look like one character and speak as another.

## Where this is heading

The pet is becoming a **virtual pet you keep alive** — hunger, happiness, life
stages, death and reset — with **screen time as the mechanic that affects its
wellbeing**. The voice conversation is not the product; it is how the pet talks.
[`DESIGN.md`](DESIGN.md) §1 has the direction, the four structural decisions and
a phased roadmap. Read it before adding anything to either surface.

The decision most likely to be violated by accident: **the pet owns its own
life.** Simulation state is authoritative on the device — NVS for persistence,
its RTC for time — not on the phone. The phone contributes screen time, language
and models, which are inputs, not a reason to move the state.

DESIGN.md's **state model (§2) and IA (§5) are both current and §5 is now
built** — it was a deliberate stub until the product stopped being a chat app,
and then shipped in a day. **The UI comes from Claude Design**, and §7 covers
that round trip: where the file is, how to read one screen out of it, and — most
importantly — **§7.3, the places the build has deliberately left the mocks
behind.** Edit a mock without reading it and you will re-propose something
already tried under a thumb.

**The design system is in the codebase, not only in the design tool** (§7.7).
Every dp and sp comes from `ui/theme/PetTokens.kt` — §6.1's fourth style layer —
and a test fails the build on a raw one. Every component the design system names
is a shared file under `ui/components/`, grouped as it groups them. A screen
composes; it does not draw.

**The Claude Design system outranks this document** (§7.2, decided 2026-08-09).
Where the design project and a decision recorded in `DESIGN.md`
disagree, the design system is right and `DESIGN.md` is what changes. Both
`DESIGN-SYSTEM.md` and `tokens/colors.css` used to assert the opposite in their
own headers and were rewritten to say this instead, so the two sides agree about
which of them wins. **One narrow exception survives**: the code remains
authoritative about *what a number means*, because a mock can draw a comparison
the simulation does not make. It is authoritative about nothing else.

The system is generated *from* this code, so it is sometimes a snapshot rather
than a proposal. **When it is quoting the code back and the quote is out of date,
regenerate it — do not revert the code.**

**Which makes the round trip part of the job, not an optional extra.** An
authority that lags the code stops deserving the rank, so **a palette or token
change is not finished until it is pushed back to the design project** via the
DesignSync MCP.

```bash
./tools/design-sync.sh
```

is the front door: it checks both halves and says which way the work goes. The
fetch and the push need Claude, because the project sits behind a claude.ai login
a shell cannot reach — ask for *"refresh design-system/tokens/ from the design
project"* or *"push design-system/tokens/ back"*. `design-system/README.md` has
why the copy is vendored rather than fetched at test time.

Two known-wrong things it documents rather than hides: the pet's `PET_COLOR_EYE`
and `PET_COLOR_TEXT` came from placeholder text in an earlier draft (the palette
is `ui/theme/Color.kt`, and the pet has not moved to it), and `Mood` currently
carries both emotion and system status in one byte, which is why the pet cannot
show "no phone" or "switched off".
