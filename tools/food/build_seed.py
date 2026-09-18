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
⚠️ WHAT THIS COVERS FOR EATING OUT, measured over the built seed rather than assumed, because the
question comes up and the honest answer is specific.

  421 rows in "Fast Foods" and "Restaurant Foods", of which 161 name a chain:

      McDonald's (53)  KFC  Burger King  Subway  Taco Bell  Wendy's  Popeyes  Arby's
      Chick-fil-A  Pizza Hut  Domino's (10)  Denny's  Applebee's  Cracker Barrel
      T.G.I. Friday's  Olive Garden  Carrabba's  On The Border

  NOT here: Starbucks, Dunkin', Chipotle, Panera, Five Guys — nor anything outside the United
  States. That is the limit of the free data, not a selection: this script takes every record both
  USDA datasets publish, with no cap.

  The rest of the 421 are dishes rather than brands — "Cheeseburger, NFS", "Burrito bowl, chicken",
  "Pad Thai with meat", "Sushi roll, salmon" — and they are the ones that answer most "what did I
  eat out" questions, because a search names the food rather than the place.

⚠️ There is no comprehensive free source for chain menus beyond this. MenuStat was probed and
answers 502 from here (which says nothing certain about the site, only that this network cannot
reach it); Nutritionix is commercial and its terms forbid bulk caching, which this project will not
do. The app's empty-search copy therefore steers people toward the dish rather than the brand, and
QUICK ADD remains the path that always works.

⚠️ Deliberately NOT ALSO LISTED IN THE UI. A chain list in screen copy is a second statement of a
fact that lives in the data, and it drifts the moment either changes — the duplicated-definition
mistake this project has corrected several times over. The surface says the shape of the limit; this
file, beside the thing that produces it, says the specifics.

--------------------------------------------------------------------------------------------------
OUTPUT is a tab-separated file, not JSON, and that is a memory decision rather than a taste one.
Around 13,000 records parsed into objects at startup is several megabytes of heap held forever for a
screen most people open occasionally. A line-oriented file can be scanned as raw text and parsed only
on a hit — the same "reject cheaply, parse the survivors" discipline `SurvivalContentRepository`
already uses for guide bodies, where it keeps memory at O(one shard) whatever the corpus does.

Columns, in order:

    id  name  category  kcal  protein  fat  carb  fibre  sugar  satfat  sodium_mg  serving_g
    serving_label  calcium_mg  iron_mg  potassium_mg  vitamin_a_ug  vitamin_c_mg  vitamin_d_ug
    cholesterol_mg  trans_fat_g

⚠️ The eight micronutrient columns are appended at the END and are EMPTY where the source did not
record the nutrient — never zero. The count is asserted below and mirrored by
`FoodRepository.COLUMNS`; a truncated line read positionally would put a serving weight in the
sodium field, which is a wrong number rather than a missing one.

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

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import nutrient_set  # noqa: E402

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

    # --------------------------------------------------------------------- vitamins and minerals
    #
    # ⚠️ **Every one of these was chosen by MEASURING coverage over both datasets, not by picking
    # the nutrient that sounds right.** Percentages are of records in that dataset carrying the
    # field at all (zero is a measurement and counts):
    #
    #                        SR Legacy        FNDDS
    #     1087 calcium         98.9% mg      100.0% mg
    #     1089 iron            99.0% mg      100.0% mg
    #     1092 potassium       96.4% mg      100.0% mg
    #     1106 vitamin A       88.8% µg      100.0% µg   (retinol activity equivalents)
    #     1162 vitamin C       94.1% mg      100.0% mg
    #     1114 vitamin D       66.5% µg      100.0% µg
    #     1253 cholesterol     94.9% mg      100.0% mg
    #     1257 trans fat       53.6% g         0.0%     ← see the note below
    #
    # ⚠️ **VITAMIN A AND VITAMIN D EACH EXIST UNDER TWO IDS AND ONLY ONE OF EACH PAIR IS USABLE.**
    # FoodData Central publishes vitamin A as both 1104 (IU) and 1106 (µg RAE), and vitamin D as both
    # 1110 (IU) and 1114 (µg). Measured: FNDDS carries **1104 on zero records and 1110 on zero
    # records** — only the µg forms. Reaching for the IU id would therefore have silently lost the
    # entire FNDDS half of the corpus, 5,432 of 13,225 foods, and precisely the half this file's own
    # header argues is the more useful one ("foods as EATEN, described the way a person would").
    # `Micronutrients.Micro` declares µg for both, so the µg ids are also the ones that need no
    # conversion. Two reasons, same answer.
    1087: ("calcium", "mg"),
    1089: ("iron", "mg"),
    1092: ("potassium", "mg"),
    1106: ("vita", "µg"),
    1162: ("vitc", "mg"),
    1114: ("vitd", "µg"),
    1253: ("chol", "mg"),
    # ⚠️ **Trans fat is the one borderline call, and it is included deliberately.** Half the corpus
    # does not publish it at all, which would ordinarily argue for leaving the column out — a column
    # present on a handful of records renders as a measurement for the few and as silence for the
    # rest. It earns its place because the app ALREADY shows trans fat for barcoded products, where
    # measured coverage is 16.7%; omitting it here would leave the best data in the app with worse
    # coverage than the worst. `Micronutrients.Day.coverage` reports how much of a day a figure was
    # drawn from, which is what makes a partial column honest rather than misleading.
    1257: ("transfat", "g"),
}

# In the order the TSV writes them, after the macros. ⚠️ Kept as a list because the column ORDER is
# an on-disk contract with `FoodRepository.parseSeedLine`, and a dict comprehension over NUTRIENTS
# would tie it to declaration order in a file somebody will reorder one day.
MICRO_FIELDS = ["calcium", "iron", "potassium", "vita", "vitc", "vitd", "chol", "transfat"]

# ⚠️ **Imported from the branded builder rather than restated here, and the odd direction is
# deliberate.** USDA's nutrient NUMBERS are a fact about USDA, not about `NutrientSet.kt`, so
# `nutrient_set.py` — whose whole doc argues against a second table of the Kotlin's own facts — is
# the wrong home for them. A second copy in this file would be the drift this project has corrected
# six times, and the failure mode is the worst kind: magnesium written under phosphorus's column
# across thirteen thousand foods with nothing anywhere to notice. `build_food_db.py` derived that
# table against USDA's own CSVs and guards it at import — a name `NutrientSet.kt` does not declare
# is fatal there — so importing gets the validation as well as the table.
#
# ⚠️ Safe to import: everything at its module level is constants and that one guard, `main()` sits
# behind an `if __name__` check, and nothing is downloaded or opened until it is called.
from build_food_db import GRAMS_PER_USDA_UNIT, USDA_EXTRA_NUMBERS  # noqa: E402

EXTRA_BY_NAME = {n.name: n for n in nutrient_set.load()}

# ⚠️ **All twenty-nine, in ID order, and both halves of that matter.**
#
# By id rather than by declaration order because `NutrientSet.kt` gives every nutrient a permanent
# explicit id for exactly this reason — the column order here is an on-disk contract with
# `FoodRepository.seedExtras`, and an id cannot silently change when somebody alphabetises the enum.
# `MICRO_FIELDS` above has to be a hand-kept list precisely because `Micronutrients.Micro` has no
# such id; this set does, so it can be derived, and derived is better.
#
# ALL of them rather than only the twenty-six USDA publishes, because "which subset, in what order"
# would be a second implicit contract for the reader to get right. Three columns — added sugars,
# iodine, polyols — are empty on every row, which is ~40 kB of tabs and is what an unrecorded
# measurement is supposed to look like anyway. If USDA ever publishes them the column is waiting.
EXTRA_ORDER = sorted(EXTRA_BY_NAME.values(), key=lambda n: n.id)

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


def extras_of(record: dict, complaints: list) -> dict:
    """Every further nutrient this record states, in the unit `NutrientSet.kt` stores it in.

    ⚠️ **Keyed on the nutrient NUMBER, where [nutrients_of] above keys on the id, and the two
    conventions in one file are not an inconsistency.** That one has to use ids because `Energy`
    appears twice under one name; this one uses numbers because the number is what the branded
    builder's table speaks, and one shared table beats two that agree until they do not. Measured
    before relying on it: every one of the 997,140 nutrient entries across both datasets carries a
    `number`, so nothing is lost by keying on it.

    ⚠️ **The unit is converted through grams rather than assumed to match**, and exactly one of the
    twenty-six needs it: USDA publishes riboflavin in milligrams and this app stores micrograms, a
    thousandfold error in the direction that looks like a food simply containing none. Doing it from
    the declared unit rather than a per-nutrient exception list means the next mismatch is handled
    too. An unrecognised unit is a complaint, never a guess — `build_food_db.py` gives the same
    reason at greater length.
    """
    out = {}
    for n in record.get("foodNutrients", []):
        nu = n.get("nutrient", {})
        name = USDA_EXTRA_NUMBERS.get(str(nu.get("number")))
        if name is None:
            continue
        amount = n.get("amount")
        if amount is None:
            continue
        per_gram = GRAMS_PER_USDA_UNIT.get((nu.get("unitName") or "").lower())
        if per_gram is None:
            complaints.append(
                f"{name}: unrecognised unit {nu.get('unitName')!r} in fdcId {record.get('fdcId')}"
            )
            continue
        out[name] = float(amount) * per_gram * EXTRA_BY_NAME[name].per_gram
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
        e = extras_of(record, complaints)
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
            # ⚠️ **A micronutrient nobody measured is written EMPTY, never as zero, and the macros
            # above are deliberately the other way round.** A missing macro is effectively zero for
            # logging and `NutritionDay.Nutrients` has no absent state; a missing micronutrient is
            # the whole reason `Micronutrients.Amounts` is a map. Three product records in four say
            # nothing about calcium, and rendering those as "0 mg" would put a measurement nobody
            # took on screen as confidently as one they did — and sum it into a day's total while
            # presenting that total as complete.
            *["" if n.get(f) is None else n[f] for f in MICRO_FIELDS],
            # ⚠️ Same rule again for the twenty-nine further nutrients: recorded or empty, never
            # zero. The density here is nothing like the barcoded corpus — measured over both
            # datasets, 68% of these cells carry a real laboratory figure, against roughly 2% for
            # the same nutrients on Open Food Facts. Generic foods are analysed; packets are typed in.
            *["" if e.get(x.name) is None else e[x.name] for x in EXTRA_ORDER],
        ]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="/tmp/usda", help="where the downloaded archives are kept")
    ap.add_argument("--out", default="core/health/src/main/assets/food/seed.tsv")
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
            # Two decimals is finer than any of these measurements, and halves the file. ⚠️ An empty
            # value stays empty — see the micronutrient note in `rows`; `0.0 == ""` is False in
            # Python, so a genuine recorded zero still formats as "0" and only an absence is blank.
            def fmt(v):
                return "" if v == "" else f"{float(v):g}"

            # ⚠️ **The numeric slices are EXPLICIT and that is exactly how the micronutrient columns
            # nearly shipped as nothing.** This built `r[3:12] + [r[12]]` and stopped, so the eight new
            # fields `rows` was already producing were discarded on the way out — and the only thing
            # that caught it was the tab-count assertion below, which is why that assertion is worth
            # keeping even though it reads like a formality about delimiters.
            fields = (
                [r[0], r[1], r[2]]
                + [fmt(v) for v in r[3:12]]
                + [r[12]]
                + [fmt(v) for v in r[13:21]]
                + [fmt(v) for v in r[21:]]
            )
            line = "\t".join(str(f) for f in fields)
            # ⚠️ **Derived from the two nutrient lists, not a literal, and it still catches the
            # failure it was written for.** A slice left off the assembly above makes the count
            # short and this fires — which is precisely what happened to the micronutrients — while
            # a tab that escaped into a field makes it long. Deriving it means the number cannot go
            # stale as either set grows, which a hand-written 49 certainly would.
            expected = 13 + len(MICRO_FIELDS) + len(EXTRA_ORDER) - 1
            assert line.count("\t") == expected, (
                f"expected {expected} tabs, got {line.count(chr(9))}: {line[:120]!r}"
            )
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
    # ⚠️ Per-micronutrient coverage, printed rather than assumed. A column present on a handful of
    # records renders as a measurement for the few and as silence for the rest, so the number that
    # decides whether a column belongs here has to be visible in the build that produced it.
    print("   micronutrients recorded:")
    for i, field in enumerate(MICRO_FIELDS):
        col = 13 + i
        n = sum(1 for l in lines if l.split("\t")[col])
        print(f"     {field:10} {n:6} ({100 * n // len(lines):3d}%)")
    # ⚠️ The same figure for the twenty-nine, for the same reason: what decides whether a column
    # belongs in a shipped asset is its measured density, and that number has to be visible in the
    # build that produced it rather than remembered from the session that added it.
    print("   further nutrients recorded:")
    base = 13 + len(MICRO_FIELDS)
    filled = 0
    for i, nut in enumerate(EXTRA_ORDER):
        n = sum(1 for l in lines if l.split("\t")[base + i])
        filled += n
        print(f"     {nut.name.lower():22} {n:6} ({100 * n // len(lines):3d}%) {nut.symbol}")
    cells = len(lines) * len(EXTRA_ORDER)
    print(f"   further-nutrient cells filled: {filled} of {cells} ({100 * filled // cells}%)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
