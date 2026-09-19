#!/usr/bin/env python3
"""Do the new words make the Settings box return more than the section that owns them?

`vis` is a plain SUBSTRING match over `cat.title + cat.keywords + s.keywords + s.title`, so a
short word can hit a section that never mentions it — and the CONTENT pollution constraint this
table exists to respect is exactly that failure. Measured, not reasoned about.
"""
import re, sys, pathlib
sys.path.insert(0, "/home/user/Thing/scratchpad/ctrlfind")
import reach  # runs the measurement and leaves table/cats parsed

NEW = """operating exploit tagging coverage story outlets cluster board news agenda eaten
takeover provisioned notify control probe newsapi fred eia finnhub openweathermap nasa
allergies medications conditions notes name reflect audit ledger anchor python acoustic
interrogator crowd density remember events change""".split()

hay = {}
for key, s in reach.table.items():
    c = reach.cats[s["cat"]]
    hay[key] = f"{c['title']} {c['kw']} {s['kw']} {s['title']}".lower()

print(f"{len(NEW)} new words, against the real vis() haystack of all 26 sections\n")
bad = []
for w in sorted(set(NEW)):
    hits = [k for k, h in hay.items() if w in h]
    if len(hits) > 1:
        bad.append((w, hits))
        print(f"  {w:<16} {len(hits)} sections: {', '.join(reach.table[k]['title'] for k in hits)}")
print(f"\n{len(bad)} of {len(set(NEW))} new words reach more than one section.")
print("(More than one is not automatically wrong — two sections can genuinely both be about a")
print(" word. What would be wrong is a word reaching a section that does not mention it.)")
