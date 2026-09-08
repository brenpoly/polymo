# design-system/ — the vendored half of the round trip

**This is a checked-in copy of the Claude Design project's token files, not a
design system of its own.** The system lives in a Claude Design project,
private to its owner, and is read through the DesignSync MCP;
DESIGN.md §7.1 has how to read it and §7.2 says it outranks DESIGN.md.

```
tokens/colors.css        both schemes, 36 M3 roles + 8 PetColors semantics
tokens/spacing.css       the spacing ladder + load-bearing geometry
tokens/shape.css         radii
tokens/typography.css    15 styles: weight, size, line height
components.txt           the 19-component roster, and the three asymmetries
strings.txt              the ranked user-facing strings — copy is design too
faces/*.json             what the pet LOOKS like — four sets, eight states each
personas/*.json          what it SAYS — the LLM prompt and every unprompted line
faces/preview/*.html     GENERATED cards, one per set
components/pet/faceSets.jsx  GENERATED geometry for the design project's prototype
guidelines/brand-pet-face.html   GENERATED — the Brand card for the face
guidelines/brand-pet-voice.html  GENERATED — the Brand card for the voice
guidelines/colors-pet.html       GENERATED — five colour roles per set, measured
guidelines/motion.html           GENERATED — the blink and breathe, per state
```

**Every one of these is a copy.** `strings.txt` and `components.txt` were
written on this side until 2026-08-09, which made the two tests that read them
weaker than they looked: they could catch the code drifting from a manifest,
never the manifest drifting from the design. That is not hypothetical — a string
was edited in the design gallery and nothing failed, because the manifest and
the code still agreed with each other. Both now live in the design project.
`VendoredCopyTest` fails if a new file appears here without an upstream — only
this README is genuinely ours, because it is about the copy rather than copied.

**Nine of them are generated as well as vendored**, which is not a
contradiction: `tools/gen-faces.py` writes them from `faces/*.json` and
`personas/*.json`, and they are pushed like everything else.
`components/pet/faceSets.jsx` is here because it fell *out* of that generator once and spent a schema change in the design
project still carrying a GENERATED header — three face sets and filled mouths
against the device's four and stroked arcs. Recording it is what lets
`design-remote.py --check` notice next time.

**The four `guidelines/` cards here became generated on 2026-08-11, and the
reason is the point of this whole directory.** They were hand-drawn, and by then
`brand-pet-face` still showed a PORTRAIT panel with six flat-mouthed faces,
`motion` asserted one 5400 ms blink for everything, and `colors-debt` described
two placeholder `#define`s that had been retired — and whose premise, that the
pet has two colours, had stopped being true when colour went per-set. Nothing
could have caught any of it. A specimen drawn by hand records what somebody
believed the day they drew it.

**What is NOT here, and therefore still not checked by anything.** The design
project also holds two `.dc.html` documents, `DESIGN-SYSTEM.md`, the remaining
hand-written `guidelines/` cards, and the rest of `components/` and `ui_kits/`.
Those are prose and hand-drawn specimens; no test can read them and they are not
vendored. They are exactly what went stale while `design-sync.sh` reported
green — see "Keeping it current".

## Why a copy exists at all

`TokenSyncTest`, `ComponentRosterTest` and `StringSyncTest` compare these files
against the Kotlin and fail the build on a mismatch. They could have called the design project
directly. They do not, for three reasons in order of weight:

1. **The build is `--offline`.** A test that made a network call would fail for
   the wrong reason on a train.
2. **A diff needs a base.** Comparing against a live remote tells you the values
   differ. Comparing against a pinned copy tells you *someone changed
   something*, which is the sentence you can act on.
3. **It puts the pull in the workflow rather than in the test.** Refreshing this
   directory is a deliberate act with a commit attached, so "the design moved"
   is visible in the history instead of arriving silently.

## Keeping it current

`tools/design-sync.sh` is the front door — it runs the checks and says which way
the work goes. The fetch itself needs Claude, because the project sits behind a
claude.ai login that a shell script has no way through:

> refresh `design-system/tokens/` from the design project
>
> push `design-system/tokens/` back to the design project

Then commit whatever changed, and stamp the push with
`tools/design-remote.py --record` — forgetting it reports a false "behind",
which is the safe direction to be wrong in.

**And when the shape of the product moves rather than a token, ask Claude to
read the prose too.** The unvendored half — the `.dc.html` documents,
`DESIGN-SYSTEM.md`, `guidelines/` — went a whole face-schema change out of date
while every automated check here stayed green, because none of them can read a
sentence. That is a standing property of this split, not a bug that was fixed.

**Gradle has been told these files are test inputs** (`app/build.gradle.kts`).
Without that it skipped `testDebugUnitTest` as UP-TO-DATE whenever *only* these
changed — which is the single case the sync test exists for. Seven mutations to
the CSS all "passed" before that was found. If you move this directory, move the
`inputs.dir` with it.

## What used to be here

Six hand-written HTML specimens from 2026-08-02 (`foundations/`, `pet/`,
`phone/`), superseded by the Claude Design project a week later and untouched
after it. They were removed on 2026-08-09 rather than left: a third thing
claiming to describe the design, which nobody updated, is how a precedence rule
gets applied to the wrong file.
