#!/usr/bin/env bash
#
# The round trip, in one command.
#
# DESIGN.md §7.2 makes the Claude Design system the source of truth, and that
# creates an obligation rather than a convenience: an authority that lags the
# code stops deserving the rank. This checks whether the two halves agree and
# tells you which way the work goes.
#
# WHAT IT DOES NOT DO: fetch. The design project lives behind a claude.ai login
# and is reachable only through the DesignSync MCP, which is available to Claude
# and not to a shell. So refreshing the vendored copy is a step you ask for —
# see PULLING below — and this script checks whatever is on disk right now.
#
# That split is deliberate rather than a limitation being dressed up. Vendoring
# the tokens means the check runs offline, and it means a design change arrives
# as a reviewable commit instead of appearing under you mid-build.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Both are environment-overridable: the design project is private to its owner,
# and the JDK path is wherever Android Studio put JDK 21 on this machine.
JDK="${POLYMO_JDK:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
PROJECT="${POLYMO_DESIGN_PROJECT:-}"

cat <<'BANNER'
── design ⇄ code ─────────────────────────────────────────────────────────────
BANNER

if [ ! -d "$ROOT/design-system/tokens" ]; then
  echo "design-system/tokens/ is missing. It is the vendored copy of the design"
  echo "project's token files; without it the sync tests cannot run at all."
  exit 1
fi

echo "vendored from project $PROJECT:"
for f in "$ROOT"/design-system/tokens/*.css "$ROOT"/design-system/components.txt "$ROOT"/design-system/strings.txt; do
  when="$(git -C "$ROOT" log -1 --format=%ad --date=short -- "$f" 2>/dev/null)"
  printf '  %-28s %s\n' "$(basename "$f")" "${when:-not yet committed}"
done
echo

# The face sets are generated, so a stale generator is its own kind of drift:
# the JSON says one thing and the surfaces still draw the last one. There are
# nine outputs now — four renderers, the four per-set cards and four guidelines
# cards — and the JVM tests can see only the Kotlin, so --check covers the rest.
if ! python3 "$ROOT/tools/gen-personas.py" --check; then
  echo
  echo "The pet's VOICE is out of date — design-system/personas/ has moved and"
  echo "the generated Kotlin has not."
  echo "Run: tools/gen-personas.py"
  exit 1
fi

if ! python3 "$ROOT/tools/gen-faces.py" --check; then
  echo
  echo "The face sets have changed but the generated code has not."
  echo "Run: tools/gen-faces.py"
  exit 1
fi
echo

# Is the PROJECT behind us? design-sync compares the code against the vendored
# copy, and both stay current while the remote quietly falls behind — which is
# exactly what happened across four rounds of face-schema work in one session,
# with this script reporting IN SYNC throughout. It could not see the one copy
# that was stale.
REMOTE_OK=1
if ! python3 "$ROOT/tools/design-remote.py" --check; then
  REMOTE_OK=0
fi
echo

# The test classes that compare the two sides. Everything else in the
# suite is about the app's own logic and is not part of this question.
if JAVA_HOME="$JDK" "$ROOT/android/gradlew" -p "$ROOT/android" testDebugUnitTest --offline \
     --tests '*TokenSyncTest*' \
     --tests '*ComponentRosterTest*' \
     --tests '*ContrastTest*' \
     --tests '*StringSyncTest*' \
     --tests '*TruncationTest*' \
     --tests '*VendoredCopyTest*' \
     --tests '*FaceSyncTest*' \
     > /tmp/design-sync.log 2>&1; then
  if [ "$REMOTE_OK" = "1" ]; then
    echo "IN SYNC — every token, colour role, type style, ranked string, face"
    echo "set and the component roster agree with design-system/, and nothing"
    echo "has changed since the design project was last pushed."
  else
    echo "THE CODE AND THE VENDORED COPY AGREE — but the design project is"
    echo "BEHIND, listed above. The round trip is not finished until it has"
    echo "these files: an authority that lags the code stops deserving the rank."
  fi
  echo
  echo "NOT covered: component internals. These check tokens, names and copy —"
  echo "a .jsx and a .kt cannot be diffed, so a layout that drifts inside a"
  echo "component still needs an eye. See DESIGN.md 7.7 item 4."
  echo
  echo "If you have just changed the design project, the vendored copy is stale"
  echo "rather than the code being right. Pull first (see below), then re-run."
  echo
  echo "AND THE REMOTE IS STILL ONLY HALF-CHECKED: --check compares today's"
  echo "files against a fingerprint of what we last SENT. Someone editing a set"
  echo "in Claude Design directly is invisible to it. Only a pull can see that."
  [ "$REMOTE_OK" = "1" ] || exit 1
  exit 0
fi

echo "OUT OF SYNC. What follows is the design system's value against the code's:"
echo
grep -E "design says|absent from|does not have|does not mention|roster|→" /tmp/design-sync.log \
  | grep -v "^\s*at " | sed 's/^/  /' | sort -u | head -40
echo
cat <<'GUIDE'
WHICH WAY DOES THE WORK GO?

  You edited the DESIGN PROJECT
    → pull the token files down (below), then change the Kotlin to match.
      The design system is the source of truth. DESIGN.md §7.2.

  You edited the KOTLIN
    → the design project is now behind. Push the changed token files back to
      it, then re-vendor so this check passes. A palette or token change is not
      finished until the design project has it.

  The design system is quoting the code back, and the quote is old
    → this is the one case where the code is not what changes. Regenerate the
      design project instead. It happened on 2026-08-09, within an hour of the
      precedence rule landing. §7.2 has the shape of it.

PULLING AND PUSHING (both need Claude, both use the DesignSync MCP)

  pull:  "refresh design-system/ from the design project"
  push:  "push design-system/ back to the design project"

  Everything under design-system/ is a copy except README.md and the push
  manifest: the four token files, strings.txt, components.txt, faces/ — four
  face sets, their generated cards and their README — personas/, which is the
  pet's voice, components/pet/faceSets.jsx, the generated geometry the design
  project's prototype draws from, and four generated guidelines/ cards (the
  face, the voice, the pet's colours and its motion). VendoredCopyTest fails if
  a new one appears without an upstream.

  NOT vendored, and therefore NOT covered by any check here: the two .dc.html
  documents, DESIGN-SYSTEM.md, the HAND-WRITTEN guidelines/ cards, and the rest
  of components/ and ui_kits/. They are prose and hand-drawn specimens and no
  test can read them. They went stale for a whole schema change once — the face
  card was still drawing a portrait panel — which is why the four cards that
  are about the pet itself are generated now. Ask Claude to read the rest
  whenever the SHAPE of the product moves, not only when a token does.

  After a push, stamp it:  tools/design-remote.py --record
  That is what makes the next run able to say the project is behind. Forgetting
  it reports a false "behind", which is the safe direction to be wrong in.

  Then commit the vendored files, so that "the design moved" is a line in the
  history rather than something that happened quietly one morning.

Full log: /tmp/design-sync.log
GUIDE
exit 1
