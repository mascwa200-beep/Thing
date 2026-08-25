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

# ⚠️ **Vitamin D is x100 and every other microgram-scale field is not, and that is measured rather
# than inconsistent.** The reference intake is 15 ug a DAY, so the numbers this field carries are
# fractions of a microgram per hundred grams: a fortified yogurt at 0.4 ug stored as an integer
# microgram becomes 0 and vanishes, and 1.2 ug becomes 1, a seventeen per cent error on the tightest
# micronutrient in the set relative to its own guideline. Vitamin A is the opposite case — foods run
# to hundreds of micrograms against a 900 ug guideline — so a whole microgram there is already finer
# than the sources publish.
#
# ⚠️ The inverse of this lives in `OfflineFoodStore.micros`, in Kotlin, and the two must move in one
# commit. A scale is the one kind of constant where a second copy left behind does not fail — it
# quietly divides somebody's intake by a hundred.
SCALE_MICRO_CENTI = 100  # microgram-scale micros x100 -> 0.01 ug

import nutrient_set

# ⚠️ **Loaded at import, before anything is downloaded, and a bad parse is fatal here.** These 29
# nutrients are declared once, in `NutrientSet.kt`, because the app reads every one of their ids,
# units and scales at runtime — a second table in Python would be the same facts twice, and the
# failure mode is the worst kind: magnesium written under phosphorus's id across millions of rows
# with nothing anywhere to notice. `tools/kb/ci_parity_lint.py` reads its allowlist out of Kotlin
# for the same reason.
EXTRAS = nutrient_set.load()

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

-- Every further nutrient, sparsely: a row only where a figure exists.
--
-- ⚠️ **A side table because 29 more columns on `food` would cost 128 MB of nothing.** SQLite
-- spends a byte of record header on a column even when it is NULL. Measured both ways over
-- 200,000 realistic rows and extrapolated to the real 4.5M: the widened shape is +127.9 MB
-- carrying nothing, this one is +44.7 MB carrying every figure there is. The densest of the 29
-- is recorded on 5.7% of products and most are near 2%.
--
-- ⚠️ **WITHOUT ROWID, which halves it again.** An ordinary rowid table would need a separate
-- unique index on the pair, and that index holds a second copy of both key columns: measured at
-- 4.27 MB against 1.98 MB for the same 121,147 rows. Here the primary key IS the B-tree, the same
-- reasoning that makes `barcode INTEGER PRIMARY KEY` right for `food`.
--
-- `nutrient` is NutrientSet.Nutrient.id — a permanent number, never an ordinal.
CREATE TABLE food_extra (
  barcode  INTEGER NOT NULL,
  nutrient INTEGER NOT NULL,
  value    INTEGER NOT NULL,
  PRIMARY KEY (barcode, nutrient)
) WITHOUT ROWID;

CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
"""

COLUMNS = (
    "barcode name brand kcal prot carb fat fib sug sat sod "
    "calcium iron potassium vit_a vit_c vit_d chol transfat serv_g serv_label pack_g src"
).split()

INSERT = f"INSERT OR REPLACE INTO food ({','.join(COLUMNS)}) VALUES ({','.join('?' * len(COLUMNS))})"
EXTRA_INSERT = "INSERT OR REPLACE INTO food_extra (barcode,nutrient,value) VALUES (?,?,?)"

MAX_NAME = 90
MAX_BRAND = 60


INT_MAX = 2_147_483_647

# ---------------------------------------------------------------------------------------------
# What a hundred grams of food can possibly hold
# ---------------------------------------------------------------------------------------------
#
# ⚠️ **This did not exist, and a crowd-entered corpus of 4.4 million rows guarantees it is needed.**
# `scaled` took one ceiling of 100,000 applied to the RAW figure, so `proteins_100g: 5000` — five
# kilograms of protein in a hundred grams — was stored as fact, and a vitamin A figure somebody typed
# in international units into a field documented as grams became one and a half billion micrograms.
# Neither reads as an error on a card. Both read as a food.
#
# ⚠️ **Deliberately physics rather than nutrition, exactly as `FoodPortion.MAX_MASS_G_PER_100G` says
# in Kotlin — these are two implementations of ONE rule and are kept aligned field for field.** A
# tighter bound would catch more and every tighter bound is an opinion that can be wrong about a real
# food: a protein isolate really is eighty grams of protein per hundred, pure oil really is a hundred
# grams of fat. What is unarguable is that a constituent cannot outweigh the food, and that alone
# catches the whole thousand-fold unit-error family, which is where the wrong numbers come from.
#
# ⚠️ **The second reason is not nutritional at all: Room reads these columns as 32-bit `Int`.** SQLite
# will store 1.5e11 happily in a 64-bit integer and `Cursor.getInt` will then hand Kotlin a truncated
# value — a garbage figure arriving on the phone as a small, plausible one. So every ceiling is capped
# at `INT_MAX` too, and a column that could exceed it is a defect whatever its physics.
def _ceiling(natural_max: float, scale: int) -> int:
    return int(min(natural_max * scale, INT_MAX))


CEILINGS = {
    # Energy is not a mass. Pure fat is ~900 kcal per 100 g and ethanol ~700, so nothing edible
    # reaches a thousand; the slack above 900 is for a record that rounded, not one that is wrong.
    "kcal": _ceiling(1_000.0, SCALE_KCAL),
    # Grams, bounded by the weight of the food itself.
    "prot": _ceiling(100.0, SCALE_MACRO),
    "carb": _ceiling(100.0, SCALE_MACRO),
    "fat": _ceiling(100.0, SCALE_MACRO),
    "fib": _ceiling(100.0, SCALE_MACRO),
    "sug": _ceiling(100.0, SCALE_MACRO),
    "sat": _ceiling(100.0, SCALE_MACRO),
    "transfat": _ceiling(100.0, SCALE_MACRO),
    # Milligrams: 100 g is 100,000 mg.
    "sod": _ceiling(100_000.0, SCALE_SODIUM),
    "calcium": _ceiling(100_000.0, 1),
    "potassium": _ceiling(100_000.0, 1),
    "chol": _ceiling(100_000.0, 1),
    "iron": _ceiling(100_000.0, SCALE_MICRO),
    "vit_c": _ceiling(100_000.0, SCALE_MICRO),
    # Micrograms: 100 g is 100,000,000 ug — a bound INT_MAX tightens further, which is the point.
    "vit_a": _ceiling(100_000_000.0, SCALE_MICROGRAM),
    "vit_d": _ceiling(100_000_000.0, SCALE_MICRO_CENTI),
}

# Protein, fat and carbohydrate are distinct constituents, so together they cannot outweigh the food
# either — with slack, because a source may round each of them and some conventions count fibre inside
# the carbohydrate figure while others do not. Mirrors FoodPortion.MAX_MACRO_SUM_G.
MAX_MACRO_SUM_G = 105.0

# The nutrition block, in the order the schema declares it. When the macros contradict the food's own
# weight there is no way to know WHICH of them is wrong, so the whole block goes and the row keeps its
# name — the state the app already renders as `FoodLookup.NoNutrition`, and a far better answer than a
# plausible-looking calorie count. Micronutrients are left alone, exactly as `FoodPortion.sane` leaves
# them, because they are bounded individually and none of them is implicated by a macro sum.
NUTRITION_FIELDS = ("kcal", "prot", "carb", "fat", "fib", "sug", "sat", "sod")


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

    # ⚠️ `extras` is NOT a column: it is the sparse side-table payload, a dict of
    # NutrientSet id -> stored integer, and it travels with the row so a reader fills it in one
    # place and the insert loop writes it in one place.
    __slots__ = COLUMNS + ["extras"]

    # How many times each field was refused, and how many rows lost their whole nutrition block.
    # ⚠️ Class-level rather than passed around, because it belongs with the rule and the rule has to
    # sit where nothing can go round it. A build reports these; a ceiling that dropped nothing is not
    # evidence it was unnecessary, so the report names the ones that never fired too.
    DROPPED: dict[str, int] = {}
    IMPOSSIBLE_MACROS = 0

    def __init__(self, barcode: int, src: int):
        for c in COLUMNS:
            setattr(self, c, None)
        self.barcode = barcode
        self.src = src
        self.extras = {}

    def values(self):
        """The row as the INSERT wants it — sanitised, because this is the only way in.

        ⚠️ The bounds are applied HERE rather than at each reader. Both `read_off` and `read_usda`
        end in `row.values()` and a third source added later will too, which is the difference
        between a rule and a rule somebody remembered.
        """
        self._bound()
        return tuple(getattr(self, c) for c in COLUMNS)

    def _bound(self):
        for field, ceiling in CEILINGS.items():
            v = getattr(self, field)
            if v is not None and (v < 0 or v > ceiling):
                setattr(self, field, None)
                Row.DROPPED[field] = Row.DROPPED.get(field, 0) + 1

        macros = sum(
            (getattr(self, f) or 0) for f in ("prot", "carb", "fat")
        ) / SCALE_MACRO
        if macros > MAX_MACRO_SUM_G:
            for f in NUTRITION_FIELDS:
                setattr(self, f, None)
            Row.IMPOSSIBLE_MACROS += 1

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
} | {f"{n.off_field}_100g" for n in EXTRAS}

KJ_PER_KCAL = 4.184

# How many figures each further nutrient contributed, and how many were refused as unbelievable.
# ⚠️ Reported for every one of the 29 whether it fired or not — a nutrient that yielded nothing is
# the thing most worth knowing about, and a report listing only the ones that worked cannot say it.
EXTRA_KEPT: dict[int, int] = {}
EXTRA_REFUSED: dict[int, int] = {}


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
            # g -> ug x100. See SCALE_MICRO_CENTI: a whole microgram is too coarse for a nutrient
            # whose daily reference is fifteen of them.
            row.vit_d = scaled(g(rec, "vitamin-d_100g"), 1_000_000 * SCALE_MICRO_CENTI)
            row.chol = scaled(g(rec, "cholesterol_100g"), 1000)       # g -> mg
            row.transfat = scaled(g(rec, "trans-fat_100g"), SCALE_MACRO)

            row.serv_g = scaled(g(rec, "serving_quantity"), 1, ceiling=5000.0)
            row.serv_label = serving_label(g(rec, "serving_size"))
            # ⚠️ Already grams. Open Food Facts converts this field itself — 453.59237 is a
            # pound, 340.19 twelve ounces — so the free-text `quantity` beside it needs no
            # parser here. Measured on the real export: present on 26.7% of rows carrying
            # nutrition, which is what makes a "1 package" portion worth offering at all.
            row.pack_g = mass(g(rec, "product_quantity"), ceiling=100_000.0)

            # ⚠️ **Open Food Facts publishes every one of these in GRAMS**, whatever unit the app
            # stores them in — `Nutrient.store` is the single conversion and it is the twin of
            # `NutrientSet.fromGrams`/`sane`/`store` in Kotlin. An absent or unbelievable figure
            # yields None and no row at all: absent is not zero, and the app renders the two
            # differently on purpose.
            for n in EXTRAS:
                v = n.store(g(rec, f"{n.off_field}_100g") or None)
                if v is not None:
                    row.extras[n.id] = v
                    EXTRA_KEPT[n.id] = EXTRA_KEPT.get(n.id, 0) + 1
                elif (g(rec, f"{n.off_field}_100g") or "") != "":
                    EXTRA_REFUSED[n.id] = EXTRA_REFUSED.get(n.id, 0) + 1

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
    "328": ("vit_d", SCALE_MICRO_CENTI),  # ug x100 — see SCALE_MICRO_CENTI
    "601": ("chol", 1),                # mg
    "605": ("transfat", SCALE_MACRO),
}


# ⚠️ **Measured, not recalled.** Both USDA generic datasets were downloaded and every nutrient id
# they publish was enumerated, then each of the 29 was matched by NAME and its real coverage read
# off. That is where these numbers come from; the file's own rule above — select by NUMBER, never by
# name, because names drift between releases — still holds for the selection itself.
#
# ⚠️ **Three of the 29 are Open Food Facts only, and that is a measured absence rather than an
# omission**: neither USDA dataset publishes ADDED SUGARS (it has only "Sugars, total including
# NLEA"), IODINE (nothing at all), or POLYOLS (no sugar alcohol, sorbitol, xylitol or maltitol under
# any name). Nothing here can fill those.
#
# ⚠️ The value's UNIT is NOT hardcoded. `nutrient.csv` states it per nutrient and the conversion is
# derived from that — riboflavin arrives in milligrams where this app stores micrograms, and a
# hardcoded scale is exactly how a thousandfold error gets into a column and stays plausible. A unit
# this does not understand is SKIPPED and reported, never guessed at.
USDA_EXTRA_NUMBERS = {
    "209": "STARCH",
    "210": "SUCROSE",
    "211": "GLUCOSE",
    "212": "FRUCTOSE",
    "213": "LACTOSE",
    "214": "MALTOSE",
    "287": "GALACTOSE",
    "645": "MONOUNSATURATED_FAT",
    "646": "POLYUNSATURATED_FAT",
    "304": "MAGNESIUM",
    "305": "PHOSPHORUS",
    "309": "ZINC",
    "315": "MANGANESE",
    "312": "COPPER",
    "317": "SELENIUM",
    "404": "VITAMIN_B1",
    "405": "VITAMIN_B2",       # ⚠️ USDA publishes milligrams; this app stores micrograms.
    "406": "NIACIN",
    "410": "PANTOTHENIC_ACID",
    "415": "VITAMIN_B6",
    "417": "FOLATE",           # "Folate, total" — NOT 435 (DFE) or 432 (food), which are different figures.
    "418": "VITAMIN_B12",      # NOT 578, which is "Vitamin B-12, added".
    "323": "VITAMIN_E",        # alpha-tocopherol. NOT the gamma/beta/delta tocopherols beside it.
    "430": "VITAMIN_K1",       # phylloquinone, the same substance the OFF column names.
    "321": "BETA_CAROTENE",
    "255": "WATER",
}

# How many grams one of each unit USDA may state is. ⚠️ Anything not here is refused rather than
# assumed, because assuming grams for an unrecognised unit is a thousandfold error in the safe-
# looking direction.
GRAMS_PER_USDA_UNIT = {"g": 1.0, "mg": 1e-3, "\u00b5g": 1e-6, "ug": 1e-6, "mcg": 1e-6}

EXTRA_BY_NAME = {n.name: n for n in EXTRAS}
_unknown = sorted(set(USDA_EXTRA_NUMBERS.values()) - set(EXTRA_BY_NAME))
if _unknown:
    raise SystemExit(
        f"build_food_db: USDA_EXTRA_NUMBERS names {_unknown}, which NutrientSet.kt does not declare.\n"
        "  A name that resolves to nothing would silently contribute nothing, in a green build."
    )
USDA_EXTRA_SKIPPED: dict[str, int] = {}


def read_usda(path: Path, limit: int | None, log):
    """Stream the USDA branded zip. Joins branded_food -> food_nutrient by fdc_id."""
    with zipfile.ZipFile(path) as zf:
        names = {Path(n).name: n for n in zf.namelist() if n.endswith(".csv")}
        need = ["branded_food.csv", "food.csv", "food_nutrient.csv", "nutrient.csv"]
        for n in need:
            if n not in names:
                # ⚠️ Loud, and a hard failure. A source that was DOWNLOADED and then contributed
                # nothing is a broken assumption, not an outage — the caller went to the trouble of
                # fetching 449 MB, so something about the archive is not what this expects. The
                # first version merely logged one line and returned, and a build that quietly lost
                # the whole USDA merge went green with 48.8% nutrition coverage instead of 56.3%.
                # A source that was never provided at all is a different case and is fine: `build`
                # is called with whichever paths exist.
                raise SystemExit(
                    f"USDA archive {path.name} has no {n}.\n"
                    f"  It contains: {sorted(names)[:6]}\n"
                    "  This builder parses the CSV release. Check the URL says _csv_ and not _json_."
                )

        # nutrient id -> nutrient number, so the selection above can key on the stable number.
        num_of, unit_of = {}, {}
        with zf.open(names["nutrient.csv"]) as fh:
            for r in csv.DictReader(io.TextIOWrapper(fh, "utf-8", errors="replace")):
                num_of[r["id"]] = r.get("nutrient_nbr", "").strip()
                # ⚠️ The unit the archive itself states, so no scale for a further nutrient is ever
                # written down twice. See GRAMS_PER_USDA_UNIT.
                unit_of[r["id"]] = r.get("unit_name", "").strip()

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
                number = num_of.get(r.get("nutrient_id", ""), "")
                spec = USDA_NUTRIENTS.get(number)
                if spec is not None:
                    field, scale = spec
                    v = scaled(r.get("amount"), scale)
                    if v is not None:
                        setattr(row, field, v)
                    continue

                extra = EXTRA_BY_NAME.get(USDA_EXTRA_NUMBERS.get(number, ""))
                if extra is None:
                    continue
                # ⚠️ The archive states the unit; convert THROUGH grams, which is the one currency
                # `Nutrient.store` speaks. An unknown unit is counted and dropped.
                per_g = GRAMS_PER_USDA_UNIT.get(unit_of.get(r.get("nutrient_id", ""), "").lower())
                if per_g is None:
                    USDA_EXTRA_SKIPPED[extra.name] = USDA_EXTRA_SKIPPED.get(extra.name, 0) + 1
                    continue
                try:
                    grams = float(r.get("amount") or "") * per_g
                except (TypeError, ValueError):
                    continue
                v = extra.store(grams)
                if v is not None:
                    row.extras[extra.id] = v

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
        extra_batch = []
        for row in read_off(off_path, limit, log):
            stats["off"] += 1
            # ⚠️ **`values()` is what applies the bounds, so it has to come BEFORE `has_nutrition` is
            # read.** The other way round — which is how this loop was first written after the bounds
            # landed — a row whose macros outweigh the food counts as complete and enters `complete`,
            # blocking a perfectly good USDA row from filling the gap, and THEN has its nutrition
            # stripped. The row ends up with no numbers and no second chance at them.
            vals = row.values()
            if row.has_nutrition:
                stats["off_nutrition"] += 1
                complete.add(row.barcode)
            batch.append(vals)
            extra_batch.extend((row.barcode, k, v) for k, v in row.extras.items())
            if len(batch) >= BATCH:
                cur.executemany(INSERT, batch)
                batch.clear()
                # ⚠️ The extras go in with the product batch, never afterwards from a list held
                # over the whole stream: at ~2 million of them that list is the one structure in
                # this builder big enough to matter.
                if extra_batch:
                    cur.executemany(EXTRA_INSERT, extra_batch)
                    extra_batch.clear()
                if stats["off"] % 500_000 == 0:
                    log(f"  {stats['off']:,} products  ({time.time() - t0:.0f}s)")
        if batch:
            cur.executemany(INSERT, batch)
        if extra_batch:
            cur.executemany(EXTRA_INSERT, extra_batch)
        con.commit()
        log(f"  {stats['off']:,} products in {time.time() - t0:.0f}s")

    if usda_path and usda_path.exists():
        log(f"USDA  {usda_path.name} ({usda_path.stat().st_size / 1e6:.0f} MB zip)")
        t0 = time.time()
        batch = []
        extra_batch = []
        for row in read_usda(usda_path, limit, log):
            stats["usda"] += 1
            # ⚠️ Same order as above, and for the same reason.
            vals = row.values()
            if not row.has_nutrition:
                continue
            stats["usda_nutrition"] += 1
            # ⚠️ USDA fills gaps; it does not overwrite. OFF is the only source with European
            # coverage, and a US row landing on top of a populated French one would be a net loss.
            if row.barcode in complete:
                continue
            stats["replaced_by_usda"] += 1
            batch.append(vals)
            # ⚠️ Only for rows that are actually inserted. A row skipped because Open Food Facts
            # already has better numbers must not leave its further nutrients behind, pointing at a
            # product row that says something else.
            extra_batch.extend((row.barcode, k, v) for k, v in row.extras.items())
            for k in row.extras:
                EXTRA_KEPT[k] = EXTRA_KEPT.get(k, 0) + 1
            if len(batch) >= BATCH:
                cur.executemany(INSERT, batch)
                batch.clear()
                if extra_batch:
                    cur.executemany(EXTRA_INSERT, extra_batch)
                    extra_batch.clear()
        if batch:
            cur.executemany(INSERT, batch)
        if extra_batch:
            cur.executemany(EXTRA_INSERT, extra_batch)
        con.commit()
        log(f"  {stats['usda']:,} items, {stats['replaced_by_usda']:,} filled a gap "
            f"({time.time() - t0:.0f}s)")

    # ⚠️ Reported even when nothing was refused, and every ceiling is named either way. A ceiling that
    # dropped nothing is not evidence it was unnecessary — it is evidence about this release of these
    # sources — and a report that lists only the ones that fired cannot tell the two apart.
    log("bounds:")
    for field in CEILINGS:
        n = Row.DROPPED.get(field, 0)
        log(f"  {field:<10} ceiling {CEILINGS[field]:>12,}  refused {n:,}")
    log(f"  macros outweighed the food on {Row.IMPOSSIBLE_MACROS:,} rows "
        f"(nutrition dropped, name kept)")

    # ⚠️ Every one of the 29 is named whether it contributed or not. A nutrient that yielded
    # nothing at all is the single most useful line in this report — it means either the column
    # moved or the whole feature is inert for it — and a report listing only what worked cannot
    # tell that from a nutrient nobody has ever recorded.
    if USDA_EXTRA_SKIPPED:
        log("USDA figures dropped because their unit was not one this understands:")
        for name, n in sorted(USDA_EXTRA_SKIPPED.items()):
            log(f"  {name:<22} {n:,}")

    kept_total = sum(EXTRA_KEPT.values())
    log(f"further nutrients: {kept_total:,} figures across {len(EXTRAS)} nutrients")
    for n in EXTRAS:
        kept = EXTRA_KEPT.get(n.id, 0)
        refused = EXTRA_REFUSED.get(n.id, 0)
        seen = stats.get("off", 0) + stats.get("replaced_by_usda", 0)
        share = (kept / seen * 100) if seen else 0.0
        flag = "   <-- NOTHING" if kept == 0 else ""
        log(f"  {n.id:>2} {n.name:<22} {kept:>10,}  {share:5.2f}%  refused {refused:,}{flag}")

    total = cur.execute("SELECT COUNT(*) FROM food").fetchone()[0]
    with_nut = cur.execute("SELECT COUNT(*) FROM food WHERE kcal IS NOT NULL AND kcal > 0").fetchone()[0]
    named = cur.execute("SELECT COUNT(*) FROM food WHERE name IS NOT NULL").fetchone()[0]
    served = cur.execute("SELECT COUNT(*) FROM food WHERE serv_g IS NOT NULL").fetchone()[0]

    # ⚠️ **Derived from what actually happened, never asserted.** This line was a hardcoded string
    # naming both sources, so run 1962 shipped a database claiming USDA data it did not contain —
    # USDA had been skipped and the meta table said otherwise. A source that contributed nothing is
    # not named, and its licence is not claimed either.
    contributed = []
    if stats.get("off"):
        contributed.append("Open Food Facts (ODbL)")
    if stats.get("usda"):
        contributed.append("USDA FoodData Central (public domain)")
    attribution = "; ".join(
        part for part, on in (
            ("Product data from Open Food Facts, licensed under the Open Database License (ODbL)",
             stats.get("off")),
            ("Branded data from USDA FoodData Central, public domain", stats.get("usda")),
        ) if on
    ) or "No source contributed to this build."

    for k, v in {
        "rows": str(total),
        "with_nutrition": str(with_nut),
        "built_at": time.strftime("%Y-%m-%d", time.gmtime()),
        "off_rows": str(stats.get("off", 0)),
        "usda_rows": str(stats.get("usda", 0)),
        "extra_rows": str(cur.execute("SELECT COUNT(*) FROM food_extra").fetchone()[0]),
        "extra_nutrients": str(len(EXTRAS)),
        "sources": "; ".join(contributed) or "none",
        "attribution": attribution + ".",
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

    # ⚠️ **The side table is checked against the Kotlin that defines it, not against itself.**
    # Every nutrient id present has to be one this build knows, every value has to fit the column
    # Room reads as a 32-bit Int, and — the one that matters most — the table must not be EMPTY.
    # A silent under-parse upstream would ship a feature that does nothing, in a green build.
    known = {n.id: n for n in EXTRAS}
    extra_rows = cur.execute("SELECT COUNT(*) FROM food_extra").fetchone()[0]
    distinct = cur.execute("SELECT COUNT(DISTINCT nutrient) FROM food_extra").fetchone()[0]
    print(f"\nfurther nutrients: {extra_rows:,} figures, {distinct} of {len(known)} nutrients present")
    if extra_rows == 0:
        print("  FAIL: the side table is empty — the extraction did not run")
        ok = False
    stray = cur.execute(
        "SELECT DISTINCT nutrient FROM food_extra WHERE nutrient NOT IN "
        f"({','.join(str(i) for i in known)})"
    ).fetchall() if known else []
    if stray:
        print(f"  FAIL: unknown nutrient ids in the side table: {[r[0] for r in stray]}")
        ok = False
    for nid, n in known.items():
        hi = cur.execute("SELECT MAX(value) FROM food_extra WHERE nutrient=?", (nid,)).fetchone()[0]
        if hi is not None and hi > n.stored_ceiling:
            print(f"  FAIL: {n.name} holds {hi:,}, past its ceiling of {n.stored_ceiling:,}")
            ok = False
    # Every barcode in the side table must be a product this database actually has, or the figure
    # is unreachable — a row nothing can ever join to.
    orphans = cur.execute(
        "SELECT COUNT(*) FROM food_extra WHERE barcode NOT IN (SELECT barcode FROM food)"
    ).fetchone()[0]
    if orphans:
        print(f"  FAIL: {orphans:,} figures belong to barcodes not in the product table")
        ok = False

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
