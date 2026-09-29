#!/usr/bin/env python3
"""Negative tests for app/.../widget/Agenda.kt. Baseline asserted green first; each perturbation must
match exactly once; the source is restored in `finally` AND byte-compared after every case."""
import subprocess, sys, shutil, re
ROOT = "/home/user/Thing"
SRC = f"{ROOT}/app/src/main/java/dev/mascwa/pulse/widget/Agenda.kt"
TEST = f"{ROOT}/app/src/test/java/dev/mascwa/pulse/widget/AgendaTest.kt"
RUN = sys.argv[0].rsplit('/', 1)[0] + "/run.sh"
orig = open(SRC).read()

def run():
    p = subprocess.run([RUN, TEST, SRC], capture_output=True, text=True, cwd=ROOT)
    return p.stdout + p.stderr

CASES = [
 ("all_day_local", "if (e.allDay) Math.floorDiv(e.startMs, DAY_MS) else localDay(e.startMs, offsetAt)", "localDay(e.startMs, offsetAt)"),
 ("truncating_div", "Math.floorDiv(ms + offsetAt(ms), DAY_MS)", "(ms + offsetAt(ms)) / DAY_MS"),
 ("keep_ended", ".filterNot { ended(it, nowMs, offsetAt) }", ".filterNot { false }"),
 ("many_next", "p === next -> State.NEXT", "p.e.startMs > nowMs -> State.NEXT"),
 ("midnight_no_refine", "val refined = utcMidnight - offsetAt(guess)", "val refined = guess"),
 ("midnight_no_day_test", "return listOf(refined, guess).filter { localDay(it, offsetAt) == epochDay }.minOrNull() ?: refined", "return refined"),
 ("festival_on_first_day", "Placed(day = maxOf(first, today), e = e, first = first, last = last)", "Placed(day = first, e = e, first = first, last = last)"),
 ("upnext_from_capped", "plan.all.filter { it.state == State.NOW }.minByOrNull { it.event.endMs }\n            ?: plan.all.firstOrNull { it.state == State.NEXT }",
   "plan.days.flatMap { it.entries }.filter { it.state == State.NOW }.minByOrNull { it.event.endMs }\n            ?: plan.days.flatMap { it.entries }.firstOrNull { it.state == State.NEXT }"),
 ("back_to_back_clash", "o.e.startMs < p.e.endMs && p.e.startMs < o.e.endMs", "o.e.startMs <= p.e.endMs && p.e.startMs <= o.e.endMs"),
 ("all_day_ended_by_instant", "lastDayOf(e, offsetAt) < localDay(nowMs, offsetAt)", "e.endMs <= nowMs"),
 ("overnight_undated", 'else -> "until ${fmt.day(e.lastDay)} ${fmt.clock(e.event.endMs)}"', 'else -> "until ${fmt.clock(e.event.endMs)}"'),
 ("calendar_always", "if (withCalendar) e.event.calendarName", "if (true) e.event.calendarName"),
 ("untitled_blank", 'e.title.trim().ifEmpty { "(no title)" }', "e.title"),
 ("no_tomorrow_word", '            e.day == today + 1 -> "NEXT TOMORROW ${fmt.clock(e.event.startMs)} · ${titleOf(e.event)}"\n', ""),
 ("now_without_until", "        if (e.state == State.NOW) until(e, fmt)?.let(parts::add)\n", ""),
 ("one_calendar_named", ".distinct().size >= 2", ".distinct().size >= 1"),
 ("plural_always", 'if (n == 1) "EVENT" else "EVENTS"', '"EVENTS"'),
 ("end_exclusive", "e.allDay -> Math.floorDiv(e.endMs - 1, DAY_MS)", "e.allDay -> Math.floorDiv(e.endMs, DAY_MS)"),
]
only = sys.argv[1:]
try:
    base = run()
    ok = re.search(r"^OK \((\d+) tests\)", base, re.M)
    print("== baseline:", ok.group(0) if ok else "NOT GREEN")
    if not ok: print(base[-2000:]); sys.exit(1)
    for name, old, new in CASES:
        if only and name not in only: continue
        n = orig.count(old)
        if n != 1:
            print(f"== {name}: PERTURBATION MATCHED {n} TIMES — skipped"); continue
        open(SRC, "w").write(orig.replace(old, new))
        out = run()
        fails = re.findall(r"^\d+\) (.+?)\(", out, re.M)
        if "NOTHING COMPILED" in out: verdict = "INVALID (did not compile)"
        elif re.search(r"^OK \(", out, re.M): verdict = "ASLEEP (suite still green)"
        else: verdict = "awake: " + " | ".join(fails)
        print(f"== {name}: {verdict}")
        open(SRC, "w").write(orig)
finally:
    open(SRC, "w").write(orig)
    print("restored byte-identical" if open(SRC).read() == orig else "!!! RESTORE FAILED")
