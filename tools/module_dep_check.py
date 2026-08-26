#!/usr/bin/env python3
"""
Does a Gradle module DECLARE the libraries its own source files import?

WHY THIS EXISTS
---------------
Two CI rounds in one session were spent on exactly one defect: code that is
perfectly well-typed, in a module that never declared the library it reaches for.

  * `:core:health` used `viewModelScope` with no lifecycle dependency. The class
    compiled — `ViewModel` arrives transitively through Health Connect — so the
    type resolved and every use of its scope did not.
  * `:nutrition` built a DataStore while `:core:health` kept DataStore as
    `implementation`, which puts it on the RUNTIME classpath and not the COMPILE
    one. Twenty-four errors, all from one absent line.

⚠️ **`tools/android_compile_check.sh` cannot catch this class, and worse, it can
hide it.** Its `-l` list is hand-written, so the natural response to an unresolved
symbol is to add the artifact — which proves the code is fine GIVEN the dependency
and says nothing about whether the dependency is declared. That is precisely how
the first of the two above passed locally and failed in CI.

WHAT IT CHECKS
--------------
Every package imported by the module's own sources is provided by something the
module can actually see at COMPILE time: a direct dependency, the `api` chain of a
project dependency, or the compile-scope closure of an external artifact.

⚠️ `implementation` on a project dependency deliberately does NOT propagate. That
is the whole point — it is the rule the DataStore failure broke.

⚠️ **WHAT IT CANNOT SEE, and this is a real limit rather than a bug.** It reads
IMPORTS. A type reached only as the INFERRED result of a dependency's public API is
invisible to it, because nothing imports the name. `ProcessCameraProvider.getInstance()`
returns a Guava `ListenableFuture`; `:nutrition` never writes that name, this check
reported the module clean, and CI produced seven errors. Compiling is the only thing
that catches that class — so a green run here means "every package you named is
declared", never "this module compiles".

Usage:  tools/module_dep_check.py <module-dir> [more-module-dirs ...]
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = "/tmp/module-dep-check"
REPOS = [
    "https://repo1.maven.org/maven2",
    "https://dl.google.com/dl/android/maven2",
]
# Packages every module gets from the language and the platform itself.
FREE_PREFIXES = ("kotlin", "java", "javax", "android.", "dalvik.")
MAX_DEPTH = 3
MAX_ARTIFACTS = 260


# ------------------------------------------------------------------ the catalog

def catalog():
    """(versions, libraries) out of gradle/libs.versions.toml."""
    text = open(os.path.join(ROOT, "gradle/libs.versions.toml")).read()
    versions, libs, section = {}, {}, None
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        if line.startswith("["):
            section = line.strip("[]")
            continue
        if "=" not in line:
            continue
        key, value = (p.strip() for p in line.split("=", 1))
        if section == "versions":
            versions[key] = value.strip('"')
        elif section == "libraries":
            group = re.search(r'group\s*=\s*"([^"]+)"', value)
            name = re.search(r'name\s*=\s*"([^"]+)"', value)
            ref = re.search(r'version\.ref\s*=\s*"([^"]+)"', value)
            lit = re.search(r'version\s*=\s*"([^"]+)"', value)
            if group and name:
                libs[key] = (group.group(1), name.group(1),
                             ref.group(1) if ref else None,
                             lit.group(1) if lit else None)
    return versions, libs


def resolve(alias, versions, libs):
    """`libs.androidx.core.ktx` -> ('androidx.core', 'core-ktx', '1.15.0')."""
    key = alias.replace(".", "-")
    if key not in libs:
        return None
    group, name, ref, lit = libs[key]
    version = lit or (versions.get(ref) if ref else None)
    return (group, name, version)


# ---------------------------------------------------------------- fetching

def fetch(group, artifact, version, ext):
    """Cache and return a path, or None if no repository has it."""
    if not version:
        return None
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, f"{group}-{artifact}-{version}.{ext}")
    if os.path.exists(path):
        return path if os.path.getsize(path) else None
    rel = f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.{ext}"
    for repo in REPOS:
        r = subprocess.run(["curl", "-sfL", "-o", path, f"{repo}/{rel}"],
                           capture_output=True)
        if r.returncode == 0 and os.path.getsize(path):
            return path
    open(path, "wb").close()  # negative cache, so a miss is not refetched
    return None


def pom_deps(group, artifact, version):
    """Compile-scope dependencies, which is what a consumer sees."""
    path = fetch(group, artifact, version, "pom")
    if not path:
        return []
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return []
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    props = {}
    for p in root.findall("m:properties/*", ns):
        props[p.tag.split("}")[-1]] = (p.text or "").strip()
    out = []
    for d in root.findall("m:dependencies/m:dependency", ns):
        scope = d.findtext("m:scope", default="compile", namespaces=ns)
        optional = d.findtext("m:optional", default="false", namespaces=ns)
        if scope not in ("compile", "runtime") or optional == "true":
            continue
        g = (d.findtext("m:groupId", namespaces=ns) or "").strip()
        a = (d.findtext("m:artifactId", namespaces=ns) or "").strip()
        v = (d.findtext("m:version", namespaces=ns) or "").strip()
        m = re.fullmatch(r"\$\{([^}]+)\}", v)
        if m:
            v = props.get(m.group(1), "")
        v = pin(v)
        if g and a and v:
            out.append((g, a, v))
    return out


def pin(version):
    """
    A Maven version RANGE reduced to something that can be downloaded.

    ⚠️ **Found by this script reporting a false MISSING.** `androidx.datastore:datastore-preferences`
    pins its siblings as `[1.1.1]` — a hard range meaning exactly that version, not a version string —
    so the raw value builds a URL with square brackets in it, every fetch 404s, and the module that
    correctly declared the library is accused of not having it. `:core:health` compiles against
    `androidx.datastore.core` today with exactly this one declaration, which is the evidence that
    settled it.

    `[x]` is an exact pin. Anything else keeps its lower bound, which is what Gradle would resolve
    absent a competing constraint; a range with no lower bound is left alone to fail visibly rather
    than be guessed at.
    """
    v = version.strip()
    m = re.fullmatch(r"[\[(]\s*([^,\]\[)(]+)\s*[\])]", v)
    if m:
        return m.group(1).strip()
    m = re.match(r"[\[(]\s*([^,\]\[)(]+)\s*,", v)
    if m:
        return m.group(1).strip()
    return v


def bom_versions(group, artifact, version):
    """A BOM's dependencyManagement, so Compose artifacts get a real version."""
    path = fetch(group, artifact, version, "pom")
    if not path:
        return {}
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return {}
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    out = {}
    for d in root.findall("m:dependencyManagement/m:dependencies/m:dependency", ns):
        g = (d.findtext("m:groupId", namespaces=ns) or "").strip()
        a = (d.findtext("m:artifactId", namespaces=ns) or "").strip()
        v = (d.findtext("m:version", namespaces=ns) or "").strip()
        if g and a and v:
            out[(g, a)] = v
    return out


def packages_of(group, artifact, version):
    """
    Every package with a class in it, from the artifact's jar or aar.

    ⚠️ **A Kotlin Multiplatform artifact's plain AAR holds only a manifest**, and the classes live in
    a separate `-android` variant — `datastore-core:1.1.1` unpacks to a few hundred bytes and nothing
    else. Reading zero packages from it and reporting the importing module as undeclared is a false
    accusation, and it is exactly what the first run of this script did. So an empty result retries
    the platform variants before concluding anything. `tools/android_compile_check.sh` documents the
    same trap for the same reason.
    """
    for suffix in ("", "-android", "-jvm"):
        found = _packages_of_exact(group, artifact + suffix, version)
        if found:
            return found
    return set()


def _packages_of_exact(group, artifact, version):
    jar = fetch(group, artifact, version, "jar")
    names = []
    if jar:
        with zipfile.ZipFile(jar) as z:
            names = z.namelist()
    else:
        aar = fetch(group, artifact, version, "aar")
        if not aar:
            return set()
        with zipfile.ZipFile(aar) as z:
            if "classes.jar" not in z.namelist():
                return set()
            out = os.path.join(CACHE, f"{group}-{artifact}-{version}-classes.jar")
            if not os.path.exists(out):
                with open(out, "wb") as f:
                    f.write(z.read("classes.jar"))
            with zipfile.ZipFile(out) as inner:
                names = inner.namelist()
    return {os.path.dirname(n).replace("/", ".")
            for n in names if n.endswith(".class")}


# ------------------------------------------------------------------ the module

DEP_LINE = re.compile(
    r'^\s*(api|implementation|compileOnly)\s*\(\s*(platform\s*\(\s*)?'
    r'(libs\.[A-Za-z0-9._]+|project\("([^"]+)"\))')


def declared(module):
    """(external aliases, api project paths, implementation project paths, bom aliases)."""
    path = os.path.join(ROOT, module, "build.gradle.kts")
    text = open(path).read()
    start = text.find("\ndependencies")
    body = text[start:] if start >= 0 else text
    libs_, api_projects, impl_projects, boms = [], [], [], []
    for line in body.splitlines():
        m = DEP_LINE.match(line)
        if not m:
            continue
        conf, is_platform, ref, proj = m.group(1), m.group(2), m.group(3), m.group(4)
        if proj:
            (api_projects if conf == "api" else impl_projects).append(proj)
        elif is_platform:
            boms.append((conf, ref[len("libs."):]))
        else:
            libs_.append((conf, ref[len("libs."):]))
    return libs_, api_projects, impl_projects, boms


def module_dir(gradle_path):
    return gradle_path.lstrip(":").replace(":", "/")


def visible(module, versions, libs, seen=None, api_only=False):
    """Every package this module can name at compile time."""
    seen = seen if seen is not None else set()
    if module in seen:
        return set()
    seen.add(module)

    libs_, api_projects, impl_projects, boms = declared(module)

    managed = {}
    for _, alias in boms:
        c = resolve(alias, versions, libs)
        if c:
            managed.update(bom_versions(*c))

    out = set(source_packages(module))
    # ⚠️ `implementation` on a project dependency does not propagate to a consumer.
    # That is the rule the DataStore failure broke, so it is enforced here.
    for p in api_projects + ([] if api_only else impl_projects):
        out |= visible(module_dir(p), versions, libs, seen, api_only=True)

    pending = []
    for conf, alias in libs_:
        if api_only and conf != "api":
            continue
        c = resolve(alias, versions, libs)
        if not c:
            print(f"  ⚠️  {module}: no catalog entry for libs.{alias}")
            continue
        group, artifact, version = c
        pending.append((group, artifact, version or managed.get((group, artifact))))

    # Bounded transitive walk: a consumer sees an artifact's compile-scope closure.
    #
    # ⚠️ **Budgeted, and the budget is what makes this usable.** An unbounded walk over AndroidX
    # reaches several hundred artifacts and spends minutes downloading them the first time. Three
    # levels covers every case this is written to answer — material3 to foundation to ui is two —
    # and the cap is a backstop against a pathological graph rather than a tuned figure. A run that
    # hits it says so, because a silently truncated closure would report a declared library as
    # missing, which is the one wrong answer this must not give.
    depth, frontier = 0, pending
    done = set()
    truncated = False
    while frontier and depth < MAX_DEPTH:
        nxt = []
        for group, artifact, version in frontier:
            key = (group, artifact, version)
            if key in done or not version:
                continue
            if len(done) >= MAX_ARTIFACTS:
                truncated = True
                break
            done.add(key)
            out |= packages_of(group, artifact, version)
            nxt += pom_deps(group, artifact, version)
        frontier, depth = nxt, depth + 1
    if truncated:
        print(f"  ⚠️  {module}: stopped at {MAX_ARTIFACTS} artifacts — a MISSING below may be a "
              f"truncated closure rather than an undeclared library")
    return out


def source_packages(module):
    """
    The packages a module's own sources declare.

    ⚠️ Needed because a project dependency contributes CODE, not an artifact — the first run of this
    script reported every shared type as undeclared for want of these thirty lines, which is a
    harness that fails loudly and says nothing true. It also covers the module's own packages, so a
    file importing a sibling of its own is not flagged.
    """
    out = set()
    for dp, _, fs in os.walk(os.path.join(ROOT, module, "src/main")):
        for f in fs:
            if not f.endswith((".kt", ".java")):
                continue
            for line in open(os.path.join(dp, f)):
                m = re.match(r'package\s+([A-Za-z0-9_.]+)', line)
                if m:
                    out.add(m.group(1))
                    break
    return out


def own_imports(module):
    """Every non-free package imported by the module's own Kotlin sources."""
    out = {}
    for dp, _, fs in os.walk(os.path.join(ROOT, module, "src/main")):
        for f in fs:
            if not f.endswith(".kt"):
                continue
            full = os.path.join(dp, f)
            for line in open(full):
                m = re.match(r'import\s+([A-Za-z0-9_.]+)', line)
                if not m:
                    continue
                fq = m.group(1)
                if fq.startswith(FREE_PREFIXES):
                    continue
                pkg = fq.rsplit(".", 1)[0]
                # A member import (`Foo.Bar.baz`) sits one level deeper than its
                # package, so accept either reading rather than guessing.
                out.setdefault(pkg, set()).add(os.path.relpath(full, ROOT))
    return out


def main(modules):
    versions, libs = catalog()
    bad = 0
    for module in modules:
        module = module.rstrip("/")
        print(f"== {module}")
        have = visible(module, versions, libs)
        if not have:
            print("  ⚠️  resolved no packages at all — treat this run as inconclusive")
            bad += 1
            continue
        missing = {}
        for pkg, files in own_imports(module).items():
            parent = pkg.rsplit(".", 1)[0]
            if pkg in have or parent in have:
                continue
            missing[pkg] = files
        if missing:
            bad += 1
            for pkg in sorted(missing):
                print(f"  MISSING  {pkg}")
                for f in sorted(missing[pkg])[:3]:
                    print(f"             {f}")
        else:
            print(f"  ok — every imported package is declared ({len(have)} visible)")
    return 1 if bad else 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
