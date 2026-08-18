import re, shutil, subprocess, sys, pathlib
SRC = pathlib.Path("core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/MediaFloor.kt")
orig = SRC.read_text()

PERTURBS = {
 "P1 released ignores the owner check": (
   "if (owner == who) Step(State(Owner.NONE), Action.NOTHING) else Step(this, Action.NOTHING)",
   "Step(State(Owner.NONE), Action.NOTHING)"),
 "P2 to() stops comparing owners": (
   "        if (next == owner) Step(this, Action.NOTHING)\n        else Step(State(next), stopOf(owner))",
   "        Step(State(next), stopOf(owner))"),
}

for label,(find,repl) in PERTURBS.items():
    assert find in orig, f"PERTURBATION DID NOT MATCH SOURCE: {label}"
    SRC.write_text(orig.replace(find, repl, 1))
    out = subprocess.run(["./scratchpad/kb/runcore.sh",
        "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/MediaFloorTest.kt"],
        capture_output=True, text=True).stdout
    fails = re.findall(r"^\d+\) (\w+)", out, re.M)
    print(f"{label}: failures={fails or 'NONE — the rule is not tested!'}")
    SRC.write_text(orig)
print("source restored:", SRC.read_text() == orig)
