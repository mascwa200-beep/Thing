#!/usr/bin/env python3
"""Does every capitalised symbol a Kotlin file uses actually resolve?

    tools/kotlin_import_check.py <package-directory>

⚠️ WHY THIS EXISTS. The parse-only kotlinc gate finds braces and syntax and says NOTHING about
names, and `tools/android_resolve_check.sh` differences unresolved names against HEAD — which
reports nothing for a brand-new file, because there is no baseline to cancel against. A missing
import in a new Compose file therefore reaches CI untouched by either.

Two checks, and the second one exists because the first was not enough:

  1. every capitalised symbol used is imported, declared in the same package, or a builtin;
  2. every own-package import actually RESOLVES to a declaration at that path.

⚠️ Check 2 was added after CI failed on code this tool had just called clean. An import that
EXISTS is not an import that RESOLVES: `import dev.mascwa.pulse.feature.common.JetBrainsMono`
satisfied check 1 completely, and that package does not contain JetBrainsMono — it lives in
ui.theme. Fourteen unresolved references reached CI past a gate reporting "clean". A gate you can
satisfy while being wrong is worse than no gate.

It is textual, so it cannot fail for an environmental reason — the same reasoning as
WidgetLinkageTest. Only own-package imports are resolved; a third-party import cannot be checked
from source and guessing would produce noise.

⚠️ Three things had to be handled before its output was worth reading, and all three were found by
running it rather than by writing it:

  - comments and string literals are stripped first, or every capitalised word of English prose in
    a KDoc is reported and the one real finding is lost among ninety;
  - enum CONSTANTS count as same-package declarations, or `MACROS("MACROS"),` looks exactly like a
    call to an unimported symbol;
  - source roots are DISCOVERED, not listed. A hardcoded list said every module keeps sources under
    `src/main/java`; `:core:feeds` uses `src/main/kotlin`, so that whole shared module was invisible
    and the tool reported 265 false alarms across the app, every one of them a real import.
"""

import re
import pathlib
import sys

OWN = "dev.mascwa.pulse."

SRC_ROOTS = sorted(
    d for pattern in ("*/src/main/*", "*/*/src/main/*")
    for d in pathlib.Path(".").glob(pattern)
    if d.is_dir() and d.name in ("java", "kotlin")
)

# ⚠️ Written by the build, never present in source. Reporting them is the documented false positive
# `android_resolve_check.sh` also carries; naming them here is cheaper than re-diagnosing it.
GENERATED = {"R", "BuildConfig"}

BUILTINS = set(
    """String Int Long Double Float Boolean List Map Set Pair Triple Unit Any Nothing
    Array Char Byte Short Number Comparable Iterable Sequence Result Exception Throwable Regex
    System Math Locale UUID Runtime Thread Error""".split()
)


def strip(t: str) -> str:
    """The source with comments and string literals removed."""
    out, i, n = [], 0, len(t)
    while i < n:
        two = t[i:i + 2]
        if two == "/*":
            depth, i = 1, i + 2          # Kotlin block comments nest
            while i < n and depth:
                if t[i:i + 2] == "/*":
                    depth, i = depth + 1, i + 2
                elif t[i:i + 2] == "*/":
                    depth, i = depth - 1, i + 2
                else:
                    i += 1
            continue
        if two == "//":
            while i < n and t[i] != "\n":
                i += 1
            continue
        if t[i:i + 3] == '"""':
            i += 3
            while i < n and t[i:i + 3] != '"""':
                i += 1
            i += 3
            continue
        if t[i] == '"':
            i += 1
            while i < n and t[i] != '"':
                i += 2 if t[i] == "\\" else 1
            i += 1
            continue
        out.append(t[i])
        i += 1
    return "".join(out)


def declarations(text: str) -> set:
    """Every top-level or enum-constant name a file declares."""
    body = strip(text)
    # ⚠️ The modifier list must be generous. A first version omitted `suspend`, so two real top-level
    # `suspend fun` declarations on the desktop side were reported as broken imports — and a report
    # with two false alarms in it is one somebody stops reading.
    names = set(re.findall(
        r'^\s*(?:internal |private |public |abstract |open |sealed |expect |actual |suspend |'
        r'inline |operator |infix |tailrec |external |override |final |value )*'
        r'(?:fun|val|var|const val|class|object|enum class|interface|data class|'
        r'annotation class|typealias) (?:<[^>]*> )?([A-Za-z_]\w*)', body, re.M))
    names |= set(re.findall(r'^\s+([A-Z][A-Z0-9_]*)\s*[(,;]', body, re.M))
    return names


# ⚠️ Cached per directory. Without it the tool re-reads and re-strips every file in a package once
# per import, which is fine for one package and takes minutes across the repository — and a check
# too slow to run over everything is one nobody runs over everything.
_PKG_CACHE: dict = {}


def _package_symbols(directory: pathlib.Path) -> set:
    key = str(directory)
    if key in _PKG_CACHE:
        return _PKG_CACHE[key]
    names, extensions = set(), set()
    for kt in directory.glob("*.kt"):
        text = kt.read_text()
        names |= declarations(text)
        # ⚠️ Extension functions and properties, and the receiver is the awkward part. A first
        # version required `fun ` immediately followed by a simple receiver, so
        # `suspend fun <T> MutableStateFlow<Async<T>>.load(` was invisible — eighteen real imports of
        # `core.util.load` reported as broken across the app. The modifiers, the generic parameter
        # list and a receiver carrying its own type arguments all have to be allowed for.
        body = strip(text)
        extensions |= set(re.findall(
            r'^\s*(?:internal |private |public |inline |suspend |operator |infix )*'
            r'fun\b[^=\n]*?\.([A-Za-z_]\w*)\s*[(<]', body, re.M))
        extensions |= set(re.findall(
            r'^\s*(?:internal |private |public )*(?:val|var)\b[^=\n]*?\.([A-Za-z_]\w*)\s*(?::|\bget\b)',
            body, re.M))
    _PKG_CACHE[key] = names | extensions
    return _PKG_CACHE[key]


def declares(directory: pathlib.Path, symbol: str) -> bool:
    """Is `symbol` declared by any file directly in `directory`?"""
    return directory.is_dir() and symbol in _package_symbols(directory)


def unresolvable_imports(text: str) -> list:
    out = []
    for imp in re.findall(r'^import (' + re.escape(OWN) + r'[\w.]+)$', text, re.M):
        pkg, _, sym = imp.rpartition(".")
        if sym in GENERATED:
            continue
        rel = pathlib.Path(*pkg.split("."))
        dirs = [r / rel for r in SRC_ROOTS if (r / rel).is_dir()]
        if dirs:
            if not any(declares(d, sym) for d in dirs):
                out.append(f"{imp} (package exists, {sym} is not in it)")
            continue
        # ⚠️ No such directory does not mean no such import. `import …core.telemetry.VoiceMachine.settle`
        # imports a MEMBER of an object, so the last two segments are type and member rather than
        # package and type. Back off one segment: if the shorter path is a real package declaring that
        # type, this is a nested member — unverifiable from source without parsing the type's body, so
        # it is passed over rather than reported. Claiming a working import is broken is the failure
        # mode that gets a whole gate ignored.
        outer_pkg, _, outer = pkg.rpartition(".")
        outer_rel = pathlib.Path(*outer_pkg.split(".")) if outer_pkg else None
        nested = outer_rel is not None and any(
            (r / outer_rel).is_dir() and declares(r / outer_rel, outer) for r in SRC_ROOTS)
        if not nested:
            out.append(f"{imp} (no such package)")
    return out


def main(pkgdir: pathlib.Path) -> int:
    files = sorted(pkgdir.glob("*.kt"))
    if not files:
        print(f"no Kotlin files in {pkgdir} — this gate checked nothing")
        return 2
    texts = {f: f.read_text() for f in files}
    same_pkg = set()
    for text in texts.values():
        same_pkg |= declarations(text)

    bad = False
    for f, text in texts.items():
        body = strip(text)
        imported = {m.rsplit(".", 1)[1] for m in re.findall(r'^import ([\w.]+)$', text, re.M)}
        imported |= set(re.findall(r'^import [\w.]+ as (\w+)$', text, re.M))
        used = set(re.findall(r'(?<![\w.])([A-Z]\w*)(?=[.(<])', body))
        missing = sorted(u for u in used
                         if u not in imported and u not in same_pkg and u not in BUILTINS)
        if missing:
            bad = True
            print(f"  {f.name}: used but not imported: {missing}")
        for bad_import in unresolvable_imports(text):
            bad = True
            print(f"  {f.name}: import does not resolve: {bad_import}")

    print("REVIEW THE ABOVE" if bad
          else "clean — every symbol is imported, same-package, or a Kotlin/JDK builtin")
    return 1 if bad else 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(pathlib.Path(sys.argv[1])))
