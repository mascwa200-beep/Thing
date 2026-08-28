#!/usr/bin/env python3
"""Build the constellation asset: stick figures, asterisms and IAU boundaries.

    curl -o /tmp/index.json https://raw.githubusercontent.com/Stellarium/stellarium/master/skycultures/modern/index.json
    curl -o /tmp/hip2.dat.gz https://cdsarc.cds.unistra.fr/ftp/I/311/hip2.dat.gz
    python3 tools/sky/build_constellations.py /tmp/index.json /tmp/hip2.dat.gz \
        app/src/main/assets/sky/constellations.json

WHY THE LINE ENDPOINTS HAVE TO BE RESOLVED HERE
-----------------------------------------------
The skyculture names its figure vertices by Hipparcos number, and NEITHER bundled catalogue carries
a HIP identifier — the bright one drops it (see the NOTICE) and the packed Gaia one is positions
only. Resolving at build time means the app needs no cross-identification index at all, and the
whole set is 912 stars, which is under twenty kilobytes.

  ⚠️ `pmRA` in hip2.dat is mu_alpha*, i.e. it ALREADY INCLUDES cos(dec), so carrying a position
  forward divides by it. This was settled against Polaris rather than assumed: HIP 11767 sits at
  declination +89.26 (cos = 0.0128) and the field reads 44.48, which is the published mu_alpha*.
  Reading the convention the other way would put the pole stars out by a factor of seventy-eight.

  ⚠️ The epoch correction is load-bearing, not a formality. Hipparcos positions are J1991.25 and
  everything else this app draws is J2000 — 8.75 years. Measured over these 912: median 0.49
  arcseconds, worst 32.46 (HIP 71683, Alpha Centauri). At the narrowest field one screen pixel is
  0.83 arcseconds, so an uncorrected worst case is thirty-nine pixels — a line visibly missing the
  star it is drawn to.

WHY THE BOUNDARIES ARE B1875 EDGES AND NOT J2000 POLYGONS
--------------------------------------------------------
⚠️ **Do not reach for CDS VI/49 bound_20.dat for this.** It looks like the obvious source and it is
a POINT CLOUD SORTED BY RIGHT ASCENSION, not a list of polygons: 11,789 constellation-code changes
across 12,948 rows, with the first three rows belonging to Cepheus, Aries and Hydrus. Code that
walks it expecting contiguous per-constellation runs produces something plausible and wrong.

The skyculture's own `edges` block is the better source and it is only 781 rows:

    001:002 M+ 22:52:00 +34:30:00 22:52:00 +52:30:00 AND LAC

One edge, both endpoints in B1875, both adjacent constellations named, and M+/P+ saying whether the
segment runs along a meridian (constant RA) or a parallel (constant declination). Naming both sides
is what stops every border being drawn twice — the CDS file contains each segment once per
constellation, which its own documentation calls out as a hazard.

⚠️ And because a boundary is a STRAIGHT LINE OF RA OR DEC IN B1875, the eleven thousand J2000
"shape points" in the CDS file exist only to approximate what precession turns into a curve. Storing
the B1875 edge instead lets the renderer generate that curve at whatever epoch it is drawing for —
smaller than bundling the points and correct for any date, rather than only for J2000.

SERPENS
-------
⚠️ Serpens is the one constellation in two disjoint pieces, so boundary data splits it SER1/SER2
while the app's StarNames carries a single SER. Both halves are emitted under `Ser` and the count of
distinct figure codes stays 88.
"""

from __future__ import annotations

import gzip
import json
import math
import sys
from pathlib import Path

# Hipparcos positions are at this epoch; everything else the app draws is J2000.
HIP_EPOCH = 1991.25
TARGET_EPOCH = 2000.0
MAS_TO_DEG = 1.0 / 3_600_000.0

# Positions are written with this many decimal places. One ten-thousandth of a degree is 0.36
# arcseconds, which is under half a pixel at the narrowest field the projection allows.
POS_DP = 5

# The star this run re-derives to prove itself. Alpha Centauri A: the fastest mover in the figure
# set, so it fails loudest if the epoch correction is dropped. Published J2000 is 14h39m36.4951s,
# -60d50'02.308"; corrected this builder lands 0.07" away and uncorrected 32.46", so one arcsecond
# separates the two states by a factor of four hundred and sixty.
ANCHOR_HIP = 71683
ANCHOR_RA = 219.90206
ANCHOR_DEC = -60.83397
ANCHOR_TOLERANCE_ARCSEC = 1.0


def read_hipparcos(path: Path) -> dict[int, tuple[float, float]]:
    """HIP -> (raJ2000Deg, decJ2000Deg), proper motion carried from J1991.25."""
    opener = gzip.open if path.suffix == ".gz" else open
    out: dict[int, tuple[float, float]] = {}
    dt = TARGET_EPOCH - HIP_EPOCH
    with opener(path, "rt") as fh:
        for line in fh:
            if len(line) < 70:
                continue
            try:
                hip = int(line[0:6])
                ra = math.degrees(float(line[15:28]))
                dec = math.degrees(float(line[29:42]))
                pm_ra = float(line[51:59] or 0.0)   # mu_alpha*, mas/yr, includes cos(dec)
                pm_dec = float(line[60:68] or 0.0)  # mu_delta, mas/yr
            except ValueError:
                continue
            cos_dec = math.cos(math.radians(dec))
            if abs(cos_dec) > 1e-9:
                ra += pm_ra * MAS_TO_DEG * dt / cos_dec
            dec += pm_dec * MAS_TO_DEG * dt
            out[hip] = (ra % 360.0, max(-90.0, min(90.0, dec)))
    return out


def sexagesimal_ra(text: str) -> float:
    """`22:52:00` is RIGHT ASCENSION IN HOURS, so it is fifteen degrees to the hour."""
    h, m, s = (float(p) for p in text.split(":"))
    return (h + m / 60.0 + s / 3600.0) * 15.0


def sexagesimal_dec(text: str) -> float:
    """`+34:30:00` is degrees, and the sign belongs to the WHOLE value, not just the degrees."""
    sign = -1.0 if text.lstrip().startswith("-") else 1.0
    d, m, s = (float(p) for p in text.lstrip("+- ").split(":"))
    return sign * (d + m / 60.0 + s / 3600.0)


def code_of(entry_id: str) -> str:
    """`CON modern Aql` -> `Aql`."""
    return entry_id.rsplit(" ", 1)[-1]


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print(__doc__)
        return 2
    index_path, hip_path, out_path = (Path(p) for p in argv[1:])

    sky = json.loads(index_path.read_text(encoding="utf-8"))
    hip = read_hipparcos(hip_path)
    print(f"hipparcos rows: {len(hip)}")

    # ---- figures and asterisms -------------------------------------------------------------
    # ⚠️ Every polyline vertex becomes an INDEX into one shared star list, so a star used by four
    # lines is stored once. 912 distinct HIP across 914 figure points and every asterism.
    used: list[int] = []
    slot: dict[int, int] = {}
    missing: set[int] = set()

    def index_of(h: int) -> int | None:
        if h not in hip:
            missing.add(h)
            return None
        if h not in slot:
            slot[h] = len(used)
            used.append(h)
        return slot[h]

    def polylines(raw: list) -> list[list[int]]:
        out = []
        for line in raw:
            got = [index_of(h) for h in line if isinstance(h, int)]
            got = [g for g in got if g is not None]
            if len(got) >= 2:
                out.append(got)
        return out

    figures = []
    for c in sky["constellations"]:
        lines = polylines(c.get("lines", []))
        name = (c.get("common_name") or {}).get("english") or code_of(c["id"])
        figures.append({"code": code_of(c["id"]), "name": name, "lines": lines})

    asterisms = []
    helpers = 0
    for a in sky.get("asterisms", []):
        # ⚠️ A ray helper is scaffolding for drawing pointer rays, not an asterism anybody names.
        # Kept out of the asset rather than shipped and filtered at draw time.
        if a.get("is_ray_helper"):
            helpers += 1
            continue
        lines = polylines(a.get("lines", []))
        if not lines:
            continue
        name = (a.get("common_name") or {}).get("english") or code_of(a["id"])
        asterisms.append({"code": code_of(a["id"]), "name": name, "lines": lines})

    stars = []
    for h in used:
        ra, dec = hip[h]
        stars.append([round(ra, POS_DP), round(dec, POS_DP)])

    # ---- boundaries ------------------------------------------------------------------------
    # ⚠️ EIGHT fields, counted rather than guessed. The first cut of this required ten and therefore
    # skipped every row — and produced a perfectly valid asset with no boundaries in it, because the
    # guards below had nothing that noticed an empty result. Both faults are fixed: the count is
    # measured (all 781 rows split to exactly 8) and `emitted == source rows` is now asserted.
    raw_edges = sky.get("edges", [])
    edges = []
    for row in raw_edges:
        parts = row.split()
        if len(parts) != 8:
            continue
        kind = parts[1][0]                       # 'M' meridian, 'P' parallel, in B1875
        ra1 = sexagesimal_ra(parts[2]); dec1 = sexagesimal_dec(parts[3])
        ra2 = sexagesimal_ra(parts[4]); dec2 = sexagesimal_dec(parts[5])
        a, b = parts[6], parts[7]
        edges.append([kind, round(ra1, POS_DP), round(dec1, POS_DP),
                      round(ra2, POS_DP), round(dec2, POS_DP), a, b])

    asset = {
        "epoch": "J2000",
        "boundaryEpoch": sky.get("edges_epoch", "B1875"),
        "stars": stars,
        "figures": figures,
        "asterisms": asterisms,
        "boundaries": edges,
    }

    # ---- guards ----------------------------------------------------------------------------
    # ⚠️ Every one of these has a silent failure mode: a wrong column offset, a sexagesimal field
    # read as degrees when it is hours, an unresolved HIP quietly dropped. Each produces a file that
    # parses perfectly and describes the wrong sky.
    problems = []
    # ⚠️ NOTHING-WAS-PRODUCED comes first, because it is the failure the first version of this
    # script actually had and the one every other guard here is blind to. A parser that drops every
    # row emits a file that is valid, small, and silently missing a whole feature.
    if len(edges) != len(raw_edges):
        problems.append(f"parsed {len(edges)} of {len(raw_edges)} edge rows — the rest were dropped")
    if not edges:
        problems.append("no boundaries were parsed at all")
    if not stars:
        problems.append("no figure stars were resolved at all")
    if missing:
        problems.append(f"{len(missing)} HIP could not be resolved: {sorted(missing)[:10]}")
    # ⚠️ Re-derive a known star from this run's own output and refuse to finish if it moved — the
    # same guard build_star_catalog.py puts on Sirius, and for the same reason: a dropped epoch
    # correction or a wrong column offset produces a file that parses perfectly and puts the sky in
    # slightly the wrong place. Alpha Centauri A is the anchor because it is the fastest-moving star
    # in this set, so it fails loudest. Corrected it lands 0.07 arcseconds from the published J2000
    # position; uncorrected it is 32.46 out, which is why one arcsecond is a safe bar.
    if ANCHOR_HIP in slot:
        ra, dec = stars[slot[ANCHOR_HIP]]
        cos_dec = math.cos(math.radians(dec))
        off = math.hypot((ra - ANCHOR_RA) * cos_dec, dec - ANCHOR_DEC) * 3600.0
        if off > ANCHOR_TOLERANCE_ARCSEC:
            problems.append(
                f"HIP {ANCHOR_HIP} is {off:.2f}\" from its published J2000 position "
                f"({ANCHOR_RA}, {ANCHOR_DEC}) — the epoch correction or a column offset is wrong"
            )
    else:
        problems.append(f"the anchor star HIP {ANCHOR_HIP} is not in the figure set")
    if len(figures) != 88:
        problems.append(f"expected 88 constellations, got {len(figures)}")
    # Every code a boundary names must be a constellation the figures know, allowing for Serpens
    # being two disjoint pieces in boundary data and one figure in the app.
    known = {f["code"].upper() for f in figures} | {"SER1", "SER2"}
    for e in edges:
        for side in (e[5], e[6]):
            if side.upper() not in known:
                problems.append(f"boundary names unknown constellation {side!r}")
    for f in figures:
        if not f["lines"]:
            problems.append(f"{f['code']} has no figure lines")
    for i, (ra, dec) in enumerate(stars):
        if not (0.0 <= ra < 360.0) or not (-90.0 <= dec <= 90.0):
            problems.append(f"star {i} is off the sphere: {ra} {dec}")
    for e in edges:
        if e[0] not in ("M", "P"):
            problems.append(f"edge kind {e[0]!r} is neither meridian nor parallel")
        if not (0.0 <= e[1] < 360.0 and 0.0 <= e[3] < 360.0):
            problems.append(f"edge RA off range: {e}")
        if not (-90.0 <= e[2] <= 90.0 and -90.0 <= e[4] <= 90.0):
            problems.append(f"edge Dec off range: {e}")
    # ⚠️ The boundaries tile the WHOLE sky, so their right ascensions must span very nearly the full
    # circle. This is here because the obvious range check cannot catch the likeliest mistake:
    # `22:52:00` is HOURS, and a reader that forgot the fifteen degrees to the hour yields values in
    # 0..24 — inside 0..360, inside every other guard, and describing a sky squeezed into one
    # twenty-fourth of itself.
    if edges:
        span = max(max(e[1], e[3]) for e in edges) - min(min(e[1], e[3]) for e in edges)
        if span < 300.0:
            problems.append(
                f"boundary right ascensions span only {span:.1f} deg — they should cover the sky; "
                "a span near 24 means the hours-to-degrees conversion was lost"
            )
    # A meridian holds RA; a parallel holds declination. If the two sexagesimal readers were
    # swapped, essentially every edge would fail this.
    for e in edges:
        if e[0] == "M" and abs(e[1] - e[3]) > 1e-6:
            problems.append(f"meridian edge does not hold RA: {e}")
        if e[0] == "P" and abs(e[2] - e[4]) > 1e-6:
            problems.append(f"parallel edge does not hold declination: {e}")

    if problems:
        for p in problems[:20]:
            print(f"  FAIL {p}", file=sys.stderr)
        print(f"{len(problems)} problem(s); nothing written", file=sys.stderr)
        return 1

    out_path.write_text(json.dumps(asset, separators=(",", ":")), encoding="utf-8")
    size = out_path.stat().st_size
    print(f"figures      {len(figures)}")
    print(f"asterisms    {len(asterisms)}  ({helpers} ray helpers excluded)")
    print(f"stars        {len(stars)}")
    print(f"boundaries   {len(edges)} edges")
    print(f"wrote        {out_path}  {size} bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
