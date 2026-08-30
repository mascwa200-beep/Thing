#!/usr/bin/env python3
"""Does a deeper catalogue strengthen or fill the Great Rift? Ask the archive, not the file.

    python3 scratchpad/sky/measure_rift_depth.py
    python3 scratchpad/sky/measure_rift_depth.py --magnitudes 12 14 15

This answers the open follow-up recorded in CLAUDE.md beside `measure_milkyway.py`. That note
said the question needed the 295 MB G<15 catalogue on disk, and **the framing was wrong**: the
Milky Way raster is a DENSITY map, `gaia_source` carries galactic `l`/`b` as columns, and a
`GROUP BY` therefore answers it directly. Four requests instead of the ~2,200 a crawl costs,
which also keeps faith with the politeness reasoning `sky-catalogue.yml` is built on.

⚠️ **VALIDATED AGAINST THE BANKED BASELINE BY A COMPLETELY DIFFERENT ROUTE.** `measure_milkyway.py`
runs the shipped raster over the packed catalogue and banked a G<12 rift depth of 2.01x and a
plane-to-pole contrast of 7.93x (the module docstring's figure). This reads raw Gaia through the
archive and gets **2.01x and 7.93x**. Two independent paths, same numbers — which is what makes
the deeper comparison worth believing.

⚠️ **THE PLANE BAND NEEDS NO AREA WEIGHTING AND THE POLE COMPARISON DOES.** Every 10-degree
longitude bin of the |b| <= 5 band covers the same solid angle, so within a profile the raw
counts ARE the density and there is no weighting to get backwards. Only the plane-against-poles
figure divides by area, because those two bands genuinely differ.

⚠️ **THREE AGGREGATIONS, AND THEY DISAGREE — READ ALL THREE.** Measured across G<12 -> G<15:

    plane-to-pole contrast                     7.93x -> 17.38x   strengthens, unambiguously
    rift, min-based (the banked method)        2.01x ->  2.05x   flat
    rift, mean of the three trough bins        1.65x ->  1.56x   fills in
    rift, min-based, centre bin excluded       1.96x ->  1.63x   fills in

The flat one is the outlier and the reason is identifiable rather than noise: **l = 0-9 is the
galactic CENTRE, not a flank.** It grows 30.3x between the two cuts against the plane's overall
15.5x — the centre emerging from its own extinction, which is the same phenomenon the rift is
made of, happening somewhere else and pulling the flank average up with it. Exclude it and the
min-based measure agrees with the mean-based one.

So: **the band gets much stronger and the rift fills in somewhat.** A likely mechanism, offered as
reasoning and not as something measured here: extinction is a fixed magnitude penalty, so a dust
lane admits stars down to L-A where clear sky admits them to L, and the ratio N(L-A)/N(L) tends
toward 1 as L moves into the range where disc counts flatten. In a Euclidean universe the ratio
would not move with depth at all; the galaxy is not Euclidean, and it runs out.

⚠️ **This measures the SKY, not the raster the app draws.** It is the right instrument for the
question — and the G<12 agreement above is what licenses that claim — but a rebuilt
`milkyway.bin` should still be compared with `measure_milkyway.py` against the old one before it
ships, because the raster adds its own binning and smoothing on top.
"""

from __future__ import annotations

import argparse
import math
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "tools", "sky"))

# ⚠️ Imported, so this speaks to the archive through exactly the mechanism the builder uses —
# same endpoint, same encoding, same row cap. A hand-rolled POST here could drift from it.
from build_catalogue import _post                                    # noqa: E402

BIN_DEG = 10          # matches the profile `measure_milkyway.py` prints, so the two are comparable
PLANE_LAT = 5.0       # |b| <= 5, the band `build_milkyway.verify` calls the plane
POLE_LAT = 75.0       # |b| >= 75, the band it calls the poles
TROUGH_BINS = (2, 3, 4)      # l = 20-49, where the Aquila Rift lies
FLANK_BINS = (0, 1, 6, 7)    # l = 0-19 and 60-79 — see the centre-bin warning above


def _solid_angle(lo_deg: float, hi_deg: float) -> float:
    """Square degrees of ONE latitude band, lo to hi, signed.

    ⚠️ One band, not a mirrored pair — a caller wanting both hemispheres asks twice. The
    mirroring version of this reads fine and is wrong for the plane, whose band already spans
    both signs, so it would double an area that is already whole.
    """
    d = 180.0 / math.pi
    return 360.0 * (math.sin(math.radians(hi_deg)) - math.sin(math.radians(lo_deg))) * d


def profile(mag: float) -> dict[int, int]:
    # ⚠️ `GROUP BY lonbin`, not `GROUP BY FLOOR(l/10)`. This parser rejects an EXPRESSION in a
    # GROUP BY outright ("Was expecting ... <REGULAR_IDENTIFIER> ... <UNSIGNED_INTEGER>"), so the
    # alias — or the column position — is the only form it takes.
    q = (f"SELECT FLOOR(l/{BIN_DEG}) AS lonbin, COUNT(*) AS n FROM gaiadr3.gaia_source "
         f"WHERE phot_g_mean_mag < {mag} AND b BETWEEN {-PLANE_LAT} AND {PLANE_LAT} "
         f"GROUP BY lonbin ORDER BY lonbin")
    started = time.time()
    out: dict[int, int] = {}
    for line in _post(q, timeout=900).splitlines()[1:]:
        if line.strip():
            a, b = line.split(",")[:2]
            out[int(float(a))] = int(float(b))
    print(f"  G<{mag} plane: {len(out)} bins, {sum(out.values()):,} stars, {time.time()-started:.1f}s")
    return out


def poles(mag: float) -> int:
    q = (f"SELECT COUNT(*) AS n FROM gaiadr3.gaia_source "
         f"WHERE phot_g_mean_mag < {mag} AND (b >= {POLE_LAT} OR b <= {-POLE_LAT})")
    return int(float(_post(q, timeout=900).splitlines()[1]))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--magnitudes", nargs="+", type=float, default=[12, 15])
    ap.add_argument("--bins", action="store_true", help="print every 10-degree bin, not just 0-79")
    args = ap.parse_args()

    a_plane = _solid_angle(-PLANE_LAT, PLANE_LAT)          # one band, already spanning both signs
    a_pole = 2 * _solid_angle(POLE_LAT, 90.0)              # two caps, so twice
    print(f"bands: plane {a_plane:.0f} deg^2, poles {a_pole:.0f} deg^2")

    for mag in args.magnitudes:
        prof, pole = profile(mag), poles(mag)
        dens_plane = sum(prof.values()) / a_plane
        dens_pole = pole / a_pole

        upto = 36 if args.bins else 8
        print(f"\nG<{mag}  ({BIN_DEG}-degree bins, |b| <= {PLANE_LAT:g})")
        for b in range(upto):
            print(f"  l {b*BIN_DEG:3d}-{b*BIN_DEG+BIN_DEG-1:3d}  {prof.get(b, 0):>10,}")

        t_min = min(prof[b] for b in TROUGH_BINS)
        t_at = min(TROUGH_BINS, key=lambda b: prof[b])
        t_mean = sum(prof[b] for b in TROUGH_BINS) / len(TROUGH_BINS)
        f_mean = sum(prof[b] for b in FLANK_BINS) / len(FLANK_BINS)
        f_nc = sum(prof[b] for b in FLANK_BINS[1:]) / (len(FLANK_BINS) - 1)

        print(f"  PLANE-TO-POLE  {dens_plane:.1f}/deg^2 against {dens_pole:.2f}/deg^2 "
              f"-> {dens_plane / dens_pole:.2f}x")
        print(f"  RIFT min-based (the banked method)  min {t_min:,} at "
              f"l={t_at*BIN_DEG}-{t_at*BIN_DEG+BIN_DEG-1} / flanks {f_mean:,.0f} "
              f"-> {f_mean / t_min:.2f}x")
        print(f"  RIFT mean-based                     trough {t_mean:,.0f} / flanks {f_mean:,.0f} "
              f"-> {f_mean / t_mean:.2f}x")
        print(f"  RIFT min-based, centre bin excluded  flanks {f_nc:,.0f} / {t_min:,} "
              f"-> {f_nc / t_min:.2f}x")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
