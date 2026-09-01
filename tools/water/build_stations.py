#!/usr/bin/env python3
"""Build the bundled NOAA CO-OPS station list.

    tools/water/build_stations.py [--out app/src/main/assets/water/stations.tsv] [--check]

`--check` rebuilds and compares against what is committed without writing, which is what makes the
asset reviewable: an opaque 188 KB table nobody can regenerate is worse than one that can be
reproduced from the two endpoints that produced it.

## What the list is for

The widget's water block reads the gauge nearest you and asks NOAA for the product that gauge
actually publishes. ⚠️ **There are no tide-prediction stations in Michigan** — the Great Lakes are
not tidal — so a tides-only list would leave the block permanently empty for anyone living on them.
NOAA publishes water LEVELS there instead, which is why both products are carried and why each row
records which one it is.

## ⚠️ Why twelve water-level stations are deliberately dropped

The app requests IGLD for every LEVEL station, which is the datum of the Great Lakes–St. Lawrence
system and meaningless anywhere else. NOAA also publishes levels at twelve stations outside it:
Mississippi river stages in Louisiana, Laguna Madre gauges in Texas, and six in Puerto Rico. Asking
those for IGLD gets nothing back.

Dropping them costs no coverage, and that was measured rather than assumed: **every one of the twelve
has a tide station within 37 km, and four of the six Puerto Rico ones are the SAME station publishing
both products, at 0.0 km.** A tide prediction is the better reading in all twelve places, and the
core's `nearest` finds it comfortably inside its 90 km tide reach.

Filtering on the state code is coarse but it is the only signal the metadata carries, so the
`WaterStationsAssetTest` guard is expressed the same way — one rule, stated twice, and a rebuild that
readmitted one would fail the build rather than fail on the phone.
"""
import argparse
import json
import sys
import urllib.request
from pathlib import Path

MDAPI = "https://api.tidesandcurrents.noaa.gov/mdapi/prod/webapi/stations.json"

#: `type=` values, and the single letter each becomes in the bundled table.
PRODUCTS = [("tidepredictions", "T"), ("waterlevels", "W")]

#: State codes where NOAA measures water levels that are NOT on the International Great Lakes Datum.
OUTSIDE_IGLD = {"PR", "TX", "LA"}


def fetch(kind: str) -> list[dict]:
    url = f"{MDAPI}?type={kind}"
    with urllib.request.urlopen(url, timeout=120) as r:
        body = r.read().decode("utf-8")
    rows = json.loads(body).get("stations") or []
    assert rows, f"{url} returned no stations"
    return rows


def build() -> str:
    seen: dict[str, str] = {}
    order: list[str] = []
    for kind, letter in PRODUCTS:
        for s in fetch(kind):
            sid = str(s.get("id") or "").strip()
            name = str(s.get("name") or "").strip()
            state = str(s.get("state") or "").strip()
            lat, lon = s.get("lat"), s.get("lng")
            if not sid or not name or lat is None or lon is None:
                continue
            if letter == "W" and state in OUTSIDE_IGLD:
                continue
            # ⚠️ Tide predictions win a tie. Four Puerto Rico gauges and a handful elsewhere publish
            # BOTH products under one id, and a prediction answers "when is high water" where a bare
            # level only answers "how deep is it now".
            if sid in seen:
                continue
            # ⚠️ Not blindly `f"{name}, {state}"`. NOAA already ends some names with their own state
            # ("Menemsha Harbor, MA"), and appending unconditionally gives "Menemsha Harbor, MA, MA";
            # skipping the append when the name merely contains a comma loses it entirely from the
            # many that do not end in one ("ALBANY" would stay stateless while "Sandwich Marina,
            # Cape Cod Canal, east entrance" got no state either). Append only when it is not
            # already the last field.
            label = name if (state and name.endswith(f", {state}")) or not state else f"{name}, {state}"
            # ⚠️ A tab or a newline inside a name would silently give the row a sixth column, and
            # the core's `parse` reads fields by index — it would take the first five and mangle the
            # name without complaining. `WaterStationsAssetTest` catches it, but refusing to write
            # the file is better than writing one a test then rejects.
            assert "\t" not in label and "\n" not in label, f"station {sid} name breaks the table: {label!r}"
            seen[sid] = f"{sid}\t{float(lat):.4f}\t{float(lon):.4f}\t{letter}\t{label}"
            order.append(sid)
    # Sorted by id so a rebuild is byte-comparable against what is committed; the order carries no
    # meaning to the reader, which walks the whole list.
    return "\n".join(seen[k] for k in sorted(order)) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="app/src/main/assets/water/stations.tsv")
    ap.add_argument("--check", action="store_true", help="compare against the committed file, write nothing")
    a = ap.parse_args()

    built = build()
    rows = built.rstrip("\n").split("\n")
    tide = sum(1 for r in rows if r.split("\t")[3] == "T")
    level = len(rows) - tide
    print(f"{len(rows)} stations — {tide} tide, {level} water level, {len(built)} bytes")
    assert tide > 3000, f"only {tide} tide stations — the fetch looks truncated"
    assert level >= 40, f"only {level} level stations — the Great Lakes would go dark"

    out = Path(a.out)
    if a.check:
        if not out.is_file():
            print(f"MISSING: {out}")
            return 1
        same = out.read_text() == built
        print("identical — the committed list is what these endpoints imply" if same
              else "DIFFERENT from the committed list")
        return 0 if same else 1
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(built)
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
