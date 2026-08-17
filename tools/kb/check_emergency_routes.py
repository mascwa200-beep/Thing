#!/usr/bin/env python3
"""Local twin of the emergency-route guard in the app-module tests.

⚠️ Every (guideId, section) pair in EmergencyTriage is a route someone follows in an emergency. A
guide section renamed for any reason — better wording, better search — silently sends them to a page
that no longer exists, and the content still validates perfectly.

That is not hypothetical: renaming First Aid's "Burns" heading so that "how do I treat a burn" would
stop returning a cellular-respiration guide broke the burn route in the same commit. CI caught it,
which is what CI is for, but the round trip is slow. Run this before committing any change to a
guide heading.

    python3 tools/kb/check_emergency_routes.py

Exits non-zero and names the break. No dependencies.
"""
import glob
import json
import re
import sys

TRIAGE = "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/EmergencyTriage.kt"
SHARDS = "app/src/main/assets/survival/guides_*.json"


def main() -> int:
    src = open(TRIAGE, encoding="utf-8").read()
    routes = re.findall(r'guideId\s*=\s*"([^"]+)"\s*,\s*section\s*=\s*"([^"]+)"', src)
    if not routes:
        print("no routes parsed from EmergencyTriage.kt — has its shape changed?")
        return 1

    guides = {}
    for path in glob.glob(SHARDS):
        blob = json.load(open(path, encoding="utf-8"))
        entries = blob["guides"] if isinstance(blob, dict) else blob
        for g in entries:
            guides[g["id"]] = {s["heading"] for s in g["sections"]}

    broken = 0
    for gid, section in routes:
        if gid not in guides:
            print(f"BROKEN  guide does not exist: {gid}")
            broken += 1
        elif section not in guides[gid]:
            print(f"BROKEN  section renamed or gone: {gid} / {section!r}")
            print(f"        available: {sorted(guides[gid])}")
            broken += 1

    if broken:
        print(f"\n{broken} emergency route(s) broken — fix EmergencyTriage.kt or restore the heading")
        return 1
    print(f"CLEAN — all {len(routes)} emergency routes resolve")
    return 0


if __name__ == "__main__":
    sys.exit(main())
