#!/usr/bin/env bash
# Run NotifIdTest locally, with no Android SDK.
#
# ⚠️ WHY THIS EXISTS. NotifIdTest keeps its own hand-written registry of every notification id and
# asserts it equals NotifId.PERSISTENT — deliberately, so that adding a constant and forgetting to
# list it is a build failure rather than a silently drifted keep set. That is two independent
# statements of one fact, and the whole point is that updating one does not update the other. Adding
# FGS_INTERROGATOR to the source and not to the test cost a full CI round to discover.
#
# The test itself is pure Kotlin, but it lives in a module whose sibling code is full of Android
# types, and NotifId is declared INSIDE Notifier.kt — which imports half the platform. So the object
# is brace-matched out of that file and compiled alone. What is checked is the shipped declaration,
# not a copy of it.
#
# Run after any change to NotifId. Exits non-zero if the test fails.
set -euo pipefail
cd "$(dirname "$0")/.."

KC=/opt/gradle-8.14.3/lib/kotlin-compiler-embeddable-2.0.21.jar
STD=/opt/gradle-8.14.3/lib/kotlin-stdlib-2.0.21.jar
TRV=/opt/gradle-8.14.3/lib/trove4j-1.0.20200330.jar
ANN=/opt/gradle-8.14.3/lib/annotations-24.0.1.jar
GC="$HOME/.gradle/caches/modules-2/files-2.1"
COR=$(find "$GC" -name 'kotlinx-coroutines-core-jvm-*.jar' | head -1)
JU=$(find "$GC" -name 'junit-4.13.2.jar' | head -1)
HC=$(find "$GC" -name 'hamcrest-core-1.3.jar' | head -1)
for j in "$KC" "$STD" "$TRV" "$ANN" "$COR" "$JU" "$HC"; do
  # ⚠️ Asserted rather than assumed: omitting a jar from the compiler's own -cp makes kotlinc die
  # before it compiles a line, which looks very much like a clean pass.
  [ -n "$j" ] && [ -f "$j" ] || { echo "missing a required jar (one of the paths above)"; exit 2; }
done

src=$(mktemp -d)/NotifId.kt
python3 - "$src" <<'PY'
import sys
s = open("app/src/main/java/dev/mascwa/pulse/notifications/Notifier.kt").read()
i = s.index("object NotifId")
d, j = 0, s.index("{", i)
end = None
for k in range(j, len(s)):
    if s[k] == "{":
        d += 1
    elif s[k] == "}":
        d -= 1
        if d == 0:
            end = k + 1
            break
assert end is not None, "unbalanced braces reading NotifId out of Notifier.kt"
open(sys.argv[1], "w").write("package dev.mascwa.pulse.notifications\n\n" + s[i:end] + "\n")
PY

out=$(mktemp -d)
java -cp "$KC:$STD:$TRV:$ANN:$COR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -d "$out" -cp "$STD:$JU:$HC" \
  "$src" app/src/test/java/dev/mascwa/pulse/notifications/NotifIdTest.kt 2>&1 |
  grep -Ev '^(warning|info):' || true

java -cp "$out:$STD:$JU:$HC" org.junit.runner.JUnitCore \
  dev.mascwa.pulse.notifications.NotifIdTest 2>&1 | grep -v JAVA_TOOL_OPTIONS
