#!/usr/bin/env python3
"""Negative-test the credential-coverage gate by planting real omissions.

⚠️ This gate's whole value is catching a credential somebody forgot to register, so a run that has
never been watched to FAIL is worth nothing — and this one already reported every credential in the
app as uncovered once, because its own brace matcher was wrong. Baseline asserted green first, each
perturbation asserted to have matched, restore byte-compared.
"""
import filecmp
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path("/home/user/Thing")
SETTINGS = ROOT / "app/src/main/java/dev/mascwa/pulse/data/settings/AppSettings.kt"
# ⚠️ EmailAccount lives in its own file now, and the gate scans the whole package for exactly
# that reason — a single-file gate would have stopped seeing this password and gone on passing.
ACCOUNT = ROOT / "app/src/main/java/dev/mascwa/pulse/data/settings/EmailAccount.kt"
BACKUP = ROOT / "app/src/main/java/dev/mascwa/pulse/data/settings/SettingsBackup.kt"
TEST = "app/src/test/java/dev/mascwa/pulse/data/settings/CredentialCoverageTest.kt"
CLASS = "dev.mascwa.pulse.data.settings.CredentialCoverageTest"

# (label, file, find, replace, tests that MUST fail)
CASES = [
    (
        "a new credential field arrives registered nowhere",
        SETTINGS,
        "    val emailAccounts: List<EmailAccount> = emptyList(),",
        "    val emailAccounts: List<EmailAccount> = emptyList(),\n    val telemetryUploadToken: String = \"\",",
        ["every credential-shaped field is named in the scrub list",
         "every credential-shaped field is blanked on export"],
    ),
    (
        "the mail password is dropped from the scrub list",
        SETTINGS,
        "    *emailAccounts.map { it.password }.toTypedArray(),",
        "",
        ["every credential-shaped field is named in the scrub list"],
    ),
    (
        "the mail password is no longer blanked on export",
        BACKUP,
        "        emailAccounts = s.emailAccounts.map { it.copy(password = \"\") },",
        "",
        ["every credential-shaped field is blanked on export"],
    ),
    (
        "export blanks something import never puts back",
        BACKUP,
        """        emailAccounts = restored.emailAccounts.map { r ->
            r.copy(password = current.emailAccounts.firstOrNull { it.sameAccountAs(r) }?.password.orEmpty())
        },""",
        "",
        ["a blanked credential is put back on import"],
    ),
    (
        "the scan stops finding anything at all",
        ROOT / TEST,
        '''    private val credentialish = listOf("password", "token", "secret", "apikey", "passphrase", "credential")''',
        '''    private val credentialish = listOf("nothingmatchesthis")''',
        # ⚠️ The point of the fourth test: with the regex broken every "nothing is missing"
        # assertion passes trivially, so only the self-check can notice.
        ["the heuristic is actually finding the fields it is meant to"],
    ),
]


def run():
    p = subprocess.run(["/tmp/runcred.sh", TEST, CLASS], cwd=ROOT, capture_output=True, text=True)
    return p.stdout + p.stderr


def failing(out):
    return set(re.findall(r"^\d+\) ([^(]+)\(", out, re.M))


base = run()
if "OK (" not in base:
    sys.exit("BASELINE IS NOT GREEN — nothing below would mean anything:\n" + base[-3000:])
print("baseline:", re.search(r"OK \(\d+ tests\)", base).group(0))

originals = {f: f.read_text() for f in {c[1] for c in CASES}}
tmp = Path(tempfile.mkdtemp())
for f, text in originals.items():
    shutil.copy2(f, tmp / f.name)
asleep = []
try:
    for label, target, find, repl, expect in CASES:
        text = originals[target]
        n = text.count(find)
        assert n == 1, f"{label}: the perturbation matched {n} times, not once — it would test nothing"
        target.write_text(text.replace(find, repl))
        out = run()
        target.write_text(text)
        if "OK (" in out:
            asleep.append(label)
            print(f"  ASLEEP  {label} — the gate still passes with the registration gone")
            continue
        fails = failing(out)
        missing = [t for t in expect if t not in fails]
        extra = sorted(fails - set(expect))
        status = "awake  " if not missing else "PARTIAL"
        print(f"  {status} {label}: {len(fails)} failed")
        if missing:
            print(f"           expected but did not fail: {missing}")
            asleep.append(label)
        if extra:
            print(f"           also failed (fine, but worth seeing): {extra}")
finally:
    for f, text in originals.items():
        f.write_text(text)
        assert filecmp.cmp(f, tmp / f.name, shallow=False), f"RESTORE FAILED for {f}"
    after = run()
    assert "OK (" in after, "the restored tree does not pass:\n" + after[-3000:]
    print("restored, and green again:", re.search(r"OK \(\d+ tests\)", after).group(0))

print()
print("ALL GUARDS AWAKE" if not asleep else f"ASLEEP: {asleep}")
sys.exit(1 if asleep else 0)
