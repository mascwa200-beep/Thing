"""Negative-test every load-bearing rule in Fallacies.kt.

A test proves nothing until it has been watched failing. Each perturbation below REMOVES a rule
(it must not merely touch it) and asserts it matched the source first — three separate ways a
green negative test has lied in this repo already.
"""
import subprocess, sys, shutil, os
SRC = "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Fallacies.kt"
BAK = SRC + ".bak"

PERTURBATIONS = [
    ("MIN_WORDS floor removed",
     "if (text.split(WHITESPACE).size < MIN_WORDS) return emptyList()",
     "if (false) return emptyList()"),
    ("word-boundary anchoring removed",
     'Regex("\\\\b" + it + "\\\\b", RegexOption.IGNORE_CASE)',
     'Regex(it, RegexOption.IGNORE_CASE)'),
    ("APOS relaxed back to an optional apostrophe",
     'private const val APOS = "[\'’]"',
     'private const val APOS = "[\'’]?"'),
    ("confidence cap removed",
     "val confidence = minOf(boosted, MAX_CONFIDENCE)",
     "val confidence = boosted"),
    ("multi-cue bonus removed",
     "val boosted = f.weight + (hits.size - 1) * MULTI_CUE_BONUS",
     "val boosted = f.weight"),
    ("minConfidence floor ignored",
     "if (confidence >= minConfidence) out += Candidate(f, confidence, hits.first())",
     "out += Candidate(f, confidence, hits.first())"),
    ("best-first ordering removed",
     "return out.sortedByDescending { it.confidence }",
     "return out"),
]

shutil.copy(SRC, BAK)
orig = open(SRC).read()
fails = 0
try:
    for name, find, repl in PERTURBATIONS:
        assert find in orig, "PERTURBATION DID NOT MATCH THE SOURCE: " + name
        open(SRC, "w").write(orig.replace(find, repl, 1))
        r = subprocess.run(["bash", "scratchpad/kb/runcore.sh",
                            "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/FallaciesTest.kt"],
                           capture_output=True, text=True)
        out = r.stdout + r.stderr
        failed = [l.split("(")[0].strip() for l in out.splitlines()
                  if l and l[0].isdigit() and ") " in l and "(dev.mascwa" in l]
        if failed:
            print("  ASLEEP? no -> %-46s breaks: %s" % (name, ", ".join(f.split(") ")[1] for f in failed)))
        else:
            print("  ⚠️ GUARD ASLEEP  -> %s : nothing failed" % name); fails += 1
finally:
    shutil.move(BAK, SRC)
print("\nasleep guards:", fails)
sys.exit(1 if fails else 0)
