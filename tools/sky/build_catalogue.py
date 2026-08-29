#!/usr/bin/env python3
"""Build the packed star catalogue from Gaia DR3.

    python3 tools/sky/build_catalogue.py --magnitude 12 --out core/sky/src/main/assets/sky/stars.skycat

⚠️ **EVERY CONSTANT COMES OUT OF THE KOTLIN.** The record layout, the magnitude and colour scales,
the proper-motion square law and the number of declination bands are read from
`StarCatalogFormat.kt` and `SkyGrid.kt` at run time rather than restated here. A builder and a
reader that disagree about where the magnitude byte sits do not fail — they produce a sky of
plausible stars in slightly wrong places, and nothing in either language notices. So there is one
definition and this script reads it.

The one thing that genuinely has to be reimplemented is `SkyGrid`'s tiling arithmetic, because it is
an algorithm rather than a table. `--parity` writes a fixture that `SkyGridParityTest` checks in CI,
so the two implementations cannot drift apart silently.

## ⚠️ The archive caps sync queries at 50,000 rows and does not say so

Measured, not assumed: `MAXREC=200000` and `MAXREC=1000000` both return exactly 50,000 rows, and the
CSV carries no overflow marker of any kind. A truncated chunk is byte-for-byte the shape of a
complete one. So **a chunk that comes back with exactly the cap is treated as truncated and split**,
never as finished — that rule is the whole reason this fetches adaptively instead of by a fixed grid.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import math
import os
import re
import struct
import sys
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KOTLIN = os.path.join(ROOT, "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry")

TAP = "https://gea.esac.esa.int/tap-server/tap/sync"

# ⚠️ Measured. The server enforces this regardless of what MAXREC asks for, and reports nothing.
SERVER_ROW_CAP = 50_000

# Gaia DR3 astrometry is referred to this epoch, and the reader needs it to apply proper motion.
GAIA_EPOCH_MILLIYEAR = 2_016_000

# ⚠️ Polite. This repository has twice earned a durable rate-limit ban by bursting at a public
# service, and ESA's archive is a research facility rather than a CDN. Four at a time with a pause
# after each finishes is roughly a browser's worth of traffic.
CONCURRENCY = 4
PAUSE_SECONDS = 0.25


# --------------------------------------------------------------------------------------------
# reading the Kotlin, so there is one definition of the format
# --------------------------------------------------------------------------------------------

def _kotlin(name: str) -> str:
    with open(os.path.join(KOTLIN, name), encoding="utf-8") as fh:
        return fh.read()


def _const(source: str, name: str) -> float:
    """A `const val NAME = <number>` out of a Kotlin source."""
    m = re.search(rf"const val {name}\s*=\s*(-?[0-9_]+(?:\.[0-9]+)?)", source)
    if not m:
        raise SystemExit(f"could not find `const val {name}` — has the Kotlin moved?")
    return float(m.group(1).replace("_", ""))


class Format:
    """The record layout, read from `StarCatalogFormat.kt`."""

    def __init__(self) -> None:
        src = _kotlin("StarCatalogFormat.kt")
        self.version = int(_const(src, "VERSION"))
        self.header_bytes = int(_const(src, "HEADER_BYTES"))
        self.record_bytes = int(_const(src, "RECORD_BYTES"))
        self.mag_offset = _const(src, "MAG_OFFSET")
        self.mag_scale = _const(src, "MAG_SCALE")
        self.colour_offset = _const(src, "COLOUR_OFFSET")
        self.colour_absent = int(_const(src, "COLOUR_ABSENT"))
        self.pm_scale = _const(src, "PM_SCALE")
        # COLOUR_SCALE is an expression rather than a literal, so it is derived the same way.
        m = re.search(r"const val COLOUR_SCALE\s*=\s*([0-9.]+)\s*/\s*([0-9.]+)", src)
        if not m:
            raise SystemExit("could not read COLOUR_SCALE")
        self.colour_scale = float(m.group(1)) / float(m.group(2))
        if self.record_bytes != 8:
            raise SystemExit(f"this writer emits 8-byte records; the Kotlin says {self.record_bytes}")

    # ⚠️ Half-up in both languages, explicitly. Python's own `round` is banker's, exactly like
    # Kotlin's `round`, and the format's Kotlin side already spells this out for the same reason.
    @staticmethod
    def _half_up(value: float) -> int:
        if not math.isfinite(value):
            return 0
        return math.floor(value + 0.5)

    def magnitude(self, m: float) -> int:
        return max(0, min(255, self._half_up((m - self.mag_offset) * self.mag_scale)))

    def colour(self, bp_rp: float | None) -> int:
        if bp_rp is None or not math.isfinite(bp_rp):
            return self.colour_absent
        return max(0, min(254, self._half_up((bp_rp - self.colour_offset) * self.colour_scale)))

    def proper_motion(self, mas_per_year: float | None) -> int:
        if mas_per_year is None or not math.isfinite(mas_per_year) or mas_per_year == 0.0:
            return 0
        v = min(127, self._half_up(math.sqrt(abs(mas_per_year) / self.pm_scale)))
        return v if mas_per_year > 0 else -v

    @staticmethod
    def fraction(f: float) -> int:
        if not math.isfinite(f):
            return 0
        return max(0, min(65535, Format._half_up(f * 65535.0)))


class Grid:
    """
    The tiling, reimplemented from `SkyGrid.kt`.

    ⚠️ This is the one twin in the pipeline, because the tiling is an algorithm and not a table.
    `--parity` exists so it cannot drift: it writes positions and the tile ids this implementation
    assigns them, and a Kotlin test requires `SkyGrid` to agree.
    """

    def __init__(self) -> None:
        src = _kotlin("SkyGrid.kt")
        self.bands = int(_const(src, "BANDS"))
        self.band_height = 180.0 / self.bands
        self.divisions = [self._ra_divisions(b) for b in range(self.bands)]
        self.start = [0] * (self.bands + 1)
        running = 0
        for b in range(self.bands):
            self.start[b] = running
            running += self.divisions[b]
        self.start[self.bands] = running
        self.tile_count = running

    def _ra_divisions(self, band: int) -> int:
        lo = -90.0 + band * self.band_height
        hi = lo + self.band_height
        if lo >= 0.0:
            widest = math.cos(math.radians(lo))
        elif hi <= 0.0:
            widest = math.cos(math.radians(hi))
        else:
            widest = 1.0
        return max(1, math.ceil(360.0 * widest / self.band_height))

    def band_of(self, dec: float) -> int:
        return max(0, min(self.bands - 1, math.floor((dec + 90.0) / self.band_height)))

    def tile_of(self, ra: float, dec: float) -> int:
        band = self.band_of(dec)
        n = self.divisions[band]
        column = max(0, min(n - 1, math.floor((ra % 360.0) / (360.0 / n))))
        return self.start[band] + column

    def bounds(self, tile: int) -> tuple[float, float, float, float]:
        band = 0
        for b in range(self.bands):
            if self.start[b] <= tile < self.start[b + 1]:
                band = b
                break
        n = self.divisions[band]
        column = tile - self.start[band]
        width = 360.0 / n
        lo = -90.0 + band * self.band_height
        return (column * width, (column + 1) * width, lo, lo + self.band_height)

    @property
    def format_key(self) -> str:
        return f"band{self.bands}/{self.tile_count}"


# --------------------------------------------------------------------------------------------
# fetching
# --------------------------------------------------------------------------------------------

COLUMNS = "ra,dec,phot_g_mean_mag,bp_rp,pmra,pmdec"


def _query(magnitude: float, dec_lo: float, dec_hi: float, ra_lo: float, ra_hi: float) -> str:
    # ⚠️ Half-open bands, so no star is fetched twice — EXCEPT at the very top, where a half-open
    # range would silently drop anything at exactly +90. Nothing sits precisely on the pole, but a
    # boundary that can lose a row is not worth leaving in a builder nobody will re-read.
    dec_clause = (
        f"dec >= {dec_lo} AND dec <= {dec_hi}" if dec_hi >= 90.0
        else f"dec >= {dec_lo} AND dec < {dec_hi}"
    )
    ra_clause = (
        f"ra >= {ra_lo} AND ra <= {ra_hi}" if ra_hi >= 360.0
        else f"ra >= {ra_lo} AND ra < {ra_hi}"
    )
    where = [f"phot_g_mean_mag < {magnitude}", dec_clause, ra_clause]
    return f"SELECT {COLUMNS} FROM gaiadr3.gaia_source WHERE " + " AND ".join(where)


def _post(query: str, timeout: int = 600) -> str:
    body = urllib.parse.urlencode({
        "REQUEST": "doQuery",
        "LANG": "ADQL",
        "FORMAT": "csv",
        "MAXREC": str(SERVER_ROW_CAP),
        "QUERY": query,
    }).encode()
    request = urllib.request.Request(TAP, data=body, headers={"Accept": "text/csv"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read().decode("utf-8", "replace")


def _rows(csv_text: str) -> list[tuple]:
    out = []
    for line in csv_text.splitlines()[1:]:
        if not line:
            continue
        parts = line.split(",")
        if len(parts) < 6:
            continue
        try:
            ra = float(parts[0])
            dec = float(parts[1])
            mag = float(parts[2])
        except ValueError:
            continue

        def optional(text: str) -> float | None:
            try:
                v = float(text)
                return v if math.isfinite(v) else None
            except ValueError:
                return None

        out.append((ra, dec, mag, optional(parts[3]), optional(parts[4]), optional(parts[5])))
    return out


def _cache_path(cache_dir: str, query: str) -> str:
    return os.path.join(cache_dir, hashlib.sha256(query.encode()).hexdigest()[:24] + ".csv")


def fetch_chunk(magnitude, dec_lo, dec_hi, ra_lo, ra_hi, cache_dir, depth=0):
    """
    One chunk, split until it is provably complete.

    ⚠️ **A result of exactly [SERVER_ROW_CAP] rows is treated as TRUNCATED**, because the archive
    silently caps and says nothing. On the vanishingly rare occasion that a chunk really does hold
    exactly fifty thousand stars, splitting it costs one extra request and loses nothing.
    """
    query = _query(magnitude, dec_lo, dec_hi, ra_lo, ra_hi)
    path = _cache_path(cache_dir, query)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
    else:
        last = None
        for attempt in range(4):
            try:
                text = _post(query)
                last = None
                break
            except Exception as exc:                       # noqa: BLE001 — any failure is a retry
                last = exc
                time.sleep(2 ** attempt)
        if last is not None:
            raise SystemExit(f"gave up on {dec_lo:.2f}..{dec_hi:.2f} / {ra_lo:.1f}..{ra_hi:.1f}: {last}")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)
        time.sleep(PAUSE_SECONDS)

    rows = _rows(text)
    if len(rows) < SERVER_ROW_CAP:
        return rows

    if depth > 12:
        raise SystemExit(
            f"cannot split {dec_lo}..{dec_hi} / {ra_lo}..{ra_hi} small enough to clear the row cap"
        )
    # Split the wider axis, so a tall thin slice does not become a hundred taller thinner ones.
    os.remove(path)                                        # never keep a truncated chunk
    if (ra_hi - ra_lo) >= (dec_hi - dec_lo) * 4.0:
        mid = (ra_lo + ra_hi) / 2.0
        halves = [(dec_lo, dec_hi, ra_lo, mid), (dec_lo, dec_hi, mid, ra_hi)]
    else:
        mid = (dec_lo + dec_hi) / 2.0
        halves = [(dec_lo, mid, ra_lo, ra_hi), (mid, dec_hi, ra_lo, ra_hi)]
    out = []
    for d0, d1, r0, r1 in halves:
        out += fetch_chunk(magnitude, d0, d1, r0, r1, cache_dir, depth + 1)
    return out


# --------------------------------------------------------------------------------------------
# writing
# --------------------------------------------------------------------------------------------

def pack(rows, grid: Grid, fmt: Format, magnitude: float, out_path: str) -> dict:
    buckets: dict[int, list] = {}
    for ra, dec, mag, bp_rp, pmra, pmdec in rows:
        buckets.setdefault(grid.tile_of(ra, dec), []).append((mag, ra, dec, bp_rp, pmra, pmdec))

    # ⚠️ Brightest first WITHIN a tile, which is what lets the reader stop early at the view's
    # magnitude cut instead of decoding the whole tile.
    for tile in buckets:
        buckets[tile].sort(key=lambda r: r[0])

    total = sum(len(v) for v in buckets.values())
    index = []
    records = bytearray()
    running = 0
    for tile in range(grid.tile_count):
        index.append(running)
        ra_lo, ra_hi, dec_lo, dec_hi = grid.bounds(tile)
        ra_span = ra_hi - ra_lo
        dec_span = dec_hi - dec_lo
        for mag, ra, dec, bp_rp, pmra, pmdec in buckets.get(tile, ()):
            records += struct.pack(
                "<HHBBbb",
                fmt.fraction(((ra % 360.0) - ra_lo) / ra_span),
                fmt.fraction((dec - dec_lo) / dec_span),
                fmt.magnitude(mag),
                fmt.colour(bp_rp),
                fmt.proper_motion(pmra),
                fmt.proper_motion(pmdec),
            )
            running += 1
    index.append(running)
    assert running == total, f"packed {running} records but bucketed {total}"

    header = bytearray(fmt.header_bytes)
    header[0:4] = b"SKYC"
    struct.pack_into("<H", header, 4, fmt.version)
    struct.pack_into("<H", header, 6, grid.bands)
    struct.pack_into("<I", header, 8, grid.tile_count)
    struct.pack_into("<I", header, 12, total)
    struct.pack_into("<H", header, 16, fmt.record_bytes)
    struct.pack_into("<I", header, 20, GAIA_EPOCH_MILLIYEAR)
    struct.pack_into("<i", header, 24, int(round(magnitude * 1000)))

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "wb") as fh:
        fh.write(header)
        fh.write(struct.pack(f"<{len(index)}I", *index))
        fh.write(records)

    return {"stars": total, "tiles": grid.tile_count, "bytes": os.path.getsize(out_path)}


def verify(out_path: str, grid: Grid, fmt: Format, expected_stars: int) -> None:
    """Read back what was written, because a builder that cannot be checked is not a build step."""
    with open(out_path, "rb") as fh:
        raw = fh.read()
    assert raw[0:4] == b"SKYC", "magic is wrong"
    version, bands = struct.unpack_from("<HH", raw, 4)
    tiles, stars = struct.unpack_from("<II", raw, 8)
    record_bytes = struct.unpack_from("<H", raw, 16)[0]
    assert version == fmt.version, f"version {version}"
    assert bands == grid.bands, f"bands {bands} != {grid.bands}"
    assert tiles == grid.tile_count, f"tiles {tiles} != {grid.tile_count}"
    assert stars == expected_stars, f"stars {stars} != {expected_stars}"
    assert record_bytes == fmt.record_bytes

    index_at = fmt.header_bytes
    index = struct.unpack_from(f"<{tiles + 1}I", raw, index_at)
    assert index[0] == 0 and index[-1] == stars, "the index does not span the records"
    assert all(index[i] <= index[i + 1] for i in range(tiles)), "the index goes backwards"
    expected = index_at + (tiles + 1) * 4 + stars * record_bytes
    assert len(raw) == expected, f"file is {len(raw)} bytes, expected {expected}"

    # Spot-check: every record of a busy tile decodes inside that tile's bounds and in magnitude order.
    busiest = max(range(tiles), key=lambda t: index[t + 1] - index[t])
    ra_lo, ra_hi, dec_lo, dec_hi = grid.bounds(busiest)
    base = index_at + (tiles + 1) * 4
    previous = -1
    for i in range(index[busiest], index[busiest + 1]):
        ra_raw, dec_raw, mag, _colour, _pmra, _pmdec = struct.unpack_from(
            "<HHBBbb", raw, base + i * record_bytes
        )
        ra = (ra_lo + ra_raw / 65535.0 * (ra_hi - ra_lo)) % 360.0
        dec = dec_lo + dec_raw / 65535.0 * (dec_hi - dec_lo)
        assert grid.tile_of(ra, dec) == busiest, f"record {i} decoded outside tile {busiest}"
        assert mag >= previous, f"tile {busiest} is not sorted by magnitude at record {i}"
        previous = mag
    print(f"  verified: busiest tile {busiest} holds {index[busiest + 1] - index[busiest]} stars, in order")


def write_parity(path: str, grid: Grid) -> None:
    """
    A fixture pinning this implementation's tiling against the Kotlin's.

    ⚠️ Two implementations of one algorithm is the drift this repository has corrected seven times.
    Here the failure would be silent in the worst way: every star would decode perfectly and land in
    the wrong part of the sky.
    """
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(f"# {grid.format_key}\n")
        fh.write("# ra\tdec\ttile — written by tools/sky/build_catalogue.py --parity\n")
        n = 0
        for i in range(211):                    # a prime stride, so the sample is not on the grid
            ra = (i * 137.50776405) % 360.0
            for j in range(-89, 90, 7):
                dec = j + (i % 7) * 0.4
                dec = max(-90.0, min(89.9999, dec))
                fh.write(f"{ra:.6f}\t{dec:.6f}\t{grid.tile_of(ra, dec)}\n")
                n += 1
        # Both poles and both sides of every band edge, which is where an off-by-one lives.
        for band in range(grid.bands + 1):
            dec = max(-90.0, min(89.9999, -90.0 + band * grid.band_height))
            for ra in (0.0, 0.0001, 179.999, 180.0, 359.9999):
                fh.write(f"{ra:.6f}\t{dec:.6f}\t{grid.tile_of(ra, dec)}\n")
                n += 1
    print(f"  parity fixture: {n} positions -> {path}")


# --------------------------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--magnitude", type=float, default=12.0, help="faintest G magnitude to include")
    ap.add_argument("--out", default="core/sky/src/main/assets/sky/stars.skycat")
    ap.add_argument("--cache", default=os.path.join(ROOT, "build/skycat-cache"))
    # ⚠️ A TEST RESOURCE, not a file beside this script: loaded from the classpath, the Kotlin side
    # needs no path walking and cannot be broken by whichever directory Gradle runs a test from.
    ap.add_argument("--parity", default=os.path.join(
        ROOT, "core/telemetry/src/test/resources/sky/grid_parity.tsv"))
    ap.add_argument("--parity-only", action="store_true", help="write the fixture and stop")
    args = ap.parse_args()

    fmt = Format()
    grid = Grid()
    print(f"format v{fmt.version}, {fmt.record_bytes} bytes a star")
    print(f"grid {grid.format_key}")
    write_parity(args.parity, grid)
    if args.parity_only:
        return 0

    os.makedirs(args.cache, exist_ok=True)
    bands = [
        (-90.0 + b * grid.band_height, -90.0 + (b + 1) * grid.band_height)
        for b in range(grid.bands)
    ]
    print(f"fetching G < {args.magnitude} in {len(bands)} declination bands, {CONCURRENCY} at a time")

    rows: list[tuple] = []
    started = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        futures = {
            pool.submit(fetch_chunk, args.magnitude, lo, hi, 0.0, 360.0, args.cache): (lo, hi)
            for lo, hi in bands
        }
        done = 0
        for future in concurrent.futures.as_completed(futures):
            lo, hi = futures[future]
            got = future.result()
            rows += got
            done += 1
            print(f"  [{done}/{len(bands)}] dec {lo:+06.2f}..{hi:+06.2f}: {len(got):>7} stars "
                  f"({len(rows):>9} total, {time.time() - started:.0f}s)")

    print(f"fetched {len(rows)} stars in {time.time() - started:.0f}s")
    if not rows:
        raise SystemExit("no stars came back — refusing to write an empty catalogue")

    out_path = args.out if os.path.isabs(args.out) else os.path.join(ROOT, args.out)
    info = pack(rows, grid, fmt, args.magnitude, out_path)
    verify(out_path, grid, fmt, info["stars"])
    print(f"wrote {out_path}")
    print(f"  {info['stars']} stars, {info['tiles']} tiles, "
          f"{info['bytes'] / 1_000_000:.1f} MB "
          f"({info['bytes'] / max(1, info['stars']):.2f} bytes a star)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
