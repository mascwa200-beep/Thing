import re, subprocess, pathlib
SRC = pathlib.Path("core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/M3uCatalog.kt")
orig = SRC.read_text()
PERTURBS = {
 # Splitting on \n alone is what would leave a carriage return on every line. Removing the
 # trim() does NOT — lineSequence already handles \r\n — which is how the comment crediting
 # trim() was found to be wrong.
 "P1 split on \\n only, so CRLF survives": (
   "val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()",
   "val lines = text.split(\"\\n\").filter { it.isNotEmpty() }.toList()"),
 "P2 the cap is not applied after ordering": (
   "return out.sortedBy { it.name.lowercase() }.take(cap.coerceAtLeast(0))",
   "return out.sortedBy { it.name.lowercase() }"),
}
for label,(find,repl) in PERTURBS.items():
    assert find in orig, f"PERTURBATION DID NOT MATCH SOURCE: {label}"
    SRC.write_text(orig.replace(find, repl, 1))
    out = subprocess.run(["./scratchpad/kb/runcore.sh",
        "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/M3uCatalogTest.kt"],
        capture_output=True, text=True).stdout
    fails = re.findall(r"^\d+\) (\w+)", out, re.M)
    print(f"{label}: failures={fails or 'NONE — the rule is not tested!'}")
    SRC.write_text(orig)
print("source restored:", SRC.read_text() == orig)
