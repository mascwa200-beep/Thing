#!/usr/bin/env python3
"""Measure the three figures `build_milkyway.py` quotes, from a packed catalogue, one method.

    python3 scratchpad/sky/measure_milkyway.py                       # the catalogue on disk
    python3 scratchpad/sky/measure_milkyway.py --catalogue /tmp/g15.skycat

This exists for the open follow-up recorded in CLAUDE.md: whether deepening the catalogue from
G<12 to G<15 STRENGTHENS or WEAKENS the Milky Way's structure. Dust shows up in this raster as
ABSENCE — extinction pushes a star below the magnitude cut and out of the count — so a deeper cut
recovers stars behind dust that a shallower one could not see, and whether that sharpens the rifts
or fills them in is a real question that this project has refused to guess at eighteen times over.

⚠️ **The one method matters more than the three numbers.** `build_milkyway.py` prints the density
contrast from its own `verify()`; the flux and along-the-plane figures in its module docstring were
measured during development by some other arrangement, and comparing a new catalogue against those
would be comparing two different sums. Everything below uses ONE band definition and ONE area
weighting, so a before/after really is a before/after.

⚠️ **THE TRAP THAT MADE THIS WORTH COMMITTING, and I fell straight into it.** The first version of
this decoded magnitude as `MAG_OFFSET + raw / 255 * MAG_SCALE`, reading MAG_SCALE as a span across
the byte. It is not: the shipped `StarCatalogFormat.decodeMagnitude` is `raw / MAG_SCALE +
MAG_OFFSET`, i.e. MAG_SCALE is STEPS PER MAGNITUDE — fourteen of them — and a full byte therefore
reaches 16.21 rather than 12. The wrong form makes every faint star about three magnitudes brighter
than it is, and since faint stars are concentrated in the plane it inflated the flux contrast from
4.45x to 5.42x. That number then looked like evidence the docstring's 4.52x had gone stale, and the
"correction" was one commit away from being written. **Derive the decode from the shipped function,
never from the constant's name.**

Reproduced against the shipped G<12 catalogue (3,087,821 stars), which is byte-for-byte the one the
committed `milkyway.bin` was built from:

    DENSITY  plane 183.80  poles 22.86   -> 8.04x   (docstring says 7.93x)
    FLUX     plane 0.01645 poles 0.003694 -> 4.45x  (docstring says 4.52x)
    ALONG THE PLANE  max 389.7 at l=289, min 77.9 at l=144 -> 5.00x  (docstring says 4.63x)

All three land within a few per cent of the docstring, which is the useful finding: **those numbers
are NOT stale**, and the small differences are a band or aggregation detail rather than anything
having gone wrong. Nothing to correct.

The two extremes are worth knowing before reading a deep-tier run against them. The minimum is at
l = 144 deg, which is the ANTICENTRE — thin because the line of sight leaves the galaxy, not because
of dust. The maximum at l = 289 deg is the Carina arm tangent, and it beats the galactic centre
itself, which at G<12 is heavily extincted. So this max/min is a global figure that conflates
"the anticentre is thin" with "the rifts are dark", and a deep-tier comparison should read the
per-longitude profile it prints rather than the single ratio.

⚠️ **`--profile` is the half that answers the question, and the trough is plainly there.** From the
same G<12 run, plane density by ten-degree bin:

    l   0- 19   227.7, 208.8      the inner galaxy
    l  20- 49   137.2, 105.9, 143.0   <- a trough about 2.0x below both its flanks
    l  60- 79   207.8, 205.4      recovered

That dip sits where the Aquila Rift lies, and it is exactly the structure the module docstring means
by "dust shows up as ABSENCE". **The figure to carry forward for a deep-tier comparison is that local
depth — 105.9 against a flanking mean of 212.4, so 2.01x — not the global 5.00x**, which is set by
the anticentre and would move for reasons that have nothing to do with dust.
"""

from __future__ import annotations

import argparse
import math
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "tools", "sky"))

# ⚠️ Imported, not paraphrased. `Raster` and `galactic` read their numbers out of `MilkyWay.kt`, and
# `Format`/`Grid` out of the catalogue's own Kotlin, so this cannot drift from what the app draws.
import build_milkyway as B                                        # noqa: E402
from build_catalogue import Format, Grid                          # noqa: E402

DEFAULT_CATALOGUE = os.path.join(ROOT, "core", "sky", "src", "main", "assets", "sky", "stars.skycat")


def load(path: str, raster, fmt: Format, grid: Grid):
    """Bin every star into the raster twice over: by count, and by linear flux."""
    with open(path, "rb") as fh:
        blob = fh.read()
    _magic, version, bands, tiles, stars, record_bytes = struct.unpack_from("<IHHIIH", blob, 0)
    if version != fmt.version or bands != grid.bands or tiles != grid.tile_count:
        raise SystemExit(f"catalogue is v{version} band{bands}/{tiles}, this build reads "
                         f"v{fmt.version} band{grid.bands}/{grid.tile_count}")
    records_at = fmt.header_bytes + (tiles + 1) * 4
    index = struct.unpack_from(f"<{tiles + 1}I", blob, fmt.header_bytes)

    counts = [0] * raster.cells
    flux = [0.0] * raster.cells
    for tile in range(tiles):
        start, stop = index[tile], index[tile + 1]
        if stop <= start:
            continue
        lo_ra, hi_ra, lo_dec, hi_dec = grid.bounds(tile)
        span_ra, span_dec = hi_ra - lo_ra, hi_dec - lo_dec
        for i in range(start, stop):
            at = records_at + i * record_bytes
            raw_ra, raw_dec, raw_mag = struct.unpack_from("<HHB", blob, at)
            ra = lo_ra + span_ra * raw_ra / 65535.0
            dec = lo_dec + span_dec * raw_dec / 65535.0
            lon, lat = B.galactic(ra, dec, raster)
            col = min(int(lon / raster.step), raster.columns - 1)
            row = min(int((lat + 90.0) / raster.step), raster.rows - 1)
            cell = row * raster.columns + col
            counts[cell] += 1
            # ⚠️ The shipped decode, spelled out: raw / MAG_SCALE + MAG_OFFSET. See the module note.
            flux[cell] += 10.0 ** (-0.4 * (raw_mag / fmt.mag_scale + fmt.mag_offset))
    return stars, counts, flux


def per_area(raw, raster):
    """Divide by each row's real solid angle — a count is not a density."""
    d = math.pi / 180.0
    out = [0.0] * raster.cells
    for row in range(raster.rows):
        lo = (-90.0 + row * raster.step) * d
        hi = lo + raster.step * d
        area = (math.sin(hi) - math.sin(lo)) * (raster.step * d) * (180.0 / math.pi) ** 2
        for col in range(raster.columns):
            out[row * raster.columns + col] = raw[row * raster.columns + col] / area
    return out


def band(vals, raster, lo_lat: float, hi_lat: float) -> float:
    """The same band definition `build_milkyway.verify` uses, so its number is comparable."""
    rows = [r for r in range(raster.rows)
            if lo_lat <= abs(-90.0 + (r + 0.5) * raster.step) <= hi_lat]
    xs = [vals[r * raster.columns + c] for r in rows for c in range(raster.columns)]
    return sum(xs) / len(xs)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--catalogue", default=DEFAULT_CATALOGUE)
    ap.add_argument("--profile", action="store_true",
                    help="also print the per-longitude plane profile, which is where a rift lives")
    args = ap.parse_args()
    if not os.path.exists(args.catalogue):
        raise SystemExit(f"no catalogue at {args.catalogue}")

    raster, fmt, grid = B.Raster(), Format(), Grid()
    stars, counts, flux = load(args.catalogue, raster, fmt, grid)
    dens, flx = per_area(counts, raster), per_area(flux, raster)

    dp, dpo = band(dens, raster, 0, 5), band(dens, raster, 75, 90)
    fp, fpo = band(flx, raster, 0, 5), band(flx, raster, 75, 90)
    print(f"{os.path.basename(args.catalogue)}: {stars:,} stars")
    print(f"  DENSITY  plane {dp:.2f}  poles {dpo:.2f}  -> contrast {dp / dpo:.2f}x")
    print(f"  FLUX     plane {fp:.4g}  poles {fpo:.4g}  -> contrast {fp / fpo:.2f}x")

    plane_rows = [r for r in range(raster.rows)
                  if abs(-90.0 + (r + 0.5) * raster.step) <= 5.0]
    by_lon = [sum(dens[r * raster.columns + c] for r in plane_rows) / len(plane_rows)
              for c in range(raster.columns)]
    hi, lo = max(by_lon), min(by_lon)
    print(f"  ALONG THE PLANE  max {hi:.1f} at l={by_lon.index(hi)}deg, "
          f"min {lo:.1f} at l={by_lon.index(lo)}deg -> {hi / lo:.2f}x")

    if args.profile:
        # ⚠️ This, not the ratio above, is what answers the rift question: a rift is a LOCAL trough
        # in the inner galaxy, and the global minimum is the anticentre, which is a different thing.
        print("  per-longitude plane density (10 deg bins):")
        for start in range(0, 360, 10):
            chunk = by_lon[start:start + 10]
            print(f"    l {start:3d}-{start + 9:3d}  {sum(chunk) / len(chunk):8.1f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
