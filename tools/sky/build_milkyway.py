#!/usr/bin/env python3
"""Build the Milky Way raster by counting the stars the bundled catalogue already holds.

The Milky Way is not painted here and no picture is downloaded. Star density on the sky IS the
Milky Way — the glow the eye sees is unresolved starlight — so counting how many stars the
catalogue holds in each direction measures the same thing, and the bulge, the thinning toward the
anticentre and the dark dust rifts all fall out of the measurement.

⚠️ **Density, not flux.** Measured both ways over the catalogue as it stood at G<12, the
plane-to-pole contrast is 7.93x by density and 4.52x by flux: flux is dominated by the brightest
stars in each direction, and those are nearby and so nearly isotropic. Counting is the better
instrument and the cheaper one. Those two numbers are LEFT at that depth deliberately — they are an
argument about which instrument to use, not a description of the current raster, and quoting a fresh
density beside a stale flux would compare two different sums.

⚠️ **Dust shows up as ABSENCE, which is why a magnitude-limited catalogue can see it at all.** Dust
does not dim a star a little, it pushes it below the magnitude cut and out of the count. On the
shipped G<15 raster the density varies 9.63x along the plane (mean over |b| <= 5, per whole degree of
longitude) and the troughs land on the real dust lanes.

⚠️ **Deepening the catalogue did not fill the rifts in, which was the open question.** Measured by
that one method against the raster this replaced: plane-to-pole went 8.03x -> 17.46x and the
along-the-plane variation 5.03x -> 9.63x, while the Great Rift held at 2.58x -> 2.56x below its
flanks. The band gained a great deal and the structure was not lost. One thing genuinely moved: the
maximum is now Sagittarius at l = 1 rather than Carina-Crux at l = 289, the bulge emerging from its
own extinction.

Reads `MilkyWay.kt` for the raster's shape and file layout, and `StarCatalogFormat.kt` /
`SkyGrid.kt` (through build_catalogue) for the star catalogue's, so there is one definition of each
rather than a second copy here that can drift.

    python3 tools/sky/build_milkyway.py
    python3 tools/sky/build_milkyway.py --catalogue core/sky/src/main/assets/sky/stars.skycat
"""

from __future__ import annotations

import argparse
import math
import os
import re
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
KOTLIN = os.path.join(ROOT, "core", "telemetry", "src", "main", "java", "dev", "mascwa", "pulse",
                      "core", "telemetry")

sys.path.insert(0, HERE)
# ⚠️ Imported rather than reimplemented. `Format` and `Grid` read their numbers out of the Kotlin,
# so this builder cannot drift from the reader the way a third hand-written copy of the record
# layout would.
from build_catalogue import Format, Grid, _kotlin, _const  # noqa: E402

# ⚠️ **`core/sky`, not `app`, and these were STALE.** Both assets moved into the library that reads
# them when `:core:sky` was carved out, so every default here has pointed at a directory that does
# not exist since then — a `--catalogue`-less invocation would have failed with a file-not-found
# rather than doing anything wrong, but it made the script unrunnable as documented.
_ASSETS = os.path.join(ROOT, "core", "sky", "src", "main", "assets", "sky")
DEFAULT_CATALOGUE = os.path.join(_ASSETS, "stars.skycat")
DEFAULT_OUT = os.path.join(_ASSETS, "milkyway.bin")


# --------------------------------------------------------------------------------------------
# the raster's own shape, read from the Kotlin that draws it
# --------------------------------------------------------------------------------------------

class Raster:
    def __init__(self) -> None:
        src = _kotlin("MilkyWay.kt")
        self.step = _const(src, "STEP_DEG")
        self.columns = int(_const(src, "COLUMNS"))
        self.rows = int(_const(src, "ROWS"))
        # CELLS is `COLUMNS * ROWS` in the Kotlin — an expression, not a literal, so it is derived
        # here the same way rather than read. The assertion below is what keeps the two honest.
        self.cells = self.columns * self.rows
        self.version = int(_const(src, "FILE_VERSION"))
        self.header_bytes = int(_const(src, "HEADER_BYTES"))
        self.pole_ra = _const(src, "POLE_RA_DEG")
        self.pole_dec = _const(src, "POLE_DEC_DEG")
        self.node_l = _const(src, "NODE_L_DEG")
        # ⚠️ The renderer's two ABSOLUTE thresholds, read for the same reason every other number
        # here is read rather than restated: they are stars per square degree, they are properties
        # of the raster this script writes, and nothing downstream can tell when they stop matching
        # it. See `report_thresholds`.
        self.faintest = _const(src, "FAINTEST_DENSITY")
        self.brightest = _const(src, "BRIGHTEST_DENSITY")
        # `const val MAGIC = 0x4D574159` is hex, which _const's decimal pattern cannot read.
        m = re.search(r"const val MAGIC\s*=\s*(0x[0-9A-Fa-f]+)", src)
        if not m:
            raise SystemExit("could not find `const val MAGIC` — has MilkyWay.kt moved?")
        self.magic = int(m.group(1), 16)
        # The Kotlin says `const val CELLS = COLUMNS * ROWS`; assert that is still what it says, so a
        # future change to either side cannot leave this builder writing a file the reader rejects.
        if not re.search(r"const val CELLS\s*=\s*COLUMNS \* ROWS", src):
            raise SystemExit("MilkyWay.CELLS is no longer COLUMNS * ROWS — check the raster shape")
        if abs(self.columns * self.step - 360.0) > 1e-9 or abs(self.rows * self.step - 180.0) > 1e-9:
            raise SystemExit("the raster does not cover the sky exactly once")


def galactic(ra_deg: float, dec_deg: float, r: Raster) -> tuple[float, float]:
    """Equatorial (J2000) to galactic, the same rotation `MilkyWay.galacticOf` performs."""
    d = math.pi / 180.0
    ra, dec = ra_deg * d, dec_deg * d
    pra, pdec, node = r.pole_ra * d, r.pole_dec * d, r.node_l * d
    sin_b = math.sin(pdec) * math.sin(dec) + math.cos(pdec) * math.cos(dec) * math.cos(ra - pra)
    y = math.cos(dec) * math.sin(ra - pra)
    x = math.cos(pdec) * math.sin(dec) - math.sin(pdec) * math.cos(dec) * math.cos(ra - pra)
    lon = ((node - math.atan2(y, x)) / d) % 360.0
    return lon, math.asin(max(-1.0, min(1.0, sin_b))) / d


# --------------------------------------------------------------------------------------------
# reading the packed catalogue
# --------------------------------------------------------------------------------------------

def read_positions(path: str, fmt: Format, grid: Grid):
    """Every star's right ascension and declination, decoded from the packed catalogue."""
    # ⚠️ LITTLE-endian throughout: `StarCatalogReader` opens the buffer with
    # `ByteOrder.LITTLE_ENDIAN` and `build_catalogue.pack` writes "<". My first version of this
    # reader used ">" and reported the file as "version 256" — 0x0100, which is 1 with its bytes
    # the other way round. The new raster below uses the same order rather than introducing a
    # second convention into one directory.
    with open(path, "rb") as fh:
        blob = fh.read()
    magic, version, bands, tile_count, star_count, record_bytes = struct.unpack_from("<IHHIIH", blob, 0)
    if version != fmt.version:
        raise SystemExit(f"catalogue is version {version}, this build expects {fmt.version}")
    if bands != grid.bands or tile_count != grid.tile_count:
        raise SystemExit(f"catalogue is band{bands}/{tile_count}, the grid is {grid.bands}/{grid.tile_count}")
    if record_bytes != fmt.record_bytes:
        raise SystemExit(f"catalogue records are {record_bytes} bytes, the format says {fmt.record_bytes}")

    records_at = fmt.header_bytes + (tile_count + 1) * 4
    index = struct.unpack_from(f"<{tile_count + 1}I", blob, fmt.header_bytes)
    print(f"  catalogue: {star_count:,} stars over {tile_count:,} tiles")

    for tile in range(tile_count):
        start, stop = index[tile], index[tile + 1]
        if stop <= start:
            continue
        # ⚠️ RA FIRST. `Grid.bounds` returns (ra_lo, ra_hi, dec_lo, dec_hi) and my first version of
        # this line assumed declination first — which swaps the axes, produces a completely wrong sky,
        # and looks entirely plausible in the output because a rotated Milky Way is still a band.
        lo_ra, hi_ra, lo_dec, hi_dec = grid.bounds(tile)
        span_ra, span_dec = hi_ra - lo_ra, hi_dec - lo_dec
        for i in range(start, stop):
            at = records_at + i * record_bytes
            raw_ra, raw_dec = struct.unpack_from("<HH", blob, at)
            yield (lo_ra + span_ra * raw_ra / 65535.0, lo_dec + span_dec * raw_dec / 65535.0)


# --------------------------------------------------------------------------------------------

def build(catalogue: str, out_path: str) -> int:
    raster = Raster()
    fmt, grid = Format(), Grid()
    print(f"raster: {raster.columns}x{raster.rows} at {raster.step} deg = {raster.cells:,} cells")

    counts = [0] * raster.cells
    total = 0
    for ra, dec in read_positions(catalogue, fmt, grid):
        lon, lat = galactic(ra, dec, raster)
        col = min(int(lon / raster.step), raster.columns - 1)
        row = min(int((lat + 90.0) / raster.step), raster.rows - 1)
        counts[row * raster.columns + col] += 1
        total += 1
    print(f"  binned {total:,} stars")
    if total < 1_000_000:
        raise SystemExit(f"only {total:,} stars binned — the catalogue looks wrong")

    # ⚠️ A count is NOT a density: a cell's solid angle shrinks with the cosine of its latitude, so
    # a polar cell holding as many stars as an equatorial one is far denser. Dividing by the real
    # solid angle is what makes the raster a measurement of the sky rather than of the grid.
    d = math.pi / 180.0
    density = [0.0] * raster.cells
    widest = 1
    for row in range(raster.rows):
        lo = (-90.0 + row * raster.step) * d
        hi = lo + raster.step * d
        area = (math.sin(hi) - math.sin(lo)) * (raster.step * d) * (180.0 / math.pi) ** 2

        # ⚠️ **A count of one star is not a measurement of a density, and near the poles that is all
        # a cell holds.** Measured on the real 3,087,821-star catalogue: the row at |b| = 89.5 has a
        # solid angle of 0.0087 deg^2 per cell and averages 0.16 stars in it, so 303 of its 360
        # cells are EMPTY and the other 57 read about 115 /deg^2 — a speckled ring at each pole,
        # which looks like a rendering fault rather than like sky. The row MEANS are right (19-26,
        # matching the 23.2 measured for |b| > 75) — it is only the per-cell estimate that is noise.
        #
        # So the count is averaged over as many columns as it takes to cover roughly the same patch
        # of SKY as an equatorial cell: 1/cos(b) of them, wrapping. That is the standard treatment
        # for an equirectangular grid's polar convergence, it preserves each row's total exactly,
        # and it is a no-op below 60 degrees where a window of one is already right. What it costs
        # is longitude resolution near the poles, which is honest: at the pole there is none to have.
        # `window * cos(b)` is the arc of great-circle longitude the average covers, so a window of
        # 1/cos(b) covers one degree of it at every latitude — the same width as an equatorial cell.
        cos_b = max(1e-6, math.cos((-90.0 + (row + 0.5) * raster.step) * d))
        half = min(raster.columns // 2, max(0, int(round((1.0 / cos_b - 1.0) / 2.0))))
        window = 2 * half + 1
        widest = max(widest, window)
        base = row * raster.columns
        for col in range(raster.columns):
            if half == 0:
                n = counts[base + col]
            else:
                n = 0
                for k in range(col - half, col + half + 1):
                    n += counts[base + (k % raster.columns)]
            density[base + col] = n / (area * window)
    print(f"  polar smoothing: widest longitude window {widest} cells")

    peak = max(density)
    if peak <= 0.0:
        raise SystemExit("every cell came out empty")
    cells = bytearray(raster.cells)
    for i, v in enumerate(density):
        # Square-root scaled, matching `MilkyWay.encodeDensity`. Linear rounds the faintest cells
        # to zero and deletes the outer Milky Way; see that function's KDoc for the measurement.
        cells[i] = max(0, min(255, round(255.0 * math.sqrt(min(1.0, v / peak)))))

    header = bytearray(raster.header_bytes)
    struct.pack_into("<IHHHH", header, 0, raster.magic, raster.version, raster.columns, raster.rows, 0)
    struct.pack_into("<f", header, 12, peak)
    with open(out_path, "wb") as fh:
        fh.write(bytes(header))
        fh.write(bytes(cells))

    verify(out_path, raster, density, peak)
    size = os.path.getsize(out_path)
    print(f"  wrote {out_path} — {size:,} bytes, peak {peak:.1f} stars/deg^2")
    report_thresholds(raster, density, peak)
    return 0


def report_thresholds(raster: Raster, density: list[float], peak: float) -> None:
    """Say what the renderer's two absolute thresholds should be for THIS raster.

    ⚠️ **`MilkyWay.FAINTEST_DENSITY` and `BRIGHTEST_DENSITY` are absolute stars per square degree,
    and nothing else in the build can notice when they stop matching the raster.** They were chosen
    against a G<12 sky — a floor of 40 against a median cell of 38, a ceiling of 400 at the 99.9th
    percentile — and at G<15 the poles alone measure 164 and the plane 2,844. Left alone against a
    deeper raster the whole sky glows and the band saturates flat, while every gate here passes:
    `verify` below would read a 17x contrast and be delighted. Only a person looking at a phone
    would see it.

    So the two numbers the constants are DEFINED as, in their own KDocs, are printed here from the
    raster that was actually built. Both are statistics of the density field rather than free
    choices: the floor is the median cell, which is what makes roughly half the sky black, and the
    ceiling is the 99.9th percentile, so the brightest tenth of a per cent saturates and everything
    else keeps the full range.

    ⚠️ They do not scale together, which is why this reports rather than multiplying. Between G<12
    and G<15 the plane grows 15.5x and the poles only 7.1x — measured against the archive — so the
    field changes SHAPE, and a single factor applied to both constants would be wrong in opposite
    directions at the two ends.
    """
    ordered = sorted(density)
    median = ordered[len(ordered) // 2]
    p999 = ordered[min(len(ordered) - 1, int(round(0.999 * (len(ordered) - 1))))]

    def band(lo_lat: float, hi_lat: float) -> float:
        rows_in = [r for r in range(raster.rows)
                   if lo_lat <= abs(-90.0 + (r + 0.5) * raster.step) <= hi_lat]
        vals = [density[r * raster.columns + c] for r in rows_in for c in range(raster.columns)]
        return sum(vals) / len(vals)

    print("  --- what MilkyWay.kt's thresholds should be for this raster ---")
    print(f"    peak cell            {peak:10.1f} /deg^2")
    print(f"    99.9th percentile    {p999:10.1f} /deg^2   <- BRIGHTEST_DENSITY")
    print(f"    median cell          {median:10.1f} /deg^2   <- FAINTEST_DENSITY")
    print(f"    plane |b| <= 5       {band(0.0, 5.0):10.1f} /deg^2")
    print(f"    poles |b| >= 75      {band(75.0, 90.0):10.1f} /deg^2")
    print(f"    MilkyWay.kt says     FAINTEST {raster.faintest:g}, BRIGHTEST {raster.brightest:g}")

    # ⚠️ The property the floor exists to give: the empty sky must stay empty. If the polar band is
    # at or above the floor then high latitudes are drawn as glow, which is the exact failure this
    # whole function is here to prevent — so it is checked by name rather than left to be read off
    # the two numbers above.
    poles = band(75.0, 90.0)
    if poles >= raster.faintest:
        raise SystemExit(
            f"MilkyWay.FAINTEST_DENSITY is {raster.faintest:g} and the poles of this raster measure "
            f"{poles:.1f} — the high-latitude sky would be drawn as glow rather than left black")

    # ⚠️ **±30% because the constants are DELIBERATELY ROUNDED and a depth change is not subtle.**
    # Measured: the shipped pair sits +1.4% and +0.1% off this raster's statistics, and the G<12 pair
    # sat +12% and -0.8% off its own. A change of catalogue depth moves them by 8-30x. So this band
    # is wide enough that nobody has to write 276.2 in a source file, and narrower than anything that
    # could actually go wrong by an order of magnitude.
    for what, want, got in (("FAINTEST_DENSITY", median, raster.faintest),
                            ("BRIGHTEST_DENSITY", p999, raster.brightest)):
        if not (want * 0.7 <= got <= want * 1.3):
            raise SystemExit(
                f"MilkyWay.{what} is {got:g} but this raster says it should be about {want:.1f} "
                f"— they are more than 30% apart, so the renderer is tuned for a different sky")
    print("    both constants agree with this raster")


def verify(path: str, raster: Raster, density: list[float], peak: float) -> None:
    """Read the file back and check it says what the measurement said."""
    with open(path, "rb") as fh:
        blob = fh.read()
    expected = raster.header_bytes + raster.cells
    if len(blob) != expected:
        raise SystemExit(f"wrote {len(blob)} bytes, expected {expected}")
    magic, version, columns, rows, _ = struct.unpack_from("<IHHHH", blob, 0)
    (stored_peak,) = struct.unpack_from("<f", blob, 12)
    if magic != raster.magic or version != raster.version:
        raise SystemExit("header does not read back")
    if columns != raster.columns or rows != raster.rows:
        raise SystemExit(f"header says {columns}x{rows}, the raster is {raster.columns}x{raster.rows}")
    if abs(stored_peak - peak) / peak > 1e-6:
        raise SystemExit(f"peak stored as {stored_peak} but measured {peak}")

    body = blob[raster.header_bytes:]
    worst = 0.0
    # ⚠️ The cost a LINEAR encoding would have carried, measured beside the real one rather than
    # quoted from an old run. `MilkyWay.encodeDensity`'s KDoc justifies the square root with this
    # comparison, and the two numbers move with the depth — so measuring both here is what keeps
    # that justification true instead of leaving it a statement about a catalogue we no longer ship.
    #
    # ⚠️ It has to be computed against `density`, the array BEFORE encoding. Doing it against the
    # decoded bytes measures the round-trip of an already-quantised value, which is exact by
    # construction and reports a triumphant and meaningless 0.0%.
    worst_linear = 0.0
    for i, v in enumerate(density):
        if v <= 0.0:
            continue
        back = (body[i] / 255.0) ** 2 * stored_peak
        worst = max(worst, abs(back - v) / v)
        lin = round(255.0 * min(1.0, v / stored_peak)) / 255.0 * stored_peak
        worst_linear = max(worst_linear, abs(lin - v) / v)
    if worst > 0.15:
        raise SystemExit(f"a byte costs up to {worst * 100:.1f}% — the encoding is not good enough")

    # ⚠️ The measurement that says the file is the Milky Way and not noise: the plane must be far
    # denser than the poles, or something has gone wrong in the galactic transform and nothing else
    # here would notice — a rotated sky is still a plausible-looking file.
    def band(lo_lat: float, hi_lat: float) -> float:
        rows_in = [r for r in range(raster.rows)
                   if lo_lat <= abs(-90.0 + (r + 0.5) * raster.step) <= hi_lat]
        vals = [density[r * raster.columns + c] for r in rows_in for c in range(raster.columns)]
        return sum(vals) / len(vals)

    plane, poles = band(0.0, 5.0), band(75.0, 90.0)
    contrast = plane / poles
    print(f"  plane {plane:.1f} vs poles {poles:.1f} stars/deg^2 — contrast {contrast:.2f}x")
    if contrast < 4.0:
        raise SystemExit(f"plane-to-pole contrast is only {contrast:.2f}x — this is not the Milky Way")
    print(f"  worst byte error {worst * 100:.1f}% square-root scaled "
          f"({worst_linear * 100:.1f}% had it been linear)")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--catalogue", default=DEFAULT_CATALOGUE)
    ap.add_argument("--out", default=DEFAULT_OUT)
    args = ap.parse_args()
    if not os.path.exists(args.catalogue):
        raise SystemExit(f"no catalogue at {args.catalogue}")
    return build(args.catalogue, args.out)


if __name__ == "__main__":
    raise SystemExit(main())
