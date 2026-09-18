#!/usr/bin/env python3
"""Prove a packed star catalogue is the one that was asked for — on disk, or inside an APK.

    python3 tools/sky/check_packaged.py --catalogue core/sky/.../stars.skycat --magnitude 15
    python3 tools/sky/check_packaged.py --apk app/build/.../release/app-release.apk --magnitude 15

⚠️ **This exists because three workflows need the same eight facts and a shell copy in each of them
is how they drift.** The Android build, the standalone star map's build and the composite action
that produces the file all have to answer "is this the right catalogue, whole, and mappable?", and
the interesting half of that question is in a 32-byte header rather than in a file listing.

## What it actually catches, said honestly

* **The wrong depth.** A restored or committed G<12 catalogue in a G<15 build compiles, packages,
  installs and draws a perfectly plausible sky that simply stops early. The header records the
  magnitude the builder was asked for, so this is caught in a byte comparison.
* **Deflated packaging.** `SkyCatalogSource` explains at length that a compressed asset cannot be
  memory-mapped, so the whole file would land on the heap of the cheapest phone this is meant to run
  on. The cost of forgetting `noCompress` is not a crash — it is the map working, slowly, using far
  more memory than it should, which is exactly why it needs a gate rather than a comment.
* **Gross truncation.** A catalogue that lost most of its stars.

⚠️ **What it does NOT catch, and nothing here pretends otherwise: a single lost chunk.** The archive
serves at most 50,000 rows a request, which at G<15 is 0.14% of the catalogue — far inside any
tolerance a floor can carry without failing on ordinary variation. Completeness is the builder's own
job (a chunk at exactly the row cap is treated as truncated and split; every band's records are
counted twice; the index is required to span the file) and this is a check on identity, not on that.
"""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile

ASSET = "assets/sky/stars.skycat"
HEADER_BYTES = 32
MAGIC = b"SKYC"

# ⚠️ Measured live against the ESA Gaia archive with `SELECT COUNT(*) ... WHERE phot_g_mean_mag < m`,
# not recalled. DR3 is a frozen release, so these are exact and cannot move underneath us. The floor
# applied below is a fraction of these rather than the numbers themselves — see the note above about
# what a count gate can and cannot prove.
MEASURED_COUNTS = {
    12.0: 3_087_821,
    14.0: 16_844_156,
    15.0: 36_909_335,
}
FLOOR_FRACTION = 0.97


def read_header(data: bytes, where: str) -> tuple[int, int, int, float]:
    if len(data) < HEADER_BYTES:
        sys.exit(f"FAIL: {where} is only {len(data)} bytes — that is not a catalogue at all.")
    if data[0:4] != MAGIC:
        sys.exit(f"FAIL: {where} does not begin with SKYC — it is not a packed catalogue.")
    version, bands = struct.unpack_from("<HH", data, 4)
    tiles, stars = struct.unpack_from("<II", data, 8)
    magnitude = struct.unpack_from("<i", data, 24)[0] / 1000.0
    return version, tiles, stars, magnitude


def check(version: int, tiles: int, stars: int, magnitude: float,
          wanted: float, where: str) -> None:
    if abs(magnitude - wanted) > 0.0005:
        sys.exit(
            f"FAIL: {where} was built for G < {magnitude}, but this build wants G < {wanted}.\n"
            f"      A shallower catalogue draws a perfectly plausible sky that simply stops early,\n"
            f"      so nothing downstream would notice. Check the cache key carries the magnitude."
        )
    expected = MEASURED_COUNTS.get(wanted)
    if expected is None:
        print(f"  note: no measured star count on record for G < {wanted}; count not checked")
    else:
        floor = int(expected * FLOOR_FRACTION)
        if stars < floor:
            sys.exit(
                f"FAIL: {where} holds {stars:,} stars; G < {wanted} should hold about "
                f"{expected:,}.\n      That is well under the {FLOOR_FRACTION:.0%} floor — this "
                f"catalogue is truncated or is not the one it claims to be."
            )
    print(f"  {where}: v{version}, {tiles:,} tiles, {stars:,} stars, built for G < {magnitude}")


def from_apk(path: str, wanted: float) -> tuple[int, int, int, float]:
    with zipfile.ZipFile(path) as zf:
        try:
            info = zf.getinfo(ASSET)
        except KeyError:
            names = [n for n in zf.namelist() if n.startswith("assets/sky/")]
            sys.exit(
                f"FAIL: {ASSET} is not in the APK.\n"
                f"      Sky assets that DID make it: {', '.join(names) or '(none at all)'}"
            )
        # ⚠️ Stored, not deflated. See the module docstring: a compressed asset cannot be mapped,
        # and the `noCompress` declaration CANNOT live in the library because packaging belongs to
        # whichever module builds the APK — so every application bundling this has to say it
        # separately, and forgetting is silent.
        if info.compress_type != zipfile.ZIP_STORED:
            sys.exit(
                f"FAIL: {ASSET} is packaged compressed (method {info.compress_type}), not stored.\n"
                f"      The `androidResources {{ noCompress += \"skycat\" }}` declaration in this\n"
                f"      module's build.gradle.kts is missing or has stopped matching."
            )
        with zf.open(info) as fh:
            return read_header(fh.read(HEADER_BYTES), ASSET)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--catalogue", help="a packed catalogue on disk")
    ap.add_argument("--apk", help="an APK that should contain one")
    ap.add_argument("--magnitude", type=float, required=True, help="the depth this build asked for")
    ap.add_argument("--github-output", help="append `stars=N` to this file, for a step output")
    args = ap.parse_args()

    if bool(args.catalogue) == bool(args.apk):
        sys.exit("give exactly one of --catalogue or --apk")

    if args.apk:
        version, tiles, stars, magnitude = from_apk(args.apk, args.magnitude)
        where = ASSET
    else:
        where = args.catalogue
        try:
            with open(where, "rb") as fh:
                header = fh.read(HEADER_BYTES)
        except OSError as exc:
            sys.exit(f"FAIL: could not read {where}: {exc}")
        version, tiles, stars, magnitude = read_header(header, where)

    check(version, tiles, stars, magnitude, args.magnitude, where)
    if args.github_output:
        with open(args.github_output, "a", encoding="utf-8") as fh:
            fh.write(f"stars={stars}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
