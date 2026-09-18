#!/usr/bin/env python3
"""Build the deep-sky asset: galaxies, clusters and nebulae from OpenNGC.

    curl -o /tmp/NGC.csv https://raw.githubusercontent.com/mattiaverga/OpenNGC/master/database_files/NGC.csv
    curl -o /tmp/addendum.csv https://raw.githubusercontent.com/mattiaverga/OpenNGC/master/database_files/addendum.csv
    python3 tools/sky/build_deepsky.py /tmp/NGC.csv /tmp/addendum.csv \
        core/sky/src/main/assets/sky/deepsky.tsv

WHY A LEAN TSV AND NOT A PACKED BINARY
--------------------------------------
The plan reached for the packed, HEALPix-sorted, memory-mapped format the star catalogue uses. It
was measured instead: the ten columns below come to 638 kB raw and **236 kB deflated**, which is
cheaper than the packed estimate and needs no record layout, no `noCompress`, no mmap and no spatial
index. Twelve and a half thousand objects is a linear scan per frame — the same shape
ConstellationSource already uses for a 65 kB JSON.

  ⚠️ That is not true of the STARS, and the difference is three orders of magnitude: 3.09 million
  records cannot be scanned per frame and cannot be held as text. Do not read this file as an
  argument against stars.skycat.

WHICH ROWS ARE DROPPED, AND WHY EACH
------------------------------------
Of OpenNGC's 14,034 rows, 1,455 are removed:

    Dup    652   an entry that turned out to be another entry seen twice
    *      546   a single star, which the star catalogue already draws
    **     244   a double star, likewise
    NonEx   10   an object later shown not to exist
    Nova     3   a transient, which by definition is not there now

  ⚠️ Nothing is dropped for a MISSING POSITION, because none is missing — all 12,579 survivors carry
  RA and Dec. That was checked rather than assumed; a builder that silently discarded a tenth of the
  sky would still write a file that parses.

WHY THE MAGNITUDE CARRIES ITS BAND
----------------------------------
⚠️ **B-Mag is available where V-Mag is not, by a factor of nearly three**: 11,303 rows carry B and
only 4,218 carry V, with just 161 having V and no B. Preferring V and falling back to B is therefore
not a tidy-up — it is the difference between 4,218 objects with a brightness and 11,464.

But B and V are different photometric bands, and for the red populations that dominate this
catalogue B is the fainter number. Silently mixing them into one column would put a systematic bias
into whatever cut the app applies, invisibly. So the band travels with the value and the app decides
what to do about it. **No correction is applied here** — converting one band to the other needs a
colour this catalogue does not measure for these objects, and inventing one would put a claim where
no measurement exists.

WHAT IS KEPT VERBATIM
---------------------
The type string is OpenNGC's own, not a normalised enum. Mapping seventeen source types onto the
handful of shapes a renderer draws is a decision with edge cases (is a `Cl+N` a cluster or a
nebula?), and it belongs in the tested core where it can be argued with, not in a Python script whose
output nothing checks.
"""

from __future__ import annotations

import csv
import math
import sys
from pathlib import Path

# Positions are written with this many decimal places. 1e-5 degrees is 0.036 arcseconds, far finer
# than any deep-sky position is known and far finer than one screen pixel at any field the map
# allows; the cost is a few characters a row.
POS_DP = 5

# Rows that are not an object to draw. Each is here for its own reason — see the module docstring.
DROP_TYPES = {"Dup", "*", "**", "NonEx", "Nova"}

# ⚠️ Every type OpenNGC currently emits, so a NEW one added upstream fails this build rather than
# arriving unnoticed and being drawn as whatever the core's `else` branch happens to be.
KNOWN_TYPES = {
    "G", "GPair", "GTrpl", "GGroup",          # galaxies and galaxy systems
    "GCl", "OCl", "Cl+N", "*Ass",             # clusters and associations
    "PN", "Neb", "RfN", "EmN", "HII", "SNR",  # nebulae
    "DrkN",                                   # dark nebulae — absorption, not emission
    "Other",                                  # OpenNGC could not classify it
}

# The object this run re-derives to prove itself. M31 is the anchor because an hours-read-as-degrees
# mistake would put it at 0.71 degrees instead of 10.68, and a shifted column would put it nowhere
# near either. Published J2000: 00h42m44.3s, +41d16'09".
#
#   ⚠️ The bar is loose ON PURPOSE. This is a PARSE guard, not an astrometry check: OpenNGC's own
#   position for a large galaxy legitimately differs from any particular published centre by
#   arcseconds. Five is far tighter than any parse fault and far looser than any real disagreement.
ANCHOR_ID = "NGC0224"
ANCHOR_RA = 10.68471
ANCHOR_DEC = 41.26875
ANCHOR_TOLERANCE_ARCSEC = 5.0

# ⚠️ The famous objects, asserted present WITH a brightness. This is the build-time half of the
# surface-brightness trap: cutting this catalogue on surface brightness rather than magnitude leads
# with anonymous thirteenth-magnitude galaxies and leaves out Andromeda, and the two orderings
# overlap by eight of their top two hundred. Anything that silently loses these has gone wrong.
MUST_HAVE = {
    "NGC0224": "M31 Andromeda",
    "NGC1976": "M42 Orion Nebula",
    "NGC6205": "M13 Hercules Cluster",
    "Mel022": "M45 Pleiades",
    "NGC6720": "M57 Ring Nebula",
    "NGC4594": "M104 Sombrero",
    "NGC5194": "M51 Whirlpool",
    "NGC0292": "Small Magellanic Cloud",
}


def read_rows(path: Path) -> list[dict[str, str]]:
    """OpenNGC ships semicolon-separated CSV with a header row."""
    with path.open(newline="", encoding="utf-8") as fh:
        return list(csv.DictReader(fh, delimiter=";"))


def sexagesimal_ra(text: str) -> float:
    """`00:42:44.30` is RIGHT ASCENSION IN HOURS, so it is fifteen degrees to the hour."""
    h, m, s = (float(p) for p in text.split(":"))
    return (h + m / 60.0 + s / 3600.0) * 15.0


def sexagesimal_dec(text: str) -> float:
    """`+41:16:09.4` is degrees, and the sign belongs to the WHOLE value, not just the degrees."""
    sign = -1.0 if text.lstrip().startswith("-") else 1.0
    d, m, s = (float(p) for p in text.lstrip("+- ").split(":"))
    return sign * (d + m / 60.0 + s / 3600.0)


def number(text: str) -> float | None:
    text = text.strip()
    if not text:
        return None
    try:
        return float(text)
    except ValueError:
        return None


def label_for(row: dict[str, str]) -> str:
    """What to write under the object, or nothing.

    ⚠️ Only 227 of 12,579 get one, and that is the point: a chart that labels every catalogue number
    is unreadable. A common name is preferred over the Messier number because it is what the name
    is FOR — "Ring Nebula" tells a reader something "M57" does not.
    """
    common = row["Common names"].split(",")[0].strip()
    if common:
        return common
    messier = row["M"].strip()
    if messier:
        try:
            return f"M{int(messier)}"
        except ValueError:
            return ""
    return ""


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print(__doc__)
        return 2
    ngc_path, addendum_path, out_path = (Path(p) for p in argv[1:])

    source = read_rows(ngc_path) + read_rows(addendum_path)
    print(f"source rows  {len(source)}")

    kept = [r for r in source if r["Type"] not in DROP_TYPES]
    out: list[list[str]] = []
    problems: list[str] = []
    seen: set[str] = set()
    v_band = b_band = 0

    for row in kept:
        ident = row["Name"].strip()
        ra_text, dec_text = row["RA"].strip(), row["Dec"].strip()
        if not ident or not ra_text or not dec_text:
            problems.append(f"{ident or '<unnamed>'} has no identifier or no position")
            continue
        if ident in seen:
            problems.append(f"duplicate identifier {ident}")
            continue
        seen.add(ident)

        ra = sexagesimal_ra(ra_text) % 360.0
        dec = sexagesimal_dec(dec_text)

        # V first because it is the visual magnitude — what an eye at an eyepiece sees. B is the
        # fallback and is recorded as such; see the module docstring.
        mag, band = number(row["V-Mag"]), "V"
        if mag is None:
            mag, band = number(row["B-Mag"]), "B"
        if mag is None:
            band = ""
        elif band == "V":
            v_band += 1
        else:
            b_band += 1

        maj, minor = number(row["MajAx"]), number(row["MinAx"])
        pa = number(row["PosAng"])

        out.append([
            ident,
            row["Type"],
            f"{ra:.{POS_DP}f}",
            f"{dec:.{POS_DP}f}",
            "" if mag is None else f"{mag:.2f}",
            band,
            "" if maj is None else f"{maj:.2f}",
            "" if minor is None else f"{minor:.2f}",
            "" if pa is None else f"{pa:.0f}",
            label_for(row).replace("\t", " "),
        ])

    # ---- guards --------------------------------------------------------------------------------
    # ⚠️ Every one of these has a silent failure mode. A sexagesimal field read as degrees, a shifted
    # column, an upstream type nobody mapped: each produces a file that parses perfectly and
    # describes the wrong sky.

    # NOTHING-WAS-PRODUCED comes first, because it is the failure every other guard here is blind
    # to — a parser that drops most rows writes a file that is valid, small and quietly incomplete.
    if len(out) != len(kept):
        problems.append(f"wrote {len(out)} of {len(kept)} kept rows — the rest were dropped")
    if len(out) < 12_000:
        problems.append(f"only {len(out)} objects survived; the catalogue holds over twelve thousand")

    for r in out:
        if r[1] not in KNOWN_TYPES:
            problems.append(f"{r[0]} has type {r[1]!r}, which this builder has never seen")

    ras = [float(r[2]) for r in out]
    decs = [float(r[3]) for r in out]
    # ⚠️ These two catch the likeliest mistake, which the obvious range check cannot: `00:42:44.30`
    # is HOURS, and a reader that forgets the fifteen degrees to the hour yields values in 0..24 —
    # inside 0..360, inside every other guard, and describing a sky squeezed into a fifteenth of
    # itself.
    if max(ras) - min(ras) < 350.0:
        problems.append(
            f"right ascensions span only {max(ras) - min(ras):.1f} deg; a span near 24 means the "
            "hours-to-degrees conversion was lost"
        )
    if max(decs) - min(decs) < 160.0:
        problems.append(f"declinations span only {max(decs) - min(decs):.1f} deg")
    for r in out:
        if not (0.0 <= float(r[2]) < 360.0):
            problems.append(f"{r[0]} right ascension off range: {r[2]}")
        if not (-90.0 <= float(r[3]) <= 90.0):
            problems.append(f"{r[0]} declination off range: {r[3]}")

    for r in out:
        maj = float(r[6]) if r[6] else None
        minor = float(r[7]) if r[7] else None
        # A minor axis longer than the major one means the two columns were read the wrong way
        # round, which would draw every galaxy at ninety degrees to its real orientation.
        if maj is not None and minor is not None and minor > maj + 1e-9:
            problems.append(f"{r[0]} has minor axis {minor} longer than major {maj}")
        if maj is not None and not (0.0 < maj <= 700.0):
            problems.append(f"{r[0]} major axis {maj}' is not a plausible angular size")
        if r[8] and not (0.0 <= float(r[8]) <= 180.0):
            problems.append(f"{r[0]} position angle {r[8]} is outside 0..180")
        if r[4] and r[5] not in ("V", "B"):
            problems.append(f"{r[0]} has a magnitude with band {r[5]!r}")
        if r[5] and not r[4]:
            problems.append(f"{r[0]} names a band with no magnitude")

    by_id = {r[0]: r for r in out}
    if ANCHOR_ID in by_id:
        anchor = by_id[ANCHOR_ID]
        ra, dec = float(anchor[2]), float(anchor[3])
        cos_dec = math.cos(math.radians(dec))
        off = math.hypot((ra - ANCHOR_RA) * cos_dec, dec - ANCHOR_DEC) * 3600.0
        if off > ANCHOR_TOLERANCE_ARCSEC:
            problems.append(
                f"{ANCHOR_ID} is {off:.1f}\" from its published J2000 position "
                f"({ANCHOR_RA}, {ANCHOR_DEC}) — a column offset or the hours conversion is wrong"
            )
    else:
        problems.append(f"the anchor object {ANCHOR_ID} is not in the output")

    for ident, what in MUST_HAVE.items():
        row = by_id.get(ident)
        if row is None:
            problems.append(f"{what} ({ident}) is missing from the output")
        elif not row[4]:
            problems.append(f"{what} ({ident}) has no magnitude, so no cut can keep it")

    labelled = sum(1 for r in out if r[9])
    if labelled < 200:
        problems.append(f"only {labelled} objects carry a label; expected at least two hundred")

    if problems:
        for p in problems[:20]:
            print(f"  FAIL {p}", file=sys.stderr)
        print(f"{len(problems)} problem(s); nothing written", file=sys.stderr)
        return 1

    header = [
        "# id\ttype\tra\tdec\tmag\tband\tmajax\tminax\tpa\tname",
        "# OpenNGC (Mattia Verga), CC-BY-SA-4.0. J2000. Axes in arcminutes, angles in degrees.",
        "# mag is V where measured and B otherwise; `band` says which. See NOTICE.txt.",
    ]
    body = "\n".join("\t".join(r) for r in out)
    out_path.write_text("\n".join(header) + "\n" + body + "\n", encoding="utf-8")

    with_mag = sum(1 for r in out if r[4])
    with_size = sum(1 for r in out if r[6])
    print(f"objects      {len(out)}  (dropped {len(source) - len(kept)} of {len(source)})")
    print(f"magnitudes   {with_mag}  ({v_band} V, {b_band} B)")
    print(f"sizes        {with_size}")
    print(f"labels       {labelled}")
    print(f"wrote        {out_path}  {out_path.stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
