#!/usr/bin/env python3
"""Build the bundled offline barcode database.

Turns the Open Food Facts export and the USDA branded-food set into ONE SQLite file that answers a
scanned barcode with no network at all.

    tools/food/build_food_db.py --work DIR [--out PATH] [--limit N]

## Why this exists

`FoodRepository.byBarcode` used to delegate straight to the network, so no signal meant no scan.
The bundled seed (`build_seed.py`, 13k generic foods) has never carried a barcode at all. This is
the other half: ~4.4M real retail products, keyed so the phone can find them offline.

## The key, and why it is an integer

⚠️ **This must apply exactly the rule in `BarcodeScan.normalize`, or the app silently finds
nothing.** The same product is `038000138416` on a US packet and `0038000138416` in a European
database — one leading zero apart. As text they are two rows and a large share of US scans miss; as
numbers they are one. `barcode INTEGER PRIMARY KEY` also makes the table its own B-tree, so there
is no second index to store: measured at 56.5 bytes a row against ~100 with a text key and an index.

The Kotlin side is the source of truth for that rule; `normalize` below is its twin and the two are
pinned by the same test vectors.

## Every barcode gets a row

Measured on the real export: 95% of products have a name and only about a fifth have nutrition.
Keeping only the complete ones would throw away four scans in five. A row with a name and no
numbers still lets the app say *"Kellogg's Corn Flakes — nobody recorded the numbers, tap to add"*,
which is a different thing entirely from *"unknown barcode"*. `have_nutrition` marks which is which.

## ⚠️ The sodium trap

Open Food Facts publishes `sodium_100g` in **grams**. USDA publishes it in **milligrams**. Getting
this backwards is a silent 1000x error in a number the coach acts on, and it would look plausible
on screen. Each reader converts at its own boundary and `--verify` asserts the result is sane.

## Licensing

Open Food Facts is ODbL: attribution and share-alike on the derived database. USDA FoodData Central
is public domain. Both are recorded in the `meta` table so the app can display them.
"""

from __future__ import annotations

import argparse
import csv
import gzip
import io
import os
import sqlite3
import sys
import time
import zipfile
from pathlib import Path

# ---------------------------------------------------------------------------------------------
# The key. Twin of BarcodeScan.normalize / BarcodeScan.checkDigitValid.
# ---------------------------------------------------------------------------------------------

PRODUCT_LENGTHS = {8, 12, 13, 14}


def normalize(raw: str) -> int | None:
    """The one key a barcode maps to. MUST match BarcodeScan.normalize exactly."""
    digits = "".join(c for c in raw if c.isdigit())
    if len(digits) not in PRODUCT_LENGTHS:
        return None
    return int(digits)


def check_digit_valid(raw: str) -> bool:
    """GS1 mod-10. Weights from the RIGHT cover every length without a special case."""
    digits = "".join(c for c in raw if c.isdigit())
    if len(digits) not in PRODUCT_LENGTHS:
        return False
    total = 0
    for i, ch in enumerate(reversed(digits[:-1])):
        total += int(ch) * (3 if i % 2 == 0 else 1)
    return (10 - total % 10) % 10 == int(digits[-1])


# ---------------------------------------------------------------------------------------------
# Schema
# ---------------------------------------------------------------------------------------------

# ⚠️ Nutrients are stored as scaled INTEGERs, not REALs. A REAL is 8 bytes always; a small integer
# is 1-2, and SQLite stores NULL in the record header for ~1. Across 4.4M rows where most nutrient
# columns are empty that is the difference between a database that fits in an APK and one that does
# not. Scales are chosen so the stored value is exact for the precision the sources actually
# publish: energy to 1 kcal, macros to 0.1 g, sodium to 1 mg, micros to their own natural unit.
SCALE_KCAL = 1
SCALE_MACRO = 10        # grams x10  -> 0.1 g
SCALE_SODIUM = 1        # already milligrams
SCALE_MICRO = 100       # milligram-scale micros x100
SCALE_MICROGRAM = 1     # microgram-scale micros, stored as-is

SOURCE_OFF = 1
SOURCE_USDA = 2

SCHEMA = """
PRAGMA page_size = 4096;
PRAGMA journal_mode = OFF;
PRAGMA synchronous = OFF;

CREATE TABLE food (
  barcode   INTEGER PRIMARY KEY,   -- the rowid: the table IS the index
  name      TEXT,
  brand     TEXT,
  kcal      INTEGER,
  prot      INTEGER,
  carb      INTEGER,
  fat       INTEGER,
  fib       INTEGER,
  sug       INTEGER,
  sat       INTEGER,
  sod       INTEGER,
  -- micronutrients, all optional and mostly absent
  calcium   INTEGER,
  iron      INTEGER,
  potassium INTEGER,
  vit_a     INTEGER,
  vit_c     INTEGER,
  vit_d     INTEGER,
  chol      INTEGER,
  transfat  INTEGER,
  serv_g    INTEGER,
  serv_label TEXT,
  pack_g    INTEGER,
  src       INTEGER NOT NULL
);

CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
"""

COLUMNS = (
    "barcode name brand kcal prot carb fat fib sug sat sod "
    "calcium iron potassium vit_a vit_c vit_d chol transfat serv_g serv_label pack_g src"
).split()

INSERT = f"INSERT OR REPLACE INTO food ({','.join(COLUMNS)}) VALUES ({','.join('?' * len(COLUMNS))})"

MAX_NAME = 90
MAX_BRAND = 60


def scaled(raw: str | float | None, scale: int, ceiling: float = 100_000.0) -> int | None:
    """A nutrient, or None. Absurd values are dropped rather than shown as fact."""
    if raw is None or raw == "":
        return None
    try:
        v = float(raw)
    except (TypeError, ValueError):
        return None
    if v != v or v < 0.0 or v > ceiling:  # NaN, negative, or nonsense
        return None
    return int(round(v * scale))



# ⚠️ Unit words a serving LABEL may be made entirely of, in which case it says nothing the gram
# figure beside it does not. Includes the GS1 codes Open Food Facts carries verbatim — ONZ is an
# ounce and OZA a fluid ounce, and both render as gibberish to a reader.
LABEL_UNITS = {
    "g", "gr", "gm", "gms", "gram", "grams", "kg", "mg", "ml", "cl", "l", "litre", "litres",
    "liter", "liters", "oz", "ozs", "ounce", "ounces", "lb", "lbs", "fl", "onz", "oza", "grm",
}

MAX_LABEL = 40


def serving_label(raw: str | None) -> str | None:
    """A household description of one serving — "1 CAN (355 ml)" — or None.

    ⚠️ **A label made only of units is dropped, and that is the whole rule.** Open Food Facts'
    `serving_size` is free text and more than half of it is simply the mass again: rendering
    "1 serving (30 g)" with "30.0g" underneath is the same number said twice, which reads as a bug.
    What earns its place is a label naming the THING — a can, a slice, two tablespoons — because
    that is how somebody actually decides what they ate.
    """
    t = clean(raw, MAX_LABEL)
    if not t:
        return None
    words = [w for w in "".join(c if (c.isalpha() or c.isspace()) else " " for c in t).split()]
    if not words or all(w.lower() in LABEL_UNITS for w in words):
        return None
    return t


def mass(raw: str | float | None, ceiling: float) -> int | None:
    """A weight in whole grams, or None. ⚠️ Zero is None, not zero.

    Open Food Facts publishes `product_quantity` as 0 for a great many records, and a package that
    weighs nothing is not a package — carried through, it would put a "1 package" portion in the
    picker that resolves to no food at all.
    """
    v = scaled(raw, 1, ceiling=ceiling)
    return v if v and v > 0 else None


def clean(s: str | None, limit: int) -> str | None:
    if not s:
        return None
    t = " ".join(s.split())[:limit].strip()
    return t or None


class Row:
    """One product, ready to insert. Ordered to match COLUMNS."""

    __slots__ = COLUMNS

    def __init__(self, barcode: int, src: int):
        for c in COLUMNS:
            setattr(self, c, None)
        self.barcode = barcode
        self.src = src

    def values(self):
        return tuple(getattr(self, c) for c in COLUMNS)

    @property
    def has_nutrition(self) -> bool:
        return self.kcal is not None and self.kcal > 0


# ---------------------------------------------------------------------------------------------
# Open Food Facts
# ---------------------------------------------------------------------------------------------

OFF_FIELDS = {
    "code", "product_name", "brands", "serving_quantity", "serving_size", "product_quantity",
    "energy-kcal_100g", "energy_100g", "proteins_100g", "carbohydrates_100g", "fat_100g",
    "fiber_100g", "sugars_100g", "saturated-fat_100g", "sodium_100g", "salt_100g",
    "calcium_100g", "iron_100g", "potassium_100g", "vitamin-a_100g", "vitamin-c_100g",
    "vitamin-d_100g", "cholesterol_100g", "trans-fat_100g",
}

KJ_PER_KCAL = 4.184


def read_off(path: Path, limit: int | None, log):
    """Stream the OFF export. ⚠️ Never decompressed to disk — it is ~12.4 GB raw."""
    csv.field_size_limit(1 << 30)
    seen = 0
    with gzip.open(path, "rt", encoding="utf-8", errors="replace", newline="") as fh:
        reader = csv.reader(fh, delimiter="\t", quoting=csv.QUOTE_NONE)
        header = next(reader)
        ix = {name: i for i, name in enumerate(header) if name in OFF_FIELDS}
        missing = OFF_FIELDS - set(ix)
        # Not fatal: OFF adds and retires columns, and a missing micronutrient should cost that
        # column rather than the whole build. It is reported so a silent loss cannot happen.
        if missing:
            log(f"  note: OFF export has no column for {sorted(missing)}")

        def g(rec, name):
            i = ix.get(name, -1)
            return rec[i].strip() if 0 <= i < len(rec) else ""

        for rec in reader:
            seen += 1
            if limit and seen > limit:
                break
            key = normalize(g(rec, "code"))
            if key is None:
                continue
            row = Row(key, SOURCE_OFF)
            row.name = clean(g(rec, "product_name"), MAX_NAME)
            row.brand = clean(g(rec, "brands"), MAX_BRAND)

            kcal = scaled(g(rec, "energy-kcal_100g"), SCALE_KCAL)
            if kcal is None:
                # Some rows carry only kJ. Converting is honest; dropping the row is not.
                kj = scaled(g(rec, "energy_100g"), SCALE_KCAL)
                kcal = int(round(kj / KJ_PER_KCAL)) if kj else None
            row.kcal = kcal
            row.prot = scaled(g(rec, "proteins_100g"), SCALE_MACRO)
            row.carb = scaled(g(rec, "carbohydrates_100g"), SCALE_MACRO)
            row.fat = scaled(g(rec, "fat_100g"), SCALE_MACRO)
            row.fib = scaled(g(rec, "fiber_100g"), SCALE_MACRO)
            row.sug = scaled(g(rec, "sugars_100g"), SCALE_MACRO)
            row.sat = scaled(g(rec, "saturated-fat_100g"), SCALE_MACRO)

            # ⚠️ OFF publishes sodium in GRAMS. x1000 to reach the milligrams the app stores.
            sod = scaled(g(rec, "sodium_100g"), 1000)
            if sod is None:
                # Salt is sodium x 2.5 by mass; the same field under its other common name.
                salt = scaled(g(rec, "salt_100g"), 1000)
                sod = int(round(salt / 2.5)) if salt else None
            row.sod = sod

            row.calcium = scaled(g(rec, "calcium_100g"), 1000)        # g -> mg
            row.iron = scaled(g(rec, "iron_100g"), 1000 * 100)        # g -> mg x100
            row.potassium = scaled(g(rec, "potassium_100g"), 1000)    # g -> mg
            row.vit_a = scaled(g(rec, "vitamin-a_100g"), 1_000_000)   # g -> ug
            row.vit_c = scaled(g(rec, "vitamin-c_100g"), 1000 * 100)  # g -> mg x100
            row.vit_d = scaled(g(rec, "vitamin-d_100g"), 1_000_000)   # g -> ug
            row.chol = scaled(g(rec, "cholesterol_100g"), 1000)       # g -> mg
            row.transfat = scaled(g(rec, "trans-fat_100g"), SCALE_MACRO)

            row.serv_g = scaled(g(rec, "serving_quantity"), 1, ceiling=5000.0)
            row.serv_label = serving_label(g(rec, "serving_size"))
            # ⚠️ Already grams. Open Food Facts converts this field itself — 453.59237 is a
            # pound, 340.19 twelve ounces — so the free-text `quantity` beside it needs no
            # parser here. Measured on the real export: present on 26.7% of rows carrying
            # nutrition, which is what makes a "1 package" portion worth offering at all.
            row.pack_g = mass(g(rec, "product_quantity"), ceiling=100_000.0)
            yield row


# ---------------------------------------------------------------------------------------------
# USDA branded
# ---------------------------------------------------------------------------------------------

# ⚠️ Selected by USDA nutrient NUMBER, never by name — names drift between releases and a silent
# mismatch would put fat in the protein column. Same discipline as build_seed.py.
USDA_NUTRIENTS = {
    "208": ("kcal", SCALE_KCAL),
    "203": ("prot", SCALE_MACRO),
    "205": ("carb", SCALE_MACRO),
    "204": ("fat", SCALE_MACRO),
    "291": ("fib", SCALE_MACRO),
    "269": ("sug", SCALE_MACRO),
    "606": ("sat", SCALE_MACRO),
    "307": ("sod", SCALE_SODIUM),      # ⚠️ USDA already gives MILLIGRAMS. Do not scale.
    "301": ("calcium", 1),             # mg
    "303": ("iron", SCALE_MICRO),      # mg x100
    "306": ("potassium", 1),           # mg
    "320": ("vit_a", 1),               # ug RAE
    "401": ("vit_c", SCALE_MICRO),     # mg x100
    "328": ("vit_d", 1),               # ug
    "601": ("chol", 1),                # mg
    "605": ("transfat", SCALE_MACRO),
}


def read_usda(path: Path, limit: int | None, log):
    """Stream the USDA branded zip. Joins branded_food -> food_nutrient by fdc_id."""
    with zipfile.ZipFile(path) as zf:
        names = {Path(n).name: n for n in zf.namelist() if n.endswith(".csv")}
        need = ["branded_food.csv", "food.csv", "food_nutrient.csv", "nutrient.csv"]
        for n in need:
            if n not in names:
                log(f"  USDA: {n} not in the archive — skipping USDA entirely")
                return

        # nutrient id -> nutrient number, so the selection above can key on the stable number.
        num_of = {}
        with zf.open(names["nutrient.csv"]) as fh:
            for r in csv.DictReader(io.TextIOWrapper(fh, "utf-8", errors="replace")):
                num_of[r["id"]] = r.get("nutrient_nbr", "").strip()

        # fdc_id -> barcode + serving, for branded items that actually carry a GTIN/UPC.
        wanted: dict[str, Row] = {}
        with zf.open(names["branded_food.csv"]) as fh:
            for r in csv.DictReader(io.TextIOWrapper(fh, "utf-8", errors="replace")):
                key = normalize(r.get("gtin_upc", "") or "")
                if key is None:
                    continue
                row = Row(key, SOURCE_USDA)
                row.brand = clean(r.get("brand_owner") or r.get("brand_name"), MAX_BRAND)
                row.serv_g = scaled(r.get("serving_size"), 1, ceiling=5000.0) \
                    if (r.get("serving_size_unit") or "").lower() in ("g", "ml") else None
                # USDA states the household portion separately from its mass — "1 cup", "2 cookies"
                # — which is the half a person recognises. ⚠️ `package_weight` beside it is NOT
                # read: measured over 300,000 branded rows it is present on exactly one, so a
                # column filled from it would be a promise the source cannot keep.
                row.serv_label = serving_label(r.get("household_serving_fulltext"))
                wanted[r["fdc_id"]] = row
                if limit and len(wanted) >= limit:
                    break
        log(f"  USDA: {len(wanted):,} branded items carry a GTIN/UPC")

        with zf.open(names["food.csv"]) as fh:
            for r in csv.DictReader(io.TextIOWrapper(fh, "utf-8", errors="replace")):
                row = wanted.get(r["fdc_id"])
                if row is not None:
                    row.name = clean(r.get("description"), MAX_NAME)

        with zf.open(names["food_nutrient.csv"]) as fh:
            for r in csv.DictReader(io.TextIOWrapper(fh, "utf-8", errors="replace")):
                row = wanted.get(r.get("fdc_id", ""))
                if row is None:
                    continue
                spec = USDA_NUTRIENTS.get(num_of.get(r.get("nutrient_id", ""), ""))
                if spec is None:
                    continue
                field, scale = spec
                v = scaled(r.get("amount"), scale)
                if v is not None:
                    setattr(row, field, v)

        yield from wanted.values()


# ---------------------------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------------------------

BATCH = 20_000


def build(off_path: Path | None, usda_path: Path | None, out: Path, limit: int | None) -> dict:
    def log(msg):
        print(msg, flush=True)

    if out.exists():
        out.unlink()
    con = sqlite3.connect(out)
    con.executescript(SCHEMA)
    cur = con.cursor()

    stats = {"off": 0, "usda": 0, "off_nutrition": 0, "usda_nutrition": 0,
             "bad_checkdigit": 0, "replaced_by_usda": 0}
    # ⚠️ Which barcodes already hold real numbers, so USDA can fill a gap without overwriting a
    # populated OFF row. Kept as a set of ints rather than queried per row: 4.4M round trips to
    # SQLite would dominate the whole build.
    complete: set[int] = set()

    if off_path and off_path.exists():
        log(f"OFF   {off_path.name} ({off_path.stat().st_size / 1e6:.0f} MB gz)")
        t0 = time.time()
        batch = []
        for row in read_off(off_path, limit, log):
            stats["off"] += 1
            if row.has_nutrition:
                stats["off_nutrition"] += 1
                complete.add(row.barcode)
            batch.append(row.values())
            if len(batch) >= BATCH:
                cur.executemany(INSERT, batch)
                batch.clear()
                if stats["off"] % 500_000 == 0:
                    log(f"  {stats['off']:,} products  ({time.time() - t0:.0f}s)")
        if batch:
            cur.executemany(INSERT, batch)
        con.commit()
        log(f"  {stats['off']:,} products in {time.time() - t0:.0f}s")

    if usda_path and usda_path.exists():
        log(f"USDA  {usda_path.name} ({usda_path.stat().st_size / 1e6:.0f} MB zip)")
        t0 = time.time()
        batch = []
        for row in read_usda(usda_path, limit, log):
            stats["usda"] += 1
            if not row.has_nutrition:
                continue
            stats["usda_nutrition"] += 1
            # ⚠️ USDA fills gaps; it does not overwrite. OFF is the only source with European
            # coverage, and a US row landing on top of a populated French one would be a net loss.
            if row.barcode in complete:
                continue
            stats["replaced_by_usda"] += 1
            batch.append(row.values())
            if len(batch) >= BATCH:
                cur.executemany(INSERT, batch)
                batch.clear()
        if batch:
            cur.executemany(INSERT, batch)
        con.commit()
        log(f"  {stats['usda']:,} items, {stats['replaced_by_usda']:,} filled a gap "
            f"({time.time() - t0:.0f}s)")

    total = cur.execute("SELECT COUNT(*) FROM food").fetchone()[0]
    with_nut = cur.execute("SELECT COUNT(*) FROM food WHERE kcal IS NOT NULL AND kcal > 0").fetchone()[0]
    named = cur.execute("SELECT COUNT(*) FROM food WHERE name IS NOT NULL").fetchone()[0]
    served = cur.execute("SELECT COUNT(*) FROM food WHERE serv_g IS NOT NULL").fetchone()[0]

    for k, v in {
        "rows": str(total),
        "with_nutrition": str(with_nut),
        "built_at": time.strftime("%Y-%m-%d", time.gmtime()),
        "sources": "Open Food Facts (ODbL); USDA FoodData Central (public domain)",
        "attribution": "Product data from Open Food Facts, licensed under the Open Database "
                       "License (ODbL). Generic and US branded data from USDA FoodData Central.",
    }.items():
        cur.execute("INSERT OR REPLACE INTO meta VALUES (?,?)", (k, v))
    con.commit()

    log("VACUUM…")
    cur.execute("VACUUM")
    con.close()

    size = out.stat().st_size
    stats.update(rows=total, with_nutrition=with_nut, named=named, served=served, bytes=size)
    log("")
    log(f"rows              {total:,}")
    log(f"  named           {named:,}  ({100 * named / max(total, 1):.1f}%)")
    log(f"  with nutrition  {with_nut:,}  ({100 * with_nut / max(total, 1):.1f}%)")
    log(f"  with a serving  {served:,}  ({100 * served / max(total, 1):.1f}%)")
    log(f"size              {size / 1048576:.0f} MB  ({size / max(total, 1):.1f} bytes/row)")
    return stats


# ---------------------------------------------------------------------------------------------
# Verify
# ---------------------------------------------------------------------------------------------

# Real products, chosen so a wrong unit or a wrong column is visible rather than plausible.
PROBES = [
    ("5449000000996", "Coca-Cola 330ml"),
    ("3017620422003", "Nutella 400g"),
    ("038000138416", "Kellogg's, as UPC-A"),
    ("0038000138416", "the same, as EAN-13"),
]


def verify(out: Path) -> bool:
    con = sqlite3.connect(f"file:{out}?mode=ro", uri=True)
    cur = con.cursor()
    ok = True
    print("\nprobes:")
    for raw, label in PROBES:
        key = normalize(raw)
        r = cur.execute(
            "SELECT name, brand, kcal, prot, carb, fat, sod FROM food WHERE barcode=?", (key,)
        ).fetchone()
        if r is None:
            print(f"  {label:<28} {raw:>14} -> NOT FOUND")
            continue
        name, brand, kcal, prot, carb, fat, sod = r
        macros = "  ".join(
            f"{k} {v / SCALE_MACRO:g}" for k, v in
            (("P", prot), ("C", carb), ("F", fat)) if v is not None
        )
        print(f"  {label:<28} {raw:>14} -> {(brand or '')[:18]:<18} {(name or '')[:34]:<34} "
              f"{'' if kcal is None else str(kcal) + ' kcal':<10} {macros}")

    # ⚠️ The equivalence the whole key rests on, asserted rather than eyeballed.
    a = normalize("038000138416")
    b = normalize("0038000138416")
    if a != b:
        print("  FAIL: UPC-A and EAN-13 forms did not normalise to one key")
        ok = False

    # ⚠️ The sodium trap, caught by magnitude. Table salt is ~39% sodium, so no whole food is
    # anywhere near 40,000 mg/100g. A 1000x error would put a great many rows past it.
    absurd = cur.execute("SELECT COUNT(*) FROM food WHERE sod > 40000").fetchone()[0]
    total = cur.execute("SELECT COUNT(*) FROM food WHERE sod IS NOT NULL").fetchone()[0]
    share = 100 * absurd / max(total, 1)
    print(f"\nsodium sanity: {absurd:,} of {total:,} rows above 40,000 mg/100g ({share:.3f}%)")
    if share > 1.0:
        print("  FAIL: too many implausible sodium values — check the g/mg conversion")
        ok = False

    # Energy sanity: pure fat is ~900 kcal/100 g, so anything far past that is a bad row.
    hot = cur.execute("SELECT COUNT(*) FROM food WHERE kcal > 1000").fetchone()[0]
    withk = cur.execute("SELECT COUNT(*) FROM food WHERE kcal IS NOT NULL").fetchone()[0]
    print(f"energy sanity: {hot:,} of {withk:,} rows above 1000 kcal/100g "
          f"({100 * hot / max(withk, 1):.3f}%)")

    con.close()
    print("\nVERIFY " + ("PASSED" if ok else "FAILED"))
    return ok


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--work", type=Path, required=True, help="directory holding the source downloads")
    ap.add_argument("--out", type=Path, default=Path("food.db"))
    ap.add_argument("--limit", type=int, default=None, help="stop after N source rows (for a smoke run)")
    ap.add_argument("--verify-only", action="store_true")
    args = ap.parse_args()

    if args.verify_only:
        return 0 if verify(args.out) else 1

    off = args.work / "off.csv.gz"
    usda = args.work / "usda_branded.zip"
    if not off.exists() and not usda.exists():
        print(f"no sources in {args.work} (expected off.csv.gz and/or usda_branded.zip)", file=sys.stderr)
        return 2

    build(off if off.exists() else None, usda if usda.exists() else None, args.out, args.limit)
    return 0 if verify(args.out) else 1


if __name__ == "__main__":
    raise SystemExit(main())
