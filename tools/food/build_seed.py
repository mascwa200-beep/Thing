#!/usr/bin/env python3
"""Build the bundled offline food seed from USDA's public-domain bulk data.

    tools/food/build_seed.py [--work DIR] [--out PATH]

Open Food Facts knows what is on a supermarket shelf; it is a *packaged* food database, organised
around barcodes. It is poor at "chicken breast" and "olive oil" — the generic foods most cooking is
made of — and it needs a network. USDA's FoodData Central is the opposite: laboratory analyses of
generic foods, public domain, and small enough to bundle. Together they cover what one person
actually eats.

Two datasets, and the second earns its place by measurement rather than by being available:

  - **SR Legacy** (7,793 records) — the classic reference. Foods as *bought*, in USDA's inverted
    style: "Chicken, broilers or fryers, breast, meat only, cooked, roasted".
  - **Survey / FNDDS** (5,432 records) — foods as *eaten*, described the way a person would:
    "Chicken breast, baked, broiled, or roasted, skin not eaten, from raw".

⚠️ Measured before including Survey: a search for "chicken breast" against SR Legacy alone returns
deli slices, breaded tenders and a lunch-meat roll, because the plain roasted breast is filed under
"Chicken, broilers or fryers…". Survey returns exactly what the person meant. Dropping Survey to save
a few hundred kilobytes would make the commonest search in the feature return the wrong six rows.

--------------------------------------------------------------------------------------------------
⚠️ THREE THINGS THE DATA DOES THAT ITS SHAPE DOES NOT SUGGEST. Each is asserted, not assumed, so a
future USDA release that changes one fails this script instead of shipping a wrong number.

1. **`Energy` appears TWICE in every record** — nutrient 1062 in kJ and nutrient 1008 in kcal, both
   present in all 7,793 SR Legacy foods. Matching on the *name* is a coin flip that lands on kJ
   roughly half the time, which is a 4.184× error in the single most important field. Nutrients are
   therefore selected by **id**, never by name, and the unit of every one is checked.

2. **USDA sodium is in MILLIGRAMS; Open Food Facts sends GRAMS.** Two sources, one field, two units.
   `FoodPortion.sodiumMgFromGrams` exists for the OFF path and must never be applied to this data —
   it would divide every seeded food's sodium by a thousand. The unit assertion below is what keeps
   the two straight.

3. **A declared portion weight can be absurd.** The same rule the OFF parser uses applies here:
   `MIN_SERVING_G`/`MAX_SERVING_G` mirror `FoodPortion`, and a portion outside them is dropped rather
   than carried. A serving weight is a multiplier, so a wrong one is worse than none.

--------------------------------------------------------------------------------------------------
OUTPUT is a tab-separated file, not JSON, and that is a memory decision rather than a taste one.
Around 13,000 records parsed into objects at startup is several megabytes of heap held forever for a
screen most people open occasionally. A line-oriented file can be scanned as raw text and parsed only
on a hit — the same "reject cheaply, parse the survivors" discipline `SurvivalContentRepository`
already uses for guide bodies, where it keeps memory at O(one shard) whatever the corpus does.

Columns, in order:

    id  name  category  kcal  protein  fat  carb  fibre  sugar  satfat  sodium_mg  serving_g  serving_label

Descriptions are checked for tabs and newlines and stripped if any appear (none do today, in either
dataset — but a delimiter arriving inside a field shifts every column after it silently, which is a
corruption nothing downstream could detect).
"""

import argparse
import io
import json
import pathlib
import sys
import urllib.request
import zipfile

# ⚠️ Selected by ID. See note 1 above: `Energy` is also nutrient 1062, in kJ.
NUTRIENTS = {
    1008: ("kcal", "kcal"),
    1003: ("protein", "g"),
    1004: ("fat", "g"),
    1005: ("carb", "g"),
    1079: ("fibre", "g"),
    2000: ("sugar", "g"),
    1258: ("satfat", "g"),
    # ⚠️ mg, unlike Open Food Facts. See note 2.
    1093: ("sodium", "mg"),
}

# Mirrors FoodPortion.MIN_SERVING_G / MAX_SERVING_G. Kept in step by hand; the Kotlin core is the
# authority and a drift here only means the seed is slightly more or less generous than a scan.
MIN_SERVING_G = 4.0
MAX_SERVING_G = 2000.0

DATASETS = [
    # (url, top-level JSON key, source tag)
    ("https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_json_2021-10-28.zip",
     "SRLegacyFoods", "sr"),
    ("https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_survey_food_json_2024-10-31.zip",
     "SurveyFoods", "fndds"),
]


def fetch(url: str, work: pathlib.Path) -> dict:
    """The dataset, downloaded once into `work` and reused on later runs."""
    name = url.rsplit("/", 1)[1]
    local = work / name
    if not local.exists():
        print(f"  downloading {name} …", flush=True)
        with urllib.request.urlopen(url, timeout=300) as r:
            local.write_bytes(r.read())
    with zipfile.ZipFile(local) as z:
        inner = z.namelist()[0]
        with z.open(inner) as f:
            return json.load(io.TextIOWrapper(f, encoding="utf-8"))


def clean(s: str) -> str:
    """A field that cannot break the delimiter."""
    return " ".join(s.replace("\t", " ").split()).strip()


def nutrients_of(record: dict, complaints: list) -> dict:
    """The eight fields we store, by nutrient id, with every unit checked."""
    out = {}
    for n in record.get("foodNutrients", []):
        nu = n.get("nutrient", {})
        want = NUTRIENTS.get(nu.get("id"))
        if want is None:
            continue
        field, expect = want
        amount = n.get("amount")
        if amount is None:
            continue
        unit = (nu.get("unitName") or "").lower()
        if unit != expect.lower():
            # ⚠️ Never silently coerce. A unit that changed is a thousandfold error waiting to ship.
            complaints.append(f"{field}: expected {expect}, got {unit!r} in fdcId {record.get('fdcId')}")
            continue
        out[field] = float(amount)
    return out


# ⚠️ Not a portion name. FNDDS uses it for "somebody ate some and did not say how much", and it
# would be picked first for 220 foods — a serving row reading "Quantity not specified".
PLACEHOLDER_LABELS = {"quantity not specified"}


def portion_label(p: dict) -> str:
    """The human name of a portion, from whichever field this dataset keeps it in.

    ⚠️ Measured across both datasets, and they are exact mirror images:

        FNDDS:  modifier numeric 22194/22194, portionDescription present 22194/22194
        SR:     modifier   word 14449/14449, portionDescription present        0

    So `modifier` is an internal numeric code in one and the label in the other. Joining the two
    fields — which the first version of this did — appended a meaningless five-digit code to every
    FNDDS label: "1 cup, cooked, diced 10049". They are alternatives, never parts of one name.
    """
    described = clean(p.get("portionDescription") or "")
    if described:
        return described
    modifier = clean(p.get("modifier") or "")
    # Defensive rather than observed: no SR modifier is numeric today, but a code rendered as a
    # portion name is indistinguishable from a real one on screen.
    if modifier and not modifier.replace(".", "").isdigit():
        return modifier
    unit = clean((p.get("measureUnit") or {}).get("name") or "")
    return "" if unit.lower() in ("undetermined", "") else unit


def serving_of(record: dict) -> tuple:
    """The first declared portion with a plausible weight and a real name."""
    portions = sorted(
        (p for p in record.get("foodPortions", []) if p.get("gramWeight")),
        key=lambda p: p.get("sequenceNumber") or 9999,
    )
    for p in portions:
        grams = float(p["gramWeight"])
        if not (MIN_SERVING_G <= grams <= MAX_SERVING_G):
            continue
        label = portion_label(p)
        if not label or label.lower() in PLACEHOLDER_LABELS:
            continue
        return grams, label
    return None, ""


def rows(data: dict, key: str, tag: str, complaints: list):
    for record in data.get(key, []):
        name = clean(record.get("description", ""))
        if not name:
            continue
        n = nutrients_of(record, complaints)
        # A record with no energy is not something anybody can log. FNDDS carries a handful
        # deliberately empty (human milk, with a footnote saying so).
        if not n.get("kcal") and not n.get("protein") and not n.get("fat") and not n.get("carb"):
            continue
        grams, label = serving_of(record)
        yield [
            f"{tag}{record.get('fdcId')}",
            name,
            clean(record.get("foodCategory", {}).get("description")
                  or (record.get("wweiaFoodCategory") or {}).get("wweiaFoodCategoryDescription") or ""),
            n.get("kcal", 0.0), n.get("protein", 0.0), n.get("fat", 0.0), n.get("carb", 0.0),
            n.get("fibre", 0.0), n.get("sugar", 0.0), n.get("satfat", 0.0), n.get("sodium", 0.0),
            "" if grams is None else grams,
            label,
        ]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="/tmp/usda", help="where the downloaded archives are kept")
    ap.add_argument("--out", default="app/src/main/assets/food/seed.tsv")
    args = ap.parse_args()

    work = pathlib.Path(args.work)
    work.mkdir(parents=True, exist_ok=True)
    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)

    complaints, lines, per_source = [], [], {}
    for url, key, tag in DATASETS:
        data = fetch(url, work)
        before = len(lines)
        for r in rows(data, key, tag, complaints):
            fields = [r[0], r[1], r[2]] + [
                # Two decimals is finer than any of these measurements, and halves the file.
                ("" if v == "" else f"{float(v):g}") for v in r[3:12]
            ] + [r[12]]
            line = "\t".join(str(f) for f in fields)
            assert line.count("\t") == 12, f"delimiter escaped into a field: {line[:120]!r}"
            lines.append(line)
        per_source[tag] = len(lines) - before

    if complaints:
        # ⚠️ Loud and fatal. A changed unit is exactly the failure this script exists to prevent, and
        # the one that would be invisible on screen — every food simply looks low in something.
        print(f"\n⚠️  {len(complaints)} unit mismatches — REFUSING to write the seed:", file=sys.stderr)
        for c in complaints[:10]:
            print("   ", c, file=sys.stderr)
        return 1

    lines.sort(key=lambda l: l.split("\t", 2)[1].lower())
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")

    size = out.stat().st_size
    print(f"\n{out}: {len(lines)} foods, {size / 1024:.0f} kB")
    for tag, n in per_source.items():
        print(f"   {tag:6} {n}")
    served = sum(1 for l in lines if l.split("\t")[11])
    print(f"   with a declared serving: {served} ({100 * served // len(lines)}%)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
