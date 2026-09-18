#!/usr/bin/env bash
# One negative-test case, with the restore in the SHELL so a killed run cannot leave a
# perturbation in the tree — see negtest.py's header for why that is not a theoretical worry.
set -uo pipefail
cd "$(dirname "$0")/../.."
C=core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Sensorium.kt
B=core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/SensoriumBaseline.kt
T=$(mktemp -d)
cp "$C" "$T/c.kt"; cp "$B" "$T/b.kt"
restore() {
  cp "$T/c.kt" "$C"; cp "$T/b.kt" "$B"
  cmp -s "$T/c.kt" "$C" && cmp -s "$T/b.kt" "$B" || { echo "RESTORE FAILED — fix by hand"; exit 9; }
  rm -rf "$T"
}
trap restore EXIT
python3 scratchpad/sensorium/negtest.py "$@"
