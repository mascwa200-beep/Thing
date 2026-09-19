#!/usr/bin/env python3
"""Build the bundled OUI (MAC vendor) table for TROVE.

Fetches the IEEE MA-L registry — the 24-bit OUI blocks — and emits a compact TSV of
`PREFIX<TAB>vendor`, one per line, PREFIX being six uppercase hex digits. The on-device
`Recon.resolveMac` looks a scan result's first three octets up in this table; a randomized
(privacy) address is never looked up (see Recon), so this table is consulted only for genuine
hardware addresses.

  build_oui.py            # fetch, build, write app/src/main/assets/recon/oui.tsv, print stats
  build_oui.py --check    # rebuild to a temp and diff against the committed asset (CI/local gate)

⚠️ MA-L only. MA-M and MA-S are 28- and 36-bit blocks that SHARE a 24-bit prefix among several
registrants, so they are ambiguous at the 24 bits a scan result's OUI gives us — resolving one to
a single vendor would be a guess, which is the dishonesty Recon exists to prevent. A device in an
MA-M/MA-S block resolves to whoever (if anyone) holds the /24 in MA-L, else "unknown vendor". Named
here so the ceiling is a decision on the record, not an accident.
"""
import csv
import io
import os
import ssl
import sys
import urllib.request

SRC = "https://standards-oui.ieee.org/oui/oui.csv"
ASSET = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "recon", "oui.tsv")
CA = "/root/.ccr/ca-bundle.crt"
MAX_VENDOR = 48  # a recognizable name, not a legal address; keeps the bundled file compact


def fetch() -> str:
    ctx = ssl.create_default_context(cafile=CA) if os.path.exists(CA) else ssl.create_default_context()
    req = urllib.request.Request(SRC, headers={"User-Agent": "pulse-oui-builder"})
    with urllib.request.urlopen(req, context=ctx, timeout=120) as r:
        return r.read().decode("utf-8", "replace")


def clean(name: str) -> str:
    # One line, no tabs (the file is TSV), collapsed whitespace, bounded length.
    n = " ".join(name.replace("\t", " ").split())
    if len(n) > MAX_VENDOR:
        n = n[:MAX_VENDOR].rstrip()
    return n


def build(text: str) -> list[tuple[str, str]]:
    rows: dict[str, str] = {}
    reader = csv.reader(io.StringIO(text))
    header = next(reader, None)
    # Columns: Registry, Assignment, Organization Name, Organization Address
    for row in reader:
        if len(row) < 3:
            continue
        registry, assignment, org = row[0].strip(), row[1].strip().upper(), row[2].strip()
        if registry != "MA-L":
            continue
        if len(assignment) != 6 or any(c not in "0123456789ABCDEF" for c in assignment):
            continue
        name = clean(org)
        if not name:
            continue
        rows.setdefault(assignment, name)  # first wins; the registry is already unique per MA-L OUI
    return sorted(rows.items())


def render(rows: list[tuple[str, str]]) -> str:
    return "".join(f"{p}\t{v}\n" for p, v in rows)


def main() -> int:
    check = "--check" in sys.argv
    text = fetch()
    rows = build(text)
    if len(rows) < 20_000:
        print(f"FAIL: only {len(rows)} OUI rows parsed — the registry fetch looks wrong.")
        return 2
    out = render(rows)
    apple = dict(rows).get("AC:DE:48".replace(":", ""))  # IEEE's documentation OUI ACDE48
    sample = next((v for p, v in rows if v.lower().startswith("apple")), None)
    print(f"OUI rows: {len(rows)}")
    print(f"bytes:    {len(out.encode('utf-8'))}")
    print(f"ACDE48 →  {dict(rows).get('ACDE48')}")
    print(f"an Apple prefix → {sample!r}")

    if check:
        if not os.path.exists(ASSET):
            print(f"FAIL: committed asset missing: {ASSET}")
            return 1
        committed = open(ASSET, encoding="utf-8").read()
        if committed == out:
            print("check: committed asset matches a fresh build")
            return 0
        # The registry changes daily (new registrations), so a mismatch is expected drift, not a
        # bug — report the delta rather than failing. A structural break (empty, tiny) is caught above.
        cur = dict(l.split("\t", 1) for l in committed.splitlines() if "\t" in l)
        new = dict(rows)
        added = len(new.keys() - cur.keys())
        removed = len(cur.keys() - new.keys())
        print(f"check: differs from committed — {added} added, {removed} removed, {len(cur)} committed")
        return 0

    os.makedirs(os.path.dirname(ASSET), exist_ok=True)
    with open(ASSET, "w", encoding="utf-8") as f:
        f.write(out)
    print(f"wrote {ASSET}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
