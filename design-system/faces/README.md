# Face sets

A **face set** is every expression the pet can wear, in one style. One JSON file
per set; the code for all four surfaces is generated from it.

```
design-system/faces/<id>.json   the set — THIS IS THE SOURCE
        │
        └── tools/gen-faces.py
                ├── pet-esp32/main/pet_faces.h              the pet itself
                ├── android/…/pet/PetFaceSets.kt            the app's panel
                ├── android/…/res/drawable/pet_face_*.xml   the notification
                ├── design-system/components/pet/faceSets.jsx  the prototype
                └── design-system/faces/preview/<id>.card.html the cards
```

A set is also **paired with a persona** — the `persona` key names a file in
`../personas/`, and selecting a personality sets both, so the pet cannot look
like one character and speak as another. `gen-faces.py` fails on a persona id
that does not exist. See `../personas/README.md`.

## This inverts who owns a face

Face geometry used to live in `apply_mood()` in C, with `PetFace.kt` mirroring
it by hand and `PetFaceTest` pinning the numbers as literals so drift failed the
build. That was right when there was one face set and the firmware was the only
thing that drew it.

It is wrong now. DESIGN.md §7.2 says the design system outranks, and a face is
appearance — exactly the thing the design system is supposed to own. So the JSON
is the source and all four surfaces are generated. **Do not hand-edit the
generated files**; the generator overwrites them and `FaceSyncTest` fails the
build when they drift.

The narrow exception in CLAUDE.md still holds and is not affected: the code
remains authoritative about *what a number means*. A set says the sick face's
eyes are two 76px spirals. It does not get to say what "sick" is — `pet_sim.c`
does.

## The eight states

Seven moods plus one overlay. `PET_MOOD_*` in `pet.h` is the wire order and the
generated table follows it.

| key | | |
|---|---|---|
| `neutral` | 0 | the baseline |
| `happy` | 1 | |
| `sleepy` | 2 | transient — phone-written, decays on the pet |
| `surprised` | 3 | transient — phone-written, decays on the pet |
| `sad` | 4 | pet-local, never on the wire |
| `sick` | 5 | pet-local, never on the wire |
| `dead` | 6 | pet-local, never on the wire |
| `asleep` | — | not a mood: an overlay applied over whichever mood is current |

**The app only draws five of them**, and that is deliberate rather than an
oversight. `sleepy` and `surprised` are written by the phone, decay on the
device and are never reported back, so the phone cannot know when one is
showing; drawing a guess would break §5.0 rule 2. They are still defined here
because the *pet* wears them, and a set that skipped them would leave two faces
unstyled.

## Why `sick` is a spiral

Because four of the eight states were the same silhouette.

`sick` was two thin lids. So is `sleepy`, so is `asleep`, and so is `dead` —
four faces that all read as *two short horizontal lines* from more than a metre
away, which is the distance this thing is designed to be looked at from. The
mouths differ, and at that range the mouth is the part you read second. So **the
one state the user is supposed to act on looked like the three they cannot do
anything about**, and the fix could not be a bigger number: the lids were
already as different as lids get.

A spiral is not a variation on a lid. It is a shape no other face wears at any
size, it survives the 48dp notification badge, and the rotation carries the
meaning even when the shape is too small to resolve.

**X eyes were the other candidate and were rejected**: an X-eyed pet is the
universal signal for a dead one, and this pet has a real `dead` state whose
whole message is that nothing can be done. Borrowing that signal for the state
that most needs somebody to act would have been a clearer face saying the wrong
thing.

## The rules every set must satisfy

A set chooses how the pet **looks**. It does not get to choose what a state
**means** — these faces decide whether somebody feeds a pet, so a "happy" face
that does not read as happy is a broken signal, not a style.

`tools/gen-faces.py` refuses a set that breaks any of these, with the reason:

| | rule | why |
|---|---|---|
| mouth | `happy` curves up | otherwise it is not a happy face. `classic` shipped with a flat bar for exactly this reason — structurally fine, and it never smiled |
| | `sad` curves down | this is the face that has to make somebody go and feed the thing |
| | `neutral` is level | it is the baseline every other state is read against |
| | `dead` has no mouth | that is the whole message |
| eyes | `surprised` wider than `neutral` | |
| | `sleepy` narrower than `neutral` | |
| | `asleep` no wider than `sleepy` | it is a pet that has actually gone to sleep |
| | `sad` lidded | droopy, not merely a different mouth |
| distinctness | `sad` and `sick` differ in **both** eyes and mouth | they demand different actions — feed me versus put the phone down — and must not be confusable across a room |
| spiral | `dead` may not be one | it does not move, and a spiral that has stopped is an ornament rather than a symptom |
| | a spiral must have a `spinMs` | the rotation IS the state; a still one is a pattern the pet happens to be wearing |
| | nothing but a spiral may have a `spinMs` | a number that does nothing is worse than no number |
| | `spiralTurns` is 2..4, and the turns must clear the stroke by half again | wound too tight it fills into a disc, which is a blank stare — less than the two lids it replaced |
| motion | everything except `dead` breathes | a still living pet reads as broken hardware (DESIGN.md §6) |
| | everything except `dead` and `asleep` blinks | only a sleeping pet holds its eyes shut |
| | `dead` has `"motion": null` | the absence of the blink and the breathe is what says it is not alive |
| | `sick` breathes faster **and** shallower than `happy` | shallow and fast is what reads as ill before you have looked at the mouth |

**Circles are exempt from the curve rules.** A mouth as thick as it is wide is
an "o", not a line, so asking whether it curves up or down is meaningless —
that is how `surprised` and `sick` are drawn.

The app-visible subset of these is also checked by `PetFaceTest` against the
compiled Kotlin, so they hold even if someone edits the generated file by hand.
The generator covers all eight states; the test can only see the five the phone
draws.

## What a set may change

Geometry, colour and motion. The primitives are two eyes and one stroke for the
mouth, on a filled panel — that is what the firmware, the Compose panel, the
notification and the design project's prototype can all draw, so a set stays
faithful across all four.

**An eye takes one of three forms**, and which one is chosen by the numbers
rather than declared, except for the last:

| | |
|---|---|
| a rounded rectangle | the default. `eyeRadius` rounds it, `-1` is a capsule |
| a stroked arc | whenever `eyeBulge` is non-zero. The height becomes the line weight — this is what draws the classic `^ ^` |
| a spiral | `"eyeShape": "spiral"`. `eyeW` is the outer **diameter** and `eyeH` the stroke, the same reinterpretation the arc makes, so it needs no new size fields |

**The mouth is a stroke along an arc.** `mouthW` is its chord, `mouthThickness`
the stroke, and `mouthBulge` the sagitta: positive smiles, negative frowns, and
**zero is a straight bar**, which is exactly the capsule every face was before
curves existed. The generator derives the radius and both angles from those
three numbers and emits them to every renderer, so nothing downstream does trig
— four renderers each doing it would be four chances to curve differently.

A bulge deeper than half the chord is refused: past that you need a reflex arc,
which neither `lv_arc` nor a single SVG `A` command draws the way you expect.

`mouthCap` is `round` or `square`, per set with an optional per-state override —
that is how `pixel` keeps square ends everywhere but its round sick "o".

`eyeRadius` of `-1` means "as round as it goes" — `LV_RADIUS_CIRCLE` on the
device — and `0` gives square eyes.

**Motion is per state.** `motion` at the top of a set is the default; any state
may override part of it, and `"motion": null` means that state does not move at
all. There are three movements: the blink, the breathe, and — for spiral eyes
only — `spinMs`, one full revolution. Sizes are **device pixels** on the 448x368
screen, so they do not change
when something is scaled; `PetFace.SCREEN_WIDTH` converts them for other
surfaces.

## Adding a set

1. Copy `classic.json`, change `id`, `name` and the numbers.
2. `tools/gen-faces.py` — regenerates every surface and the preview.
3. `./gradlew testDebugUnitTest` — `FaceSyncTest` checks the generated Kotlin
   against the JSON, so a stale generator fails here rather than on the shelf.
4. Flash. A set is compiled in; switching between compiled sets is a runtime
   choice, adding one is not.
