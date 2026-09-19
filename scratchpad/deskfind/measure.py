import re, sys, pathlib

ROOT = pathlib.Path("/home/user/Thing")
SRC = ROOT / "desktop/src/main/kotlin/dev/mascwa/pulse/desktop/feature/settings/SettingsScreen.kt"
src = SRC.read_text()

# ---- self-check: the extractor must SEE things I know are present, before its silence means anything
KNOWN_SECTIONS = ["WHERE YOU ARE", "UNITS", "YOUR COUNTRY", "DATA & REFRESH", "THE LONG WATCH", "STANDBY DISPLAY"]
KNOWN_CONTROLS = ["Fahrenheit", "Country code", "Keep recording", "Place", "Latitude"]

def strip_comments(t):
    # strings first is wrong; do a tiny lexer: skip over string literals so a // inside one survives
    out, i, n = [], 0, len(t)
    while i < n:
        ch = t[i]
        if ch == '"' and t[i:i+3] != '"""':
            j = i + 1
            while j < n and t[j] != '"':
                if t[j] == '\\': j += 1
                j += 1
            out.append(t[i:j+1]); i = j + 1; continue
        if t[i:i+2] == '//':
            j = t.find('\n', i); j = n if j < 0 else j
            out.append(' ' * (j - i)); i = j; continue
        if t[i:i+2] == '/*':
            j = t.find('*/', i); j = n if j < 0 else j + 2
            out.append(' ' * (j - i)); i = j; continue
        out.append(ch); i += 1
    return ''.join(out)

code = strip_comments(src)

sections = re.findall(r'SectionTitle\("([^"]+)"\)', code)
assert sections, "extractor saw no sections at all"
for k in KNOWN_SECTIONS:
    assert k in sections, f"self-check failed: section {k!r} not seen (saw {sections})"

# a control = a labelled, actionable row. label is either `label = "X"` or the first positional string.
CTRLS = ["LcarsSwitch", "LcarsTextField", "NumberField", "LcarsButton", "LcarsGhostButton"]
def balanced(text, start):
    d, i = 0, start
    while i < len(text):
        if text[i] == '(': d += 1
        elif text[i] == ')':
            d -= 1
            if d == 0: return i
        i += 1
    return -1

rows = []
for name in CTRLS:
    for m in re.finditer(r'\b' + name + r'\s*\(', code):
        at = m.end() - 1
        # skip the DECLARATION, generic or not
        if re.search(r'\bfun\s+(<[^>]*>\s*)?$', code[:m.start()]):
            continue
        end = balanced(code, at)
        if end < 0: continue
        span = code[at+1:end]
        lab = re.search(r'label\s*=\s*"([^"]*)"', span)
        if not lab:
            lab = re.match(r'\s*"([^"]*)"', span)
        if not lab: continue
        # which section is it in? the last SectionTitle before it
        prev = [s for s in re.finditer(r'SectionTitle\("([^"]+)"\)', code) if s.start() < m.start()]
        sec = prev[-1].group(1) if prev else "(none)"
        rows.append((sec, name, lab.group(1)))

labels = [r[2] for r in rows]
for k in KNOWN_CONTROLS:
    assert k in labels, f"self-check failed: control {k!r} not seen"

print(f"self-check passed: {len(sections)} sections, {len(rows)} controls\n")
for s in sections:
    mine = [r for r in rows if r[0] == s]
    print(f"{s}  ({len(mine)})")
    for _, kind, lab in mine:
        print(f"    {lab}")
orphan = [r for r in rows if r[0] == "(none)"]
if orphan:
    print("\n(none):")
    for _, kind, lab in orphan: print(f"    {lab}")
