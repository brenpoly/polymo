#!/usr/bin/env python3
"""
Has anything changed since the design project was last pushed?

    tools/design-remote.py --check     exit 1 if the project is behind
    tools/design-remote.py --record    stamp the manifest after a push

WHAT THIS CANNOT DO, stated first because a check that overstates itself is
worse than none — this repo has been bitten by that twice.

**It does not read the design project.** That lives behind a claude.ai login and
is reachable only through the DesignSync MCP, which Claude has and a shell does
not. So this compares what is on disk NOW against a fingerprint of what was on
disk WHEN WE LAST PUSHED. It answers "is the project behind us", and it cannot
answer "is the project ahead of us" — somebody editing a set in Claude Design
directly is invisible here, and pulling is still a thing you ask Claude for.

WHY IT EXISTS ANYWAY. design-sync.sh compares the CODE against the VENDORED
copy, and both stay current while the remote silently falls behind — which is
exactly what happened over four rounds of face-schema work in one session. The
script reported a clean IN SYNC the whole time, because the remote is the one
copy it could not see. Nine files were stale by the end, including a whole face
set the project had never heard of.

This does not fix that. It makes it visible, which is the most a shell can do.
"""

import argparse
import datetime
import hashlib
import json
import os
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DS = ROOT / "design-system"
MANIFEST = DS / ".design-push.json"

# Ours, with no upstream — the same distinction VendoredCopyTest draws. A file
# here is not "unpushed", it is not the project's business.
LOCAL_ONLY = {"README.md", ".design-push.json"}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def present():
    return {
        str(p.relative_to(DS)): p
        for p in sorted(DS.rglob("*"))
        if p.is_file() and str(p.relative_to(DS)) not in LOCAL_ONLY
    }


def record(project):
    files = present()
    MANIFEST.write_text(json.dumps({
        "project": project,
        "pushedAt": datetime.datetime.now(datetime.timezone.utc)
                            .replace(microsecond=0).isoformat(),
        "note": "sha256 of each file AS PUSHED. Written by tools/design-remote.py "
                "--record, immediately after a DesignSync push. It records what we "
                "sent, not what the project now holds.",
        "localOnly": sorted(LOCAL_ONLY),
        "pushed": {rel: digest(p) for rel, p in files.items()},
    }, indent=2) + "\n")
    print(f"design-remote: stamped {len(files)} files as pushed")
    return 0


def check():
    if not MANIFEST.exists():
        print("design-remote: NEVER PUSHED — no manifest.")
        print("  Ask Claude: \"push design-system/ to the design project\"")
        return 1

    m = json.loads(MANIFEST.read_text())
    was = m.get("pushed", {})
    now = present()

    changed = sorted(r for r, p in now.items() if r in was and digest(p) != was[r])
    added = sorted(r for r in now if r not in was)
    removed = sorted(r for r in was if r not in now)

    if not (changed or added or removed):
        print(f"design-remote: nothing has changed since the push on "
              f"{m.get('pushedAt', '?')}")
        return 0

    print("design-remote: THE DESIGN PROJECT IS BEHIND.")
    print(f"  Last pushed {m.get('pushedAt', '?')}. Since then:")
    for r in added:
        print(f"    NEW      {r}")
    for r in changed:
        print(f"    CHANGED  {r}")
    for r in removed:
        print(f"    DELETED  {r}  (still in the project)")
    print()
    print('  Ask Claude: "push design-system/ to the design project"')
    print("  Then: tools/design-remote.py --record")
    return 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--record", action="store_true")
    # The design project is private to its owner; there is no useful default.
    ap.add_argument("--project", default=os.environ.get("POLYMO_DESIGN_PROJECT"))
    a = ap.parse_args()
    if a.record:
        return record(a.project)
    if a.check:
        return check()
    ap.error("--check or --record")


if __name__ == "__main__":
    sys.exit(main())
