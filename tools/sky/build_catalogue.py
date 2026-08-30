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

## ⚠️ Nothing ever holds the whole catalogue in memory

A 6-float Python tuple is roughly 250 bytes, so the 36.9 million stars of a G<15 cut would be about
9 GB as one list — and the first version of this script built exactly that, then re-bucketed it into
a dict of lists so both lived at once. On a CI runner already hosting Gradle that is not a slow
build, it is a dead one. The food-database builder hit the same wall and its fix is recorded in
CLAUDE.md (`dict` 2,535 MB -> `array("i")` 968 MB).

The fix here is cheaper than typed arrays and needs no new dependency, because of one property of
the tiling that was **checked rather than assumed**: `SkyGrid` divides declination into bands and
each band into whole RA columns, so **a tile never straddles a band**. Every band is therefore an
independent packing problem. Each worker fetches its band, buckets the rows by tile as the chunks
arrive, writes that band's records to a part file and returns nothing but a list of per-tile counts;
the rows are freed when it returns. Peak memory is the densest band times the number of workers —
measured, the densest band at G<15 holds 1,159,100 stars, so about 4 x 290 MB.

`BandPacker.add` re-derives each row's tile with the shipped `Grid.tile_of` and **requires it to land
inside that band's own tile range**. That is not defensive noise: it is what turns "the fetch bands
and the grid bands line up" from a claim in a comment into something the build fails on.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import math
import mmap
import operator
import os
import re
import shutil
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

def _peak_rss_mb() -> str:
    """Peak resident memory, so a build that is quietly heading for an OOM says so before it gets there."""
    try:
        import resource                                   # POSIX only; absent on Windows
    except ImportError:                                   # pragma: no cover — not a supported host
        return "unknown (no resource module)"
    kb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return f"{kb / 1024:.0f} MB"                           # Linux reports kilobytes


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


def fetch_chunk(magnitude, dec_lo, dec_hi, ra_lo, ra_hi, cache_dir, sink, depth=0):
    """
    One chunk, split until it is provably complete, handed to `sink` a chunk at a time.

    ⚠️ **A result of exactly [SERVER_ROW_CAP] rows is treated as TRUNCATED**, because the archive
    silently caps and says nothing. On the vanishingly rare occasion that a chunk really does hold
    exactly fifty thousand stars, splitting it costs one extra request and loses nothing.

    ⚠️ It hands each leaf chunk to `sink` and returns only a COUNT. The recursion used to concatenate
    its children's lists, which meant a whole band existed twice at the moment the last child
    returned; a sink lets each chunk's rows be bucketed and released as they arrive.
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
        sink(rows)
        return len(rows)

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
    got = 0
    for d0, d1, r0, r1 in halves:
        got += fetch_chunk(magnitude, d0, d1, r0, r1, cache_dir, sink, depth + 1)
    return got


# --------------------------------------------------------------------------------------------
# writing
# --------------------------------------------------------------------------------------------

class BandPacker:
    """
    One declination band's rows, bucketed by tile as the chunks arrive and written out on its own.

    ⚠️ **This is only correct because a tile never straddles a band**, which is a property of
    `SkyGrid` rather than a hope: bands are cut first and each is divided into a whole number of RA
    columns, so `Grid.start[band] .. Grid.start[band + 1]` is exactly the tiles a band can hold.
    `add` re-derives every row's tile through the shipped `Grid.tile_of` — the same function the
    parity fixture pins against the Kotlin — and refuses anything landing outside that range, so the
    alignment between the fetch's declination bounds and the grid's is checked on every star rather
    than asserted in prose.
    """

    def __init__(self, band: int, grid: Grid, fmt: Format) -> None:
        self.band = band
        self.grid = grid
        self.fmt = fmt
        self.lo = grid.start[band]
        self.hi = grid.start[band + 1]
        self.buckets: list[list] | None = [[] for _ in range(self.hi - self.lo)]
        self.rows = 0

    def add(self, rows: list[tuple]) -> None:
        lo, hi = self.lo, self.hi
        tile_of = self.grid.tile_of
        buckets = self.buckets
        for row in rows:
            tile = tile_of(row[0], row[1])
            if not lo <= tile < hi:
                raise SystemExit(
                    f"band {self.band} fetched a star at ra={row[0]} dec={row[1]} which the grid "
                    f"places in tile {tile}, outside this band's tiles {lo}..{hi - 1} — the fetch "
                    f"bands and SkyGrid's bands have stopped lining up"
                )
            buckets[tile - lo].append(row)
        self.rows += len(rows)

    def write(self, path: str) -> list[int]:
        """Write this band's records in tile order and return the per-tile counts."""
        buckets = self.buckets
        counts = [0] * len(buckets)
        pack_record = struct.Struct("<HHBBbb").pack
        # ⚠️ **Magnitude, THEN position, and the tiebreak is what makes a build reproducible.**
        # The archive is asked a query with no ORDER BY, so a parallel execution plan may return the
        # same rows in a different order between identical runs — measured, not assumed: the same
        # small query answered twice differed at row 0, with an identical multiset. `list.sort` is
        # stable, so without a tiebreak that upstream order leaks straight into the file and two
        # builds of one frozen catalogue differ byte for byte. It was found exactly that way: a
        # rebuild of G<12 matched the committed catalogue in size, star count and tile index, and
        # differed in 190 of 5,370 tiles — every one of them holding precisely the same records in a
        # different order. Sorting on the server instead would mean an ORDER BY over a 36.9-million
        # row scan, which is a far worse thing to ask of a research archive. The keys are built per
        # TILE rather than per band, so the cost is a few megabytes at the very largest tile.
        by_magnitude = operator.itemgetter(2, 0, 1)
        fmt = self.fmt
        with open(path, "wb") as fh:
            out = bytearray()
            for i, bucket in enumerate(buckets):
                # ⚠️ Brightest first WITHIN a tile, which is what lets the reader stop early at the
                # view's magnitude cut instead of decoding the whole tile.
                bucket.sort(key=by_magnitude)
                ra_lo, ra_hi, dec_lo, dec_hi = self.grid.bounds(self.lo + i)
                ra_span = ra_hi - ra_lo
                dec_span = dec_hi - dec_lo
                for ra, dec, mag, bp_rp, pmra, pmdec in bucket:
                    out += pack_record(
                        fmt.fraction(((ra % 360.0) - ra_lo) / ra_span),
                        fmt.fraction((dec - dec_lo) / dec_span),
                        fmt.magnitude(mag),
                        fmt.colour(bp_rp),
                        fmt.proper_motion(pmra),
                        fmt.proper_motion(pmdec),
                    )
                counts[i] = len(bucket)
                buckets[i] = []                    # release this tile's rows as we pass it
                if len(out) >= 1 << 22:
                    fh.write(out)
                    out = bytearray()
            fh.write(out)
        self.buckets = None
        total = sum(counts)
        if total != self.rows:
            raise SystemExit(f"band {self.band} packed {total} records from {self.rows} rows")
        return counts


def fetch_and_pack(magnitude: float, band: int, grid: Grid, fmt: Format,
                   cache_dir: str, parts_dir: str) -> tuple[int, list[int]]:
    """
    A whole band, fetched and packed inside one worker.

    ⚠️ Fetching and packing are ONE task on purpose. A `Future` holds its result until the future
    itself is collected, so a worker that returned rows would keep every completed band's stars alive
    for the length of the run — which is the memory this whole rewrite exists to avoid. Nothing
    leaves here but a list of per-tile counts.
    """
    lo = -90.0 + band * grid.band_height
    hi = lo + grid.band_height
    packer = BandPacker(band, grid, fmt)
    fetch_chunk(magnitude, lo, hi, 0.0, 360.0, cache_dir, packer.add)
    return band, packer.write(os.path.join(parts_dir, f"band{band:03d}.bin"))


def assemble(per_band_counts: list[list[int]], parts_dir: str, grid: Grid, fmt: Format,
             magnitude: float, out_path: str) -> dict:
    """Header, index and every band's part file concatenated in band order."""
    index = []
    running = 0
    for band in range(grid.bands):
        for count in per_band_counts[band]:
            index.append(running)
            running += count
    index.append(running)
    if len(index) != grid.tile_count + 1:
        raise SystemExit(f"index has {len(index)} entries, expected {grid.tile_count + 1}")
    total = running

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
        for band in range(grid.bands):
            with open(os.path.join(parts_dir, f"band{band:03d}.bin"), "rb") as part:
                shutil.copyfileobj(part, fh, 1 << 20)

    return {"stars": total, "tiles": grid.tile_count, "bytes": os.path.getsize(out_path)}


def verify(out_path: str, grid: Grid, fmt: Format, expected_stars: int) -> None:
    """
    Read back what was written, because a builder that cannot be checked is not a build step.

    ⚠️ Memory-mapped rather than read whole: at G<15 the file is ~295 MB and this runs on the same
    machine that has just finished packing it. Mapping is also what the app itself does, so the check
    exercises the real access pattern.
    """
    with open(out_path, "rb") as fh, mmap.mmap(fh.fileno(), 0, access=mmap.ACCESS_READ) as raw:
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

        # Spot-check: every record of a busy tile decodes inside that tile's bounds, in magnitude order.
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
        held = index[busiest + 1] - index[busiest]
    print(f"  verified: busiest tile {busiest} holds {held} stars, in order")


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
    ap.add_argument("--parts", default=os.path.join(ROOT, "build/skycat-parts"),
                    help="scratch directory for the per-band record files; cleared on every run")
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
    # ⚠️ Cleared rather than reused. A part file left by an interrupted run is indistinguishable
    # from one this run wrote, and concatenating a stale band would produce a catalogue that
    # verifies perfectly and holds last time's stars in that strip of sky.
    parts_dir = args.parts if os.path.isabs(args.parts) else os.path.join(ROOT, args.parts)
    shutil.rmtree(parts_dir, ignore_errors=True)
    os.makedirs(parts_dir, exist_ok=True)

    print(f"fetching G < {args.magnitude} in {grid.bands} declination bands, {CONCURRENCY} at a time")

    per_band_counts: list[list[int] | None] = [None] * grid.bands
    fetched = 0
    started = time.time()
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
            futures = [
                pool.submit(fetch_and_pack, args.magnitude, band, grid, fmt, args.cache, parts_dir)
                for band in range(grid.bands)
            ]
            done = 0
            for future in concurrent.futures.as_completed(futures):
                band, counts = future.result()
                per_band_counts[band] = counts
                got = sum(counts)
                fetched += got
                done += 1
                lo = -90.0 + band * grid.band_height
                print(f"  [{done}/{grid.bands}] dec {lo:+06.2f}..{lo + grid.band_height:+06.2f}: "
                      f"{got:>8} stars ({fetched:>9} total, {time.time() - started:.0f}s)")

        print(f"fetched and packed {fetched} stars in {time.time() - started:.0f}s")
        if not fetched:
            raise SystemExit("no stars came back — refusing to write an empty catalogue")

        out_path = args.out if os.path.isabs(args.out) else os.path.join(ROOT, args.out)
        info = assemble(per_band_counts, parts_dir, grid, fmt, args.magnitude, out_path)
    finally:
        shutil.rmtree(parts_dir, ignore_errors=True)

    verify(out_path, grid, fmt, info["stars"])
    print(f"wrote {out_path}")
    print(f"  {info['stars']} stars, {info['tiles']} tiles, "
          f"{info['bytes'] / 1_000_000:.1f} MB "
          f"({info['bytes'] / max(1, info['stars']):.2f} bytes a star)")
    print(f"  peak memory: {_peak_rss_mb()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
