"""Negative-test every load-bearing rule across the four interrogator cores.

Each perturbation REMOVES a rule (not merely touches it) and asserts it matched the source before
running — the three ways a green negative test has already lied in this repo. A rule whose removal
breaks nothing is reported as ASLEEP.
"""
import subprocess, sys, shutil
B = "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/"
T = "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/"
TESTS = [T+n for n in ("FallaciesTest.kt","DiscourseTest.kt","RebuttalTest.kt","TranscriptPolicyTest.kt")]

P = [
 # ---- Discourse ----
 ("Discourse.kt","backchannel rule removed",
  "val backchannel = words.size <= BACKCHANNEL_MAX_WORDS && BACKCHANNEL.containsMatchIn(trimmed)",
  "val backchannel = false"),
 ("Discourse.kt","filler removal removed",
  ".filter { it !in FILLER }.toList()", ".toList()"),
 ("Discourse.kt","rhetorical weight removed",
  "if (rhetorical) s += RHETORICAL_WEIGHT", "if (false) s += RHETORICAL_WEIGHT"),
 ("Discourse.kt","question penalty removed",
  "if (question) s -= QUESTION_PENALTY", "if (false) s -= QUESTION_PENALTY"),
 ("Discourse.kt","HEDGED refusal removed",
  "if (claim.hedged) return Decision(Verdict.HEDGED, claim, candidate, counted)", ""),
 ("Discourse.kt","repeat counted only on escalation",
  "        // Count it the moment it is screened, whatever happens next.\n        val counted = state.copy(seen = state.seen + (candidate.fallacy.id to (state.seen[candidate.fallacy.id] ?: 0) + 1))",
  "        val counted = state"),
 ("Discourse.kt","cooldown removed",
  "        if (last != null && nowMs - last < COOLDOWN_MS) {\n            return Decision(Verdict.COOLING_DOWN, claim, candidate, counted)\n        }",""),
 ("Discourse.kt","hourly ceiling removed",
  "        if (withinHour.size >= MAX_PER_WINDOW) {\n            return Decision(Verdict.RATE_LIMITED, claim, candidate, counted.copy(recentMs = withinHour))\n        }",""),
 ("Discourse.kt","merge span cap removed",
  "if (open && gap in 0..MAX_GAP_MS && span <= MAX_MERGE_MS) {",
  "if (open && gap in 0..MAX_GAP_MS) {"),
 ("Discourse.kt","merge gap check removed",
  "if (open && gap in 0..MAX_GAP_MS && span <= MAX_MERGE_MS) {",
  "if (open && span <= MAX_MERGE_MS) {"),
 # ---- Rebuttal ----
 ("Rebuttal.kt","provenance always claims the model ran",
  "        val provenance = when {\n            draft != null -> Provenance.REASONED\n            grounding != null -> Provenance.GROUNDED\n            else -> Provenance.PATTERN_ONLY\n        }",
  "        val provenance = Provenance.REASONED"),
 ("Rebuttal.kt","blank draft accepted as a draft",
  "val draft = modelDraft?.trim()?.takeIf { it.isNotEmpty() }", "val draft = modelDraft"),
 ("Rebuttal.kt","repeat floor removed",
  "        timesSeen < REPEAT_FLOOR -> null", "        false -> null"),
 ("Rebuttal.kt","abbreviation rule removed",
  "                    if (i - start > ABBREVIATION_MAX) return i", "                    return i"),
 ("Rebuttal.kt","unpunctuated draft discarded instead of truncated",
  "        val lastSpace = window.lastIndexOf(' ')\n        return (if (lastSpace > limit / 2) window.substring(0, lastSpace) else window).trimEnd() + \"…\"",
  "        return \"\""),
 # ---- TranscriptPolicy ----
 ("TranscriptPolicy.kt","long digit run no longer refused",
  "        if (LONG_DIGIT_RUN.containsMatchIn(text)) return true", ""),
 ("TranscriptPolicy.kt","named-secret refusal removed",
  "        return SECRET_CONTEXT.containsMatchIn(text) && SHORT_CODE.containsMatchIn(text)", "        return false"),
 ("TranscriptPolicy.kt","digits masked before credential shapes",
  "        for (p in CREDENTIAL_SHAPES) out = p.replace(out, MASK)\n        out = Regex(\"\\\\b\\\\d{4,}\\\\b\").replace(out, MASK)",
  "        out = Regex(\"\\\\b\\\\d{4,}\\\\b\").replace(out, MASK)\n        for (p in CREDENTIAL_SHAPES) out = p.replace(out, MASK)"),
 ("TranscriptPolicy.kt","retention window removed",
  "        val fresh = entries.filter { nowMs - it.atMs < windowMs }", "        val fresh = entries"),
 ("TranscriptPolicy.kt","retention count cap removed",
  "        return if (fresh.size <= maxEntries) fresh else fresh.takeLast(maxEntries)", "        return fresh"),
 ("TranscriptPolicy.kt","admit stores an empty row instead of refusing",
  "        if (t.isEmpty() || isSensitive(t)) return null", "        if (false) return null"),
]

asleep = 0
for f, name, find, repl in P:
    src = B + f
    orig = open(src).read()
    if find not in orig:
        print("  ⚠️ PERTURBATION DID NOT MATCH THE SOURCE -> %s" % name); asleep += 1; continue
    shutil.copy(src, src + ".bak")
    try:
        open(src, "w").write(orig.replace(find, repl, 1))
        r = subprocess.run(["bash", "scratchpad/kb/runcore.sh"] + TESTS, capture_output=True, text=True)
        out = r.stdout + r.stderr
        broke = [l.split(") ", 1)[1].split("(")[0] for l in out.splitlines()
                 if l[:1].isdigit() and ") " in l and "(dev.mascwa" in l]
        if "OK (" in out and not broke:
            print("  ⚠️ GUARD ASLEEP -> %-52s nothing failed" % name); asleep += 1
        elif not broke:
            print("  (compile error, rule is load-bearing) -> %s" % name)
        else:
            print("  ok -> %-52s breaks: %s" % (name, ", ".join(sorted(set(broke)))))
    finally:
        shutil.move(src + ".bak", src)
print("\nasleep guards:", asleep)
sys.exit(1 if asleep else 0)
