# Personas — the pet's voice

A **persona** is everything the pet says, in one voice. One JSON file per
persona; `PetPersonas.kt` is generated from it.

```
design-system/personas/<id>.json   the voice — THIS IS THE SOURCE
        │
        └── tools/gen-personas.py
                └── android/…/pet/PetPersonas.kt
                        ├── the LLM system prompt
                        └── every line the pet says WITHOUT being asked
```

## Why a voice is a design artefact

The same reason a face is (`../faces/README.md`): it is what the thing is
*like*, not how it works. DESIGN.md §7.2 gives that to the design system. Before
this, the pet's canned lines were string literals scattered across the
conversation engine and its prompt was a constant called
`DEFAULT_PET_SYSTEM_PROMPT` — so "make the pet sound different" was a code
change in five files, and the prompt and the lines could disagree about who the
pet was without anything noticing.

**Do not hand-edit `PetPersonas.kt`.** The generator overwrites it.

## A persona is paired with a face set, and that pairing is the product

`design-system/faces/<id>.json` names its `persona`. Selecting a personality in
Settings → Your pet sets **both**, so the pet cannot look like one character and
speak as another. The pairing is many-to-one on purpose: `bloom` and `pixel` are
restyles of the pet rather than different characters, so all three of `classic`,
`bloom` and `pixel` speak in `classic`. `bmo` is a different character and has
its own.

`gen-faces.py` fails if a set names a persona that does not exist — a dangling
id would fall back to the default silently and look exactly like the pairing
not working.

## What is in a file

| key | |
|---|---|
| `id` | lowercase, matches the filename |
| `name` | what the picker shows |
| `description` | for a reader of this directory, not for the pet |
| `systemPrompt` | lines, joined. The character, and nothing else — see below |
| `lines.fed` | said when the pet is fed |
| `lines.played` | said when it is played with |
| `lines.calling` | said when it is calling for attention |
| `lines.fullFed` | **said when it declines a feed it does not need** |
| `lines.fullPlayed` | said when it declines play it does not need |
| `announce.one` / `.manyFromOne` / `.many` | notification arrivals |

The `announce` strings take `{app}` and `{count}`. **`many` may not take
`{app}`**, and that is a rule rather than an oversight: the pet speaks aloud in
a room, so several apps are counted and not named. `manyFromOne` names the one
app because there is only one to give away.

## What a persona does not get to change

`PET_RULES` in `gen-personas.py` is appended to every prompt, and a persona
containing any of the `RESERVED` phrases is refused — restating a rule either
doubles it or, once the two are edited apart, contradicts it.

1. **The reply-length cap.** ~150 characters. The pet's screen fits about six
   short lines and the wire caps a message at 240 bytes.
2. **The no-history rule.** No conversation history is sent
   (`DEFAULT_HISTORY_MESSAGES = 0`, the main latency lever), so without being
   told, the model invents callbacks to things that were never said.
3. **Staying in character** — a digital pet, not a generic assistant.
4. Caring about the owner's wellbeing. (CLAUDE.md names the first three; this
   one rides along in the same block.)

**The first version of this generator dropped all of them**, by keeping only the
personality sentences. The result rambled *and* invented callbacks — slower and
less sensible from one omission.

## The rules the generator enforces, and why each exists

`tools/gen-personas.py` refuses input that breaks one and says which:

- **No questions in a canned line.** Answering one means holding the talk
  button, and a pet that asks things it cannot hear the reply to teaches you to
  ignore it.
- **At least two lines per occasion, and no repeats.** One line makes the pet a
  doorbell rather than a creature.
- **No naming several apps aloud.** See `announce` above.
- **Nothing may restate a `PET_RULES` line.** A persona that also says "keep it
  short" is a second, weaker copy of a rule that is already appended — and when
  the two are edited apart, the model gets a contradiction.
- **Tell it HOW to answer, never what not to say.** Naming a feeling puts the
  word within reach and a negation does not take it back: a prompt ending "without
  stating it as a status" produced *"I do not have jokes now, friend. I am just
  waiting."* Write a manner, not a prohibition. This one cost a flash to find.

## Adding one

1. Write `design-system/personas/<id>.json`.
2. Point a face set's `persona` at it, or add a set — a persona with no set
   pointing at it is unreachable, because personality is one choice.
3. `tools/gen-personas.py`, then `tools/gen-faces.py`.
4. Push both back to the design project, then `tools/design-remote.py --record`.

## The one thing here the code still owns

Same exception as faces, from CLAUDE.md: a persona says *how* the pet talks. It
does not get to say *when*. Quiet hours, the announcement toggle and whether the
pet is calling at all are `pet_sim.c` and the app's settings, not a voice.
