import re, pathlib
ROOT = pathlib.Path("/home/user/Thing")
DIR = ROOT / "desktop/src/main/kotlin/dev/mascwa/pulse/desktop/Directory.kt"
d = DIR.read_text()

# pull the Settings DeskEntry, paren-balanced
def balanced(t, start):
    dep, i = 0, start
    while i < len(t):
        if t[i] == '(': dep += 1
        elif t[i] == ')':
            dep -= 1
            if dep == 0: return i
        i += 1
    return -1
m = re.search(r'DeskEntry\(Screen\.SETTINGS\s*,', d)
assert m, "could not find the Settings DeskEntry"
span = d[m.end()-1: balanced(d, m.end()-1)+1]
lits = re.findall(r'"([^"]*)"', span)
assert "Settings" in lits, f"self-check failed, saw {lits}"
hay_text = " ".join(lits)
print("Settings entry haystack:", hay_text, "\n")

def tokens(text):
    low = text.lower()
    plain = [w for w in re.split(r'[^a-z0-9]+', low) if w]
    joined = [w.replace('-', '') for w in re.split(r'[^a-z0-9-]+', low) if w.strip('-')]
    return set(plain) | set(t for t in joined if t)

hay = tokens(hay_text)

def reaches(tok, hay):
    if tok in hay: return True
    if len(tok) < 4: return False
    return any(len(h) >= 4 and (h.startswith(tok) or tok.startswith(h)) for h in hay)

SECTIONS = ["WHERE YOU ARE","UNITS","YOUR COUNTRY","DATA & REFRESH","THE LONG WATCH","LIBRARY & UPDATES","STANDBY DISPLAY"]
CONTROLS = ["Place","Latitude","Longitude","GUESS FROM MY CONNECTION","FORGET WHAT I TYPED",
            "Fahrenheit","Miles and feet","12-hour clock","Country code",
            "Refresh when a screen opens",
            "Keep recording",
            "Community TV channels","Look for a newer build on launch",
            "Send fault reports so they can be read and fixed","GitHub token (read-only)","EIA key (optional)",
            "Keep it in front","Use it as the screensaver","Put it on the lock screen"]
STOP = {"you","are","the","and","it","in","on","so","a","for","they","can","be","of","my","from","what","i"}

def report(kind, items):
    bad = []
    for it in items:
        toks = [t for t in tokens(it) if len(t) > 2 and t not in STOP]
        if not toks: continue
        if not any(reaches(t, hay) for t in toks):
            bad.append((it, toks))
    print(f"{kind}: {len(bad)} of {len(items)} reachable by NO word of their own name")
    for it, toks in bad:
        print(f"    {it}   [{' '.join(toks)}]")
    return bad

print()
b1 = report("SECTIONS", SECTIONS)
print()
b2 = report("CONTROLS", CONTROLS)
