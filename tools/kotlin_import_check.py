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
  3. no class declares two `companion object`s — a compile error no other local gate can see;
  4. no file declares two same-named functions that both take a lambda, which makes `it` at a
     call site ambiguous.

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

# ⚠️ TEST roots as well as main ones, and that is not thoroughness — it is the same
# "a package can span source trees" rule as the :app/:core:feeds case below. A test sits in the
# package it tests, so `RecipesTest.kt` needs no import for `Recipes`; with only main roots
# discovered, every test file reported every type it exercises as unimported and the gate emitted
# about seventy findings, none of them real. A gate that floods is worse than no gate.
SRC_ROOTS = sorted(
    d for pattern in ("*/src/main/*", "*/*/src/main/*", "*/src/test/*", "*/*/src/test/*")
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


def lambda_overloads(text: str) -> list:
    """Two functions in one file with the same name, each taking a lambda.

    ⚠️ Added after a CI round lost to exactly this. `HealthViewModel` already had a private
    `edit(block: (HealthSettings) -> HealthSettings)`; a second `edit(block: (Recipes.Recipe) ->
    Recipes.Recipe)` was added beside it, and every `it.copy(...)` at a call site then failed with
    "overload resolution ambiguity" — the compiler cannot tell which receiver `it` is when both
    candidates take a one-argument lambda. Six call sites broke at once.

    Kotlin allows the overload; what it cannot do is infer the receiver at a call site that relies on
    `it`. So this is a WARNING SHAPE, not a certain error — but it is rare enough to be worth reading:
    measured across every module, the repo contains exactly zero other instances.

    ⚠️ The parameter list is brace-matched. A `[^)]*` pattern stops at the first `)`, which is inside
    `(HealthSettings)` — so the naive version cannot see a lambda parameter at all and reports a clean
    zero on the very code that broke the build. That version was written first, and its silence was
    nearly taken for evidence.
    """
    body = strip(text)
    byname = {}
    for m in re.finditer(r'\b(?:private |internal |public )?fun\s+(\w+)\s*\(', body):
        depth, i = 0, m.end() - 1
        while i < len(body):
            if body[i] == "(":
                depth += 1
            elif body[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        params = body[m.end():i]
        if "->" in params:
            byname.setdefault(m.group(1), []).append(params.strip())
    return [
        f"'{n}' is declared {len(v)} times and every one takes a lambda — `it` cannot be inferred "
        f"at a call site: {v}"
        for n, v in byname.items() if len(v) > 1
    ]


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


def enum_constants(raw: str) -> dict:
    """
    Every `enum class Foo { A, B }` in this text, mapped to everything `Foo.` can legally name.

    ⚠️ Reads the COMMENT-STRIPPED text. The first version read the raw source and silently truncated
    every enum whose constants carry KDoc — the entry then begins with `/`, the identifier match
    fails, and the constant vanishes. `HapticCue` came back declaring exactly one of its thirteen
    values, and the check then reported the other twelve as errors. An under-read declaration is
    worse than none, because it turns a gate into a generator of false findings.

    ⚠️ The returned set is CONSTANTS PLUS MEMBERS, not constants alone, and the difference is not
    cosmetic. `SettingsCategory.FIRST` is a `val` in the enum's companion object and is referenced
    from thirty call sites; judged against the constants alone it is a finding on every one of them.
    The members are collected from the whole declaration — after the `;` and inside the companion —
    because from a call site `Foo.X` cannot distinguish them anyway.
    """
    text = strip(raw)
    out = {}
    for m in re.finditer(r'\benum class (\w+)[^{]*\{', text):
        name = m.group(1)
        # Brace-match the body so a nested class or a constant with its own block cannot end it early.
        depth, i = 0, m.end() - 1
        while i < len(text):
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        body = text[m.end():i]
        # Constants run until the first `;` (which begins the members) or the end of the body. Each
        # is a capitalised identifier at the start of an entry, optionally with its own arguments.
        head, _, members = body.partition(";")
        consts = set()
        for entry in re.split(r",(?![^(]*\))", head):
            e = entry.strip()
            # Skip a KDoc or comment line that survived, and anything that is not an identifier.
            em = re.match(r'^(?:@\w+\s*)*([A-Z][A-Za-z0-9_]*)', e)
            if em:
                consts.add(em.group(1))
        if consts:
            # Anything declared as a member — including in the companion object, whose braces are
            # inside `members` — is reachable as `Foo.thing` from outside.
            consts |= set(re.findall(r'\b(?:val|var|fun)\s+([A-Za-z_]\w*)', members))
            out[name] = consts
    return out


def bad_enum_constants(text: str, enums: dict) -> list:
    """
    `LcarsCorner.NONE` where the enum has no NONE.

    ⚠️ Added after the THIRD time in one session that a call was written from memory rather than from
    the declaration. This one shipped to CI: the enum has four values, none of them `NONE`, and every
    local gate passed it — the import check because the TYPE was imported, the parse pass because it
    type-checks nothing, and the resolve check because that file cascades wholesale without Compose
    on its classpath.

    `enums` maps a simple name to `(members, {declaring packages})`.

    Textual on purpose, like the rest of this tool. It only judges enums it can SEE declared in the
    scanned packages, so an unknown type is silently skipped rather than guessed at — a gate that
    invents findings is one people stop reading.

    ⚠️ **A matching simple name is not the same type, and this is the second collision family.** The
    caller already drops names whose several declarations DISAGREE about their members. The remaining
    case is a repo-local name colliding with a THIRD-PARTY one, which no scan of these sources can
    see: `LiveVideoController` imports `androidx.media3.common.Format` and reads its `NO_VALUE`, while
    an unrelated local `Format` enum exists elsewhere. An import of the simple name from a package
    that declares no such enum therefore means this file is talking about something else entirely,
    and the honest answer is to say nothing.
    """
    out = []
    body = strip(text)
    imports = dict(
        (fq.rsplit(".", 1)[1], fq.rsplit(".", 1)[0])
        for fq in re.findall(r'^import ([\w.]+)$', text, re.M)
    )
    for m in re.finditer(r'(?<![\w.])([A-Z]\w*)\.([A-Z][A-Z0-9_]{1,})\b', body):
        type_name, const = m.group(1), m.group(2)
        entry = enums.get(type_name)
        if entry is None:
            continue
        known, pkgs = entry
        from_pkg = imports.get(type_name)
        if from_pkg is not None and from_pkg not in pkgs:
            continue
        # A member function or property in SCREAMING_CASE is legal too, so only complain when the
        # name appears nowhere in the declaration at all.
        if const in known:
            continue
        if re.search(r'\b(?:val|var|fun)\s+' + re.escape(const) + r'\b', text):
            continue
        out.append(f"{type_name}.{const} — {type_name} declares {sorted(known)}")
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

    # ⚠️ Enums from EVERY source root, not only this package — a call site names a type it imported
    # from somewhere else, which is precisely the case that shipped `LcarsCorner.NONE` to CI.
    #
    # ⚠️ **AND ONLY NAMES WHOSE DECLARATIONS AGREE.** Short enum names collide heavily in this
    # codebase: `Status`, `Action`, `Kind`, `Severity`, `Category` and `Event` are each declared in
    # several unrelated files, so a map keeping the last one seen judges every `Status.X` against an
    # arbitrary winner. The first version of this check did that and reported about a hundred
    # findings, every one of them false — `Seismic.Severity.MICRO` against a `Severity` from the
    # safety feed. A gate that floods is worse than no gate, which this tool's own header says.
    #
    # ⚠️ But "declared exactly once" — the first fix — was too strict, and it silently cost the check
    # the one failure it was written for. `LcarsCorner` is declared TWICE, in `:app` and in
    # `:desktop`, because this repo deliberately keeps a copy of the UI kit on each side. So the
    # whole kit was excluded, `LcarsCorner.NONE` was NOT caught, and a negative test proved it.
    #
    # What actually matters is not how many declarations there are but whether they DISAGREE. Two
    # declarations with identical members are safe to judge against whichever one the file means,
    # which readmits every deliberately-duplicated kit type. Genuine collisions between unrelated
    # types differ in their members, and those are still skipped rather than guessed at.
    seen = {}
    for root in SRC_ROOTS:
        for kt in root.rglob("*.kt"):
            raw = kt.read_text(errors="replace")
            found = enum_constants(raw)
            if not found:
                continue
            pm = re.search(r'^package ([\w.]+)', raw, re.M)
            pkg = pm.group(1) if pm else ""
            for name, consts in found.items():
                agree, pkgs = seen.setdefault(name, [set(), set()])
                agree.add(frozenset(consts))
                pkgs.add(pkg)
    enums = {
        n: (set(next(iter(agree))), pkgs)
        for n, (agree, pkgs) in seen.items() if len(agree) == 1
    }

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
        for amb in lambda_overloads(text):
            bad = True
            print(f"  {f.name}: {amb}")
        for e in bad_enum_constants(text, enums):
            bad = True
            print(f"  {f.name}: no such enum constant: {e}")

    print("REVIEW THE ABOVE" if bad
          else "clean — every symbol is imported, same-package, or a Kotlin/JDK builtin")
    return 1 if bad else 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(pathlib.Path(sys.argv[1])))
