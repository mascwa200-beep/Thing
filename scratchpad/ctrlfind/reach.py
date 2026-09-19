#!/usr/bin/env python3
"""For each Settings control: can you find it by typing its own name?

A control lives inside one `vis(Sections.X)` gate. Its title is reachable if some word
of that title appears in the searchable text of the row that opens the page holding it —
that row's title, its filtered keywords, or its category's. Anything else is a control
you can only find by already knowing which page it is on.
"""
import re, sys, pathlib
sys.path.insert(0, "/home/user/Thing/scratchpad/ctrlfind")
root = pathlib.Path("/home/user/Thing")
SET = root / "app/src/main/java/dev/mascwa/pulse/feature/settings"

from measure import strip_comments, call_sites, CONTROLS   # reuse the self-checked extractor

scr_raw = (SET / "SettingsScreen.kt").read_text()
scr = strip_comments(scr_raw)

# ── section gates, in file order ────────────────────────────────────────────────
gates = [(m.start(), m.group(1))
         for m in re.finditer(r'vis\(\s*SettingsSections\.([A-Z_]+)\s*\)', scr)]
assert len(gates) >= 26, f"only {len(gates)} vis(Sections.X) gates found — extractor is wrong"

# ── the table: key -> (title, keywords, category) ──────────────────────────────
sec_src = strip_comments((SET / "SettingsSections.kt").read_text())
table = {}
for m in re.finditer(
        r'val ([A-Z_]+) = SettingsSection\(\s*"([a-z_]+)",\s*SettingsCategory\.([A-Z]+),\s*'
        r'"((?:[^"\\]|\\.)*)",\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)', sec_src):
    kw = " ".join(re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(5)))
    table[m.group(1)] = {"title": m.group(4), "kw": kw, "cat": m.group(3)}
assert len(table) == 26, f"parsed {len(table)} sections, expected 26"

cat_src = strip_comments((SET / "SettingsCategory.kt").read_text())
cats = {}
# NAME("title", "blurb", "tag", Icons.Filled.X,\n    "keywords"),  — keywords are the 5th POSITIONAL
for m in re.finditer(
        r'^    ([A-Z]+)\(\s*"((?:[^"\\]|\\.)*)",\s*"((?:[^"\\]|\\.)*)",\s*'
        r'"(?:[^"\\]|\\.)*",\s*Icons\.[A-Za-z.]+,\s*\n?\s*"((?:[^"\\]|\\.)*)"',
        cat_src, re.M):
    cats[m.group(1)] = {"title": m.group(2), "blurb": m.group(3), "kw": m.group(4)}
assert len(cats) == 10, f"parsed {len(cats)} categories, expected 10 — {sorted(cats)}"
print(f"parsed {len(table)} sections, {len(cats)} categories, {len(gates)} gates\n")

# ⚠️ Call sites BELOW the composable's own body are inside the private helper
# declarations at the bottom of the file (EditableValueRow's delegation, the four Add*Row
# builders). A position-based gate assignment files them under the LAST section, which is
# how my first run reported "Add symbol" as living in "Storage & about". The body ends at
# the first top-level declaration after the last gate.
import re as _re
_end = None
for _m in _re.finditer(r'^(private fun|@Composable|internal fun|fun )', scr, _re.M):
    if _m.start() > gates[-1][0]:
        _end = _m.start(); break
assert _end is not None, "could not find the end of the SettingsScreen body"
print(f"composable body ends at line {scr_raw[:_end].count(chr(10)) + 1}")

# ⚠️ Tokenise and match exactly as GuideSearch does, or the answer is not about the real
# scorer. fieldMatch splits on isLetterOrDigit boundaries, and wordMatch scores a PREFIX
# relation at >= 4 chars in BOTH directions — so "markets" finds "market". My first run
# used set membership, which is stricter, and wrongly reported eight Notifications rows
# unreachable when the category keywords carry "market".
MIN_STEM = 4
def words(s):
    """The tokens a query could plausibly be, for one piece of text.

    ⚠️ ALSO the hyphen-collapsed form, and only hyphens. `fieldMatch` splits "Wi-Fi" into
    "wi" and "fi", both under MIN_STEM, so neither can stem-match the keyword "wifi" — yet
    "wifi" is exactly what a person types. Periods are NOT collapsed: "NewsAPI.org" would
    become "newsapiorg", which nobody types.
    """
    low = s.lower()
    out = set(w for w in re.split(r'[^a-z0-9]+', low) if w)
    out |= set(w for w in re.split(r'[^a-z0-9-]+', low) if w for w in [w.replace('-', '')] if w)
    return out

def reaches(token, haystack):
    """Does any haystack word answer this token, as wordMatch would score it?"""
    if token in haystack:
        return True
    return len(token) >= MIN_STEM and any(
        len(w) >= MIN_STEM and (w.startswith(token) or token.startswith(w)) for w in haystack)

# ── assign every control to the gate it sits under ─────────────────────────────
rows = []
for name in CONTROLS:
    for off, args in call_sites(scr, name):
        t = (re.search(r'\btitle\s*=\s*"((?:[^"\\]|\\.)*)"', args)
             or re.match(r'\s*"((?:[^"\\]|\\.)*)"', args))
        if not t:
            continue
        sec = None
        for g_off, key in gates:
            if g_off < off:
                sec = key
            else:
                break
        if off > _end:
            continue                      # inside a private helper, not a real row
        rows.append({"control": name, "title": t.group(1), "sec": sec,
                     "line": scr_raw[:off].count('\n') + 1})

orphans = [r for r in rows if r["sec"] is None]
print(f"controls with a literal title: {len(rows)}   before the first gate: {len(orphans)}")

# ── reachable? ─────────────────────────────────────────────────────────────────
unreached, per_sec = [], {}
for r in rows:
    if r["sec"] is None:
        continue
    s = table[r["sec"]]; c = cats[s["cat"]]
    haystack = words(s["title"]) | words(s["kw"]) | words(c["title"]) | words(c["blurb"]) | words(c["kw"])
    tw = words(r["title"])
    hit = {t for t in tw if reaches(t, haystack)}
    per_sec.setdefault(r["sec"], []).append((r["title"], sorted(hit)))
    if not hit:
        unreached.append(r)

print(f"\nUNREACHABLE by their own name: {len(unreached)} of {len(rows) - len(orphans)}\n")
by = {}
for r in unreached:
    by.setdefault(r["sec"], []).append(r["title"])
for key in sorted(by):
    print(f"  {table[key]['title']}  ({table[key]['cat']})")
    for t in by[key]:
        print(f"      • {t}")
