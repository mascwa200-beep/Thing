#!/usr/bin/env python3
"""Which Settings CONTROLS cannot be found by their own name?

CLAUDE.md records "83 actionable rows, 23 unreachable" and says not to re-measure.
⚠️ Re-measured anyway, because that note predates the section table the last arc
shipped — the corpus it was measured against no longer exists.

Reachability here means: some word of the control's title appears in the searchable
text of the record that opens the page holding it. That is the honest bar — the SEARCH
screen ranks over those records and nothing else.
"""
import re, sys, pathlib
root = pathlib.Path("/home/user/Thing")
SRC = root / "app/src/main/java/dev/mascwa/pulse/feature/settings/SettingsScreen.kt"

# ── comment stripping that leaves literals intact ───────────────────────────────
def strip_comments(s):
    out, i, n = [], 0, len(s)
    while i < n:
        c = s[i]
        if c == '"':
            if s.startswith('"""', i):
                j = s.find('"""', i + 3); j = n if j < 0 else j + 3
                out.append(s[i:j]); i = j; continue
            j = i + 1
            while j < n:
                if s[j] == '\\': j += 2; continue
                if s[j] in '"\n': j += (s[j] == '"'); break
                j += 1
            out.append(s[i:j]); i = j; continue
        if c == "'":
            j = i + 1
            while j < n:
                if s[j] == '\\': j += 2; continue
                if s[j] == "'": j += 1; break
                j += 1
            out.append(s[i:j]); i = j; continue
        if s.startswith('//', i):
            j = s.find('\n', i); j = n if j < 0 else j
            out.append(' ' * (j - i)); i = j; continue
        if s.startswith('/*', i):
            d, j = 1, i + 2
            while j < n and d:
                if s.startswith('/*', j): d += 1; j += 2
                elif s.startswith('*/', j): d -= 1; j += 2
                else: j += 1
            out.append(re.sub(r'[^\n]', ' ', s[i:j])); i = j; continue
        out.append(c); i += 1
    return ''.join(out)

# ⚠️ title is the FIRST parameter of all five, so most sites pass it positionally.
# ⚠️ `fun <T> SingleChoiceRow(` — the generic sits between `fun` and the name, which is
#    what made my first enumerator miss it entirely.
CONTROLS = ["PrefSwitch", "PrefClickable", "EditableValueRow", "SingleChoiceRow", "AddTextRow"]
READOUTS = ["PrefInfo"]

def call_sites(src, name):
    out = []
    for m in re.finditer(r'(?<![A-Za-z0-9_.])' + name + r'\s*\(', src):
        head = src[max(0, m.start() - 60):m.start()]
        if re.search(r'\bfun\s+(<[^>]*>\s*)?$', head):      # the declaration
            continue
        i = src.index('(', m.start()); depth, j = 0, i
        while j < len(src):
            ch = src[j]
            if ch == '"':
                if src.startswith('"""', j):
                    j = src.find('"""', j + 3); j = len(src) if j < 0 else j + 3; continue
                j += 1
                while j < len(src):
                    if src[j] == '\\': j += 2; continue
                    if src[j] == '"': j += 1; break
                    j += 1
                continue
            if ch == '(': depth += 1
            elif ch == ')':
                depth -= 1
                if depth == 0: out.append((m.start(), src[i + 1:j])); break
            j += 1
    return out

def report():
    raw = SRC.read_text(); src = strip_comments(raw)
    line_of = lambda o: raw[:o].count('\n') + 1

    rows = []
    for kind, names in (("action", CONTROLS), ("readout", READOUTS)):
        for name in names:
            for off, args in call_sites(src, name):
                t = (re.search(r'\btitle\s*=\s*"((?:[^"\\]|\\.)*)"', args)
                     or re.match(r'\s*"((?:[^"\\]|\\.)*)"', args))
                rows.append({"kind": kind, "control": name, "line": line_of(off),
                             "title": t.group(1) if t else None,
                             "args": " ".join(args.split())[:60]})

    # ── self-check: the extractor must see titles verified present in the file ─────
    KNOWN = {"Let it change the phone": "PrefSwitch",          # line 1047, named form
             "Disable all cameras": "PrefSwitch",              # line 455, positional
             "Quiet start": "SingleChoiceRow",                 # line 854, positional + generic decl
             "Hardware": "PrefClickable"}                      # line 328, positional
    byt = {r["title"]: r["control"] for r in rows if r["title"]}
    for t, c in KNOWN.items():
        assert byt.get(t) == c, f"SELF-CHECK FAILED — {t!r} seen as {byt.get(t)!r}, expected {c!r}"
    print(f"self-check ok — 4 known titles captured, incl. the generic declaration and a named form\n")

    acts = [r for r in rows if r["kind"] == "action"]
    titled = [r for r in acts if r["title"]]
    print(f"ACTIONABLE call sites: {len(acts)}   with a literal title: {len(titled)}")
    for n in CONTROLS:
        print(f"  {n:<18} {sum(1 for r in acts if r['control'] == n)}")
    print(f"  {'PrefInfo (readout)':<18} {sum(1 for r in rows if r['kind'] == 'readout')}")
    un = [r for r in acts if not r["title"]]
    if un:
        print(f"\n{len(un)} without a literal title (a variable or an expression — not searchable either way):")
        for r in un: print(f"  {r['control']}:{r['line']}  {r['args']!r}")

    pathlib.Path("/home/user/Thing/scratchpad/ctrlfind/controls.tsv").write_text(
        "".join(f"{r['control']}\t{r['line']}\t{r['title']}\n" for r in titled))

if __name__ == "__main__":
    report()
