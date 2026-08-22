#!/usr/bin/env python3
"""Does every capitalised symbol a Kotlin file uses actually resolve?

    tools/kotlin_import_check.py <package-directory>

⚠️ WHY THIS EXISTS. The parse-only kotlinc gate finds braces and syntax and says NOTHING about
names, and `tools/android_resolve_check.sh` differences unresolved names against HEAD — which
reports nothing for a brand-new file, because there is no baseline to cancel against. A missing
import in a new Compose file therefore reaches CI untouched by either.

Three checks, and each later one exists because an earlier one was not enough:

  1. every capitalised symbol used is imported, declared in the same package, or a builtin;
  2. every own-package import actually RESOLVES to a declaration at that path;
  3. no class declares two `companion object`s — a compile error no other local gate can see.

⚠️ Check 2 was added after CI failed on code this tool had just called clean. An import that
EXISTS is not an import that RESOLVES: `import dev.mascwa.pulse.feature.common.JetBrainsMono`
satisfied check 1 completely, and that package does not contain JetBrainsMono — it lives in
ui.theme. Fourteen unresolved references reached CI past a gate reporting "clean". A gate you can
satisfy while being wrong is worse than no gate.

It is textual, so it cannot fail for an environmental reason — the same reasoning as
WidgetLinkageTest. Only own-package imports are resolved; a third-party import cannot be checked
from source and guessing would produce noise.

⚠️ Four things had to be handled before its output was worth reading, and every one was found by
running it rather than by writing it:

  - comments and string literals are stripped first, or every capitalised word of English prose in
    a KDoc is reported and the one real finding is lost among ninety;
  - enum CONSTANTS count as same-package declarations, or `MACROS("MACROS"),` looks exactly like a
    call to an unimported symbol;
  - source roots are DISCOVERED, not listed. A hardcoded list said every module keeps sources under
    `src/main/java`; `:core:feeds` uses `src/main/kotlin`, so that whole shared module was invisible
    and the tool reported 265 false alarms across the app, every one of them a real import;
  - a package can SPAN MODULES. `dev.mascwa.pulse.data.settings` exists in both `:app` and
    `:core:feeds` — deliberately, so the shared repositories kept their import paths when they moved
    — so scanning one directory made every sibling from the other half look unimported.

⚠️ **SCOPE: this is a per-package gate for code you are writing, not a repo-wide sweep.** Run it on
the package you touched. Across every package it still reports about a dozen, and the residue is two
shapes it cannot model without being a compiler, both benign:

  - **nested declarations** — `private data class ApiAlbum` inside an object, an AIDL-generated
    `IInferenceService`, a Room `Callback`. Only top-level names are collected;
  - **unqualified enum entries in a `when`** — Kotlin 2.0 resolves `AIR ->` from the subject's type
    without an import, and knowing the subject's type requires type inference.

Both are reported as "used but not imported". Neither is worth chasing: the fix would be a type
checker, and the compiler already is one. What this catches — a name that resolves nowhere, and an
import pointing at the wrong package — is the part the compiler only tells you about after a CI
round.
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

# ⚠️ The collection aliases matter as much as the obvious ones. `ArrayList`, `HashMap` and friends are
# `kotlin.collections` typealiases available with no import, and omitting them produces a false alarm
# on ordinary code — which is the failure mode that makes a gate get ignored.
BUILTINS = set(
    """String Int Long Double Float Boolean List Map Set Pair Triple Unit Any Nothing
    Array Char Byte Short Number Comparable Iterable Iterator Sequence Result Exception Throwable
    Regex StringBuilder CharSequence Enum Annotation Lazy Deprecated Suppress JvmStatic JvmField
    ArrayList HashMap HashSet LinkedHashMap LinkedHashSet MutableList MutableMap MutableSet
    MutableCollection Collection Entry IntArray LongArray DoubleArray FloatArray BooleanArray
    ByteArray CharArray ShortArray IntRange LongRange ClosedRange ArrayDeque Comparator
    Charsets RegexOption OptIn Throws JvmName JvmOverloads Volatile Synchronized Transient
    IllegalStateException IllegalArgumentException SecurityException RuntimeException
    NumberFormatException UnsupportedOperationException NoSuchElementException
    IndexOutOfBoundsException ConcurrentModificationException ArithmeticException
    Class Integer Character ProcessBuilder ProcessHandle Object Void Boolean
    System Math Locale UUID Runtime Thread Error Runnable LinkageError
    UnsatisfiedLinkError NoClassDefFoundError StackOverflowError OutOfMemoryError
    AssertionError CloneNotSupportedException InterruptedException""".split()
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
    # ⚠️ `[(,;]` alone misses the LAST entry of an enum, which carries no trailing comma — and
    # Kotlin 2.0 resolves unqualified entries in a `when`, so those reads look unimported.
    names |= set(re.findall(r'^\s+([A-Z][A-Z0-9_]*)\s*(?:[(,;}]|$)', body, re.M))
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


def duplicate_companions(text: str) -> list:
    """Classes in this file declaring more than one `companion object`.

    ⚠️ Kotlin allows exactly one per class, and getting this wrong is a compile error that NONE of
    the other local gates can see: two companions parse perfectly, no import is missing, and the
    symptom is that every constant in the second one reports as an unresolved reference — which
    reads like a missing import and is not one. It cost a CI round, from a scripted edit that
    appended a companion to a class that already had one and asserted only that the file ended in a
    closing brace.

    Brace-matched rather than counted, because two companions in one FILE are perfectly legal when
    they belong to two different classes.
    """
    body = strip(text)
    out = []
    # ⚠️ `[ \t]*`, NEVER `\s*`. Under re.M a `\s*` after `^` walks across blank lines and captures
    # them as part of the indentation — so this matched `'\n\n'` for a top-level class and the
    # per-class companion search then looked for a line indented by two newlines, which nothing is.
    # The guard reported clean against the very defect it was written for. This is the SECOND time
    # that exact trap has bitten in one sitting; the import check above carries the same note.
    for m in re.finditer(r'^([ \t]*)(?:internal |private |public |abstract |open |sealed |data |value )*'
                         r'(?:class|object|interface)\s+(\w+)', body, re.M):
        indent, name = m.group(1), m.group(2)
        brace = body.find("{", m.end())
        if brace < 0:
            continue
        depth, i, n = 1, brace + 1, len(body)
        while i < n and depth:
            if body[i] == "{":
                depth += 1
            elif body[i] == "}":
                depth -= 1
            i += 1
        inner = body[brace:i]
        # Only companions belonging to THIS class, not to a class nested inside it.
        want = indent + "    "
        found = [ln for ln in inner.splitlines()
                 if re.match(re.escape(want) + r'(?:private |internal |public )?companion object\b', ln)]
        if len(found) > 1:
            out.append(f"{name} declares {len(found)} companion objects; Kotlin allows one")
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

    # ⚠️ A package can span modules. `dev.mascwa.pulse.data.settings` exists in BOTH :app and
    # :core:feeds — deliberately, so the shared repositories kept their import paths when they moved —
    # so a type declared in the other half is same-package and needs no import, while a scan of one
    # directory cannot see it. Fold in every root that carries the same package path.
    pkg_path = None
    for root in SRC_ROOTS:
        try:
            pkg_path = pkgdir.resolve().relative_to(root.resolve())
            break
        except ValueError:
            continue
    if pkg_path is not None:
        for root in SRC_ROOTS:
            twin = root / pkg_path
            if twin.is_dir() and twin.resolve() != pkgdir.resolve():
                for kt in twin.glob("*.kt"):
                    same_pkg |= declarations(kt.read_text())

    bad = False
    for f, text in texts.items():
        body = strip(text)
        imported = {m.rsplit(".", 1)[1] for m in re.findall(r'^import ([\w.]+)$', text, re.M)}
        imported |= set(re.findall(r'^import [\w.]+ as (\w+)$', text, re.M))
        # A symbol being CALLED, constructed, or given type arguments.
        used = set(re.findall(r'(?<![\w.])([A-Z]\w*)(?=[.(<])', body))
        # ⚠️ Plus a symbol in an unambiguous TYPE position, which the pattern above cannot see: it
        # requires the name be followed by `.`, `(` or `<`, so a type used only as an annotation —
        # `food: Food`, `is Food`, `as Food` — is invisible to it. That hole let a genuinely missing
        # `import …data.food.Food` through a gate reporting "clean", which is the exact failure this
        # tool exists to prevent. Restricted to those three lead-ins because they are the only places
        # a capitalised word is certainly a type; widening further picks up enum branches in a `when`.
        # ⚠️ `[ \t]*` and NOT `\s*`: a whitespace class that crosses a newline makes every capitalised
        # word at the start of a line follow the colon that ended the line before it, which turned
        # every `when` branch of an enum into a false alarm. Measured: 11 noisy packages became 23.
        used |= set(re.findall(r'(?::[ \t]*|\bis[ \t]+|\bas[ \t]+)([A-Z]\w*)', body))
        # A generic parameter is declared, not imported. `fun <T> load(): T` must not report T.
        # ⚠️ The optional NAME matters: `fun <T> load()` puts the list straight after the keyword,
        # `class Async<T>` puts the class name in between. A pattern that only handles the first
        # leaves every generic container reporting its own type parameter as an unresolved import.
        used -= {n for decl in re.findall(
                     r'\b(?:fun|class|interface|object)\s*(?:\w+\s*)?<([^>]*)>', body)
                 for n in re.findall(r'\b([A-Z]\w*)\b', decl)}
        missing = sorted(u for u in used
                         if u not in imported and u not in same_pkg and u not in BUILTINS)
        if missing:
            bad = True
            print(f"  {f.name}: used but not imported: {missing}")
        for bad_import in unresolvable_imports(text):
            bad = True
            print(f"  {f.name}: import does not resolve: {bad_import}")
        for dup in duplicate_companions(text):
            bad = True
            print(f"  {f.name}: {dup}")

    print("REVIEW THE ABOVE" if bad
          else "clean — every symbol is imported, same-package, or a Kotlin/JDK builtin")
    return 1 if bad else 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(pathlib.Path(sys.argv[1])))
