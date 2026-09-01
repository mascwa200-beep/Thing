"""The 29 further nutrients, read out of the Kotlin that defines them.

⚠️ **There is no table of these in Python, deliberately.** `NutrientSet.kt` declares each nutrient's
permanent id, unit, source column and typical value, and the app reads every one of those at
runtime; a second table here would be the same facts twice, and this project has corrected that
mistake six times — four drifted copies of a colour palette, three of a day boundary, two of a
"how old is this" sentence. The failure mode for THIS one is the worst of the set: the builder
writing magnesium under phosphorus's id, in an asset of millions of rows, with nothing anywhere to
notice.

`tools/kb/ci_parity_lint.py` already does exactly this for the guide-category allowlist, so the
pattern is the house one rather than a new idea.

⚠️ The parse is deliberately strict and LOUD. A regex that silently matches nothing would leave the
builder writing an empty side table and a green build shipping a feature that does nothing, which
is the one outcome worse than a crash.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

KOTLIN = (
    Path(__file__).resolve().parents[2]
    / "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/NutrientSet.kt"
)

# GRAM("g", 1.0, 100_000),
_UNIT = re.compile(r'^\s{8}([A-Z]+)\("([^"]+)",\s*([0-9_.]+),\s*([0-9_]+)\),\s*$', re.M)

# ADDED_SUGARS(1, "Added sugars", Unit.GRAM, Group.CARBOHYDRATE, "added-sugars", 7.89),
_NUTRIENT = re.compile(
    r'^\s{8}([A-Z0-9_]+)\((\d+),\s*"([^"]+)",\s*Unit\.([A-Z]+),\s*Group\.([A-Z]+),\s*'
    r'"([^"]+)",\s*([0-9.]+)\),\s*$',
    re.M,
)

INT_MAX = 2_147_483_647


@dataclass(frozen=True)
class Nutrient:
    name: str
    id: int
    label: str
    unit: str          # the enum constant: GRAM / MILLIGRAM / MICROGRAM
    symbol: str        # g / mg / ug
    per_gram: float    # how many of `unit` are in one gram
    scale: int         # stored value is the figure in `unit`, times this
    group: str
    off_field: str     # the Open Food Facts column stem, without the _100g suffix
    typical: float

    @property
    def max_per_100g(self) -> float:
        """A hundred grams of food holds at most a hundred grams of anything."""
        return 100.0 * self.per_gram

    @property
    def stored_ceiling(self) -> int:
        """⚠️ Capped at INT_MAX because Room reads the column as a 32-bit Int."""
        return int(min(self.max_per_100g * self.scale, INT_MAX))

    def store(self, grams: float | None) -> int | None:
        """A figure a source published in GRAMS, as the integer the column holds, or None.

        ⚠️ The whole conversion in one place: grams into the nutrient's own unit, refused if it
        outweighs the food, refused again if it would not survive the column. The Kotlin twin is
        `NutrientSet.fromGrams` / `sane` / `store` and the two must agree.
        """
        if grams is None:
            return None
        try:
            g = float(grams)
        except (TypeError, ValueError):
            return None
        if g != g or g < 0.0:            # NaN or negative
            return None
        v = g * self.per_gram
        if v > self.max_per_100g:        # a constituent cannot outweigh the food
            return None
        n = int(round(v * self.scale))
        if n > self.stored_ceiling:
            return None
        return n



def _declared_constants(src: str, enum: str) -> list[str]:
    """Every constant declared in `enum class <enum>`, found without the shape the parsers assume.

    ⚠️ The point is that it uses a DIFFERENT rule from [_NUTRIENT] and [_UNIT] — any line inside the
    enum body starting with an upper-case identifier and a bracket. Two rules that agree is evidence;
    one rule checking itself is not.
    """
    start = src.index(f"enum class {enum}(")
    body = src.index("{", src.index(") {", start))
    depth, i = 0, body
    while i < len(src):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                break
        elif src[i] == ";" and depth == 1:
            break
        i += 1
    inner = src[body + 1:i]
    return re.findall(r"^\s+([A-Z][A-Za-z0-9_]*)\s*\(", inner, re.M)


def load(path: Path = KOTLIN) -> list[Nutrient]:
    src = path.read_text(encoding="utf-8")

    units = {}
    for m in _UNIT.finditer(src):
        units[m.group(1)] = (m.group(2), float(m.group(3).replace("_", "")), int(m.group(4).replace("_", "")))
    missed_units = [u for u in _declared_constants(src, "Unit") if u not in units]
    if missed_units or len(units) < 3:
        raise SystemExit(
            f"nutrient_set: units declared but not parsed: {missed_units} (parsed {len(units)}).\n"
            "  The Unit enum's declaration shape changed. Fix this parser rather than the enum —\n"
            "  the Kotlin is the source of truth and the builder is the reader."
        )

    out = []
    for m in _NUTRIENT.finditer(src):
        name, nid, label, unit, group, off, typical = m.groups()
        if unit not in units:
            raise SystemExit(f"nutrient_set: {name} names Unit.{unit}, which was not parsed.")
        symbol, per_gram, scale = units[unit]
        out.append(Nutrient(name, int(nid), label, unit, symbol, per_gram, scale, group, off, float(typical)))

    # ⚠️ **A COMPLETENESS CHECK, not a floor, and the negative test is why.** The first version of
    # this refused only when fewer than twenty nutrients parsed — and reshaping ONE declaration
    # (an extra space before its bracket) yielded twenty-eight with no complaint at all. That is
    # the likeliest edit anybody will ever make to that file, and it would have silently dropped a
    # nutrient from the shipped database while every gate stayed green.
    #
    # So the count is compared against the constants that are actually DECLARED, found by a
    # different rule: any line in the enum body beginning with an upper-case identifier and a
    # bracket. If one of those did not parse, this says which.
    declared = _declared_constants(src, "Nutrient")
    parsed = {n.name for n in out}
    missed = [d for d in declared if d not in parsed]
    if missed or len(out) < 20:
        raise SystemExit(
            f"nutrient_set: {len(declared)} nutrients are declared in {path.name} and {len(out)} "
            f"parsed.\n"
            f"  unparsed: {missed}\n"
            "  A silent under-parse ships a short side table in a green build, so this is a hard\n"
            "  failure. Keep the declarations one per line in the fixed order:\n"
            "    NAME(id, \"Label\", Unit.X, Group.Y, \"off-field\", typical),"
        )

    ids = [n.id for n in out]
    if len(set(ids)) != len(ids):
        raise SystemExit("nutrient_set: two nutrients share an id — the ids are permanent.")
    fields = [n.off_field for n in out]
    if len(set(fields)) != len(fields):
        raise SystemExit("nutrient_set: two nutrients read one Open Food Facts column.")
    return out


if __name__ == "__main__":
    ns = load()
    print(f"{len(ns)} nutrients from {KOTLIN.name}\n")
    print(f"{'id':>3}  {'name':<22} {'unit':<4} {'scale':>8} {'ceiling':>12}  {'typical':>10}  off column")
    for n in ns:
        print(f"{n.id:>3}  {n.name:<22} {n.symbol:<4} {n.scale:>8} {n.stored_ceiling:>12}  "
              f"{n.typical:>10}  {n.off_field}_100g")
