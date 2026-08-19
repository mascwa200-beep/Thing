#!/usr/bin/env python3
"""Negative-test every load-bearing rule in SearchPlan: break it, confirm its own test fails.

⚠️ CRASH-SAFE BY CONSTRUCTION, and that is not caution — it is a bug this harness already caused.
An earlier version was killed by a two-minute timeout *while a perturbation was on disk*. The next
run then read that damaged file as its baseline, restored to it afterwards, and reported several
rules "awake" against source that already carried an injected defect. So:

  - the pristine source is written to a .orig sidecar BEFORE anything is touched, and a run that
    finds a leftover sidecar restores from it and refuses to continue until that is acknowledged;
  - restoration happens in a finally, so an exception cannot leave the tree dirty;
  - and the baseline is RUN and required to be green before the first perturbation, so a harness
    that starts from broken source stops instead of measuring nonsense.

A green test proves nothing until you have watched it fail — and a harness needs the same care as
the thing it checks.
"""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]

# The cases live in a sibling `cases_<name>.py`, named on the command line, so this harness serves
# every core rather than being copied per core — a duplicated definition is a mistake this repo has
# had to converge four times.
import importlib.util
_spec_name = sys.argv[1] if len(sys.argv) > 1 else "search"
_spec_path = pathlib.Path(__file__).with_name(f"cases_{_spec_name}.py")
if not _spec_path.exists():
    print(f"no case file: {_spec_path}")
    sys.exit(64)
_spec = importlib.util.spec_from_file_location("cases", _spec_path)
_mod = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(_mod)

SRC = ROOT / _mod.SOURCE
TEST = _mod.TEST
SIDECAR = SRC.with_suffix(".kt.orig")

CASES = _mod.CASES


def run_tests() -> str:
    r = subprocess.run([str(ROOT / "scratchpad/search/run.sh"), TEST],
                       capture_output=True, text=True, cwd=ROOT)
    return r.stdout + r.stderr


def failed_names(out: str) -> set:
    return {l.split("(")[0].split(") ")[-1].strip()
            for l in out.splitlines() if ") " in l and "(dev.mascwa" in l}


def main() -> int:
    if SIDECAR.exists():
        SRC.write_text(SIDECAR.read_text())
        SIDECAR.unlink()
        print("A previous run died with a perturbation on disk. The source has been restored from\n"
              "the sidecar. Re-run to measure — this run is stopping so the recovery is visible.")
        return 2

    orig = SRC.read_text()
    SIDECAR.write_text(orig)
    problems = []
    try:
        baseline = run_tests()
        if "OK (" not in baseline:
            print("BASELINE IS NOT GREEN — refusing to measure anything against it.")
            print(baseline[-2000:])
            return 2
        print("baseline green\n")

        for label, old, new, expect in CASES:
            if old not in orig:
                print(f"!! PERTURBATION DID NOT MATCH THE SOURCE: {label}")
                problems.append(label)
                continue
            SRC.write_text(orig.replace(old, new, 1))
            failed = failed_names(run_tests())
            SRC.write_text(orig)
            if expect in failed:
                extra = sorted(failed - {expect})
                print(f"OK   {label}\n     -> {expect}" + (f"  (also {extra})" if extra else ""))
            else:
                print(f"!! ASLEEP: {label}\n     -> expected {expect}, got {sorted(failed) or 'NOTHING'}")
                problems.append(label)
    finally:
        SRC.write_text(orig)
        SIDECAR.unlink(missing_ok=True)

    print()
    print(f"all {len(CASES)} rules awake" if not problems
          else f"{len(problems)} problem(s): {problems}")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
