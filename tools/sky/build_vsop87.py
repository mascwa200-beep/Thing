#!/usr/bin/env python3
"""Build the truncated VSOP87B planetary tables the star map's planets are computed from.

Source: VSOP87 (Bretagnon & Francou 1988), variant B — heliocentric SPHERICAL coordinates (L, B, R)
referred to the J2000 dynamical ecliptic and equinox — from CDS catalogue VI/81, one file per planet:
    https://cdsarc.cds.unistra.fr/ftp/VI/81/VSOP87B.{mer,ven,ear,mar,jup,sat,ura,nep}

Why B and why J2000: the catalogue, `Comets` and `Ephemeris.earthHeliocentricJ2000Au` all already speak
J2000, so there is no FK5 or of-date fudge anywhere in the chain. The full theory is 35,080 terms; this
truncates each planet so the GEOCENTRIC direction error it introduces, measured against the untruncated
series over 1900-2100, stays under --target arcseconds. The number that matters to a chart is the
geocentric one, so that is what is bounded, not the heliocentric coordinate error.

The truncation rule is one threshold per planet: a term of the T^k series is kept when
A * TMAX^k >= epsilon, with TMAX = 0.1 millennia (the half-width of 1900-2100 around J2000). The
threshold is found by a ladder search so the emitted table is the smallest that meets the target on
a 2,001-point grid, and then RE-MEASURED on 400 random instants the search never saw.

Output is Kotlin source, one `internal object` PER PLANET.
⚠️ Per planet and not one object for all eight, and that is a JVM constraint rather than taste: a
`doubleArrayOf(...)` literal is initialised element by element in the class's static initialiser,
about eight bytes of bytecode per double, and the JVM refuses any method over 64 KB. All eight planets
in one object would be one <clinit> and it would not load. The builder refuses to emit an object over
MAX_DOUBLES_PER_OBJECT so the limit is hit here and not on a phone.

Usage:
    python3 tools/sky/build_vsop87.py <dir-with-VSOP87B.*> [--target 0.5] [--out <Vsop87Tables.kt>]
                                                            [--pins <pins.json>]
Byte-reproducible: the same inputs and target produce the same file.
"""
import argparse
import json
import math
import os
import sys

import numpy as np

PLANETS = [
    # (kotlin object suffix, file suffix)
    ("Mercury", "mer"),
    ("Venus", "ven"),
    ("Earth", "ear"),
    ("Mars", "mar"),
    ("Jupiter", "jup"),
    ("Saturn", "sat"),
    ("Uranus", "ura"),
    ("Neptune", "nep"),
]
VARS = {1: "L", 2: "B", 3: "R"}
TMAX = 0.1                    # millennia either side of J2000: 1900..2100
ARCSEC = math.pi / 180.0 / 3600.0
MAX_DOUBLES_PER_OBJECT = 6000  # ~48 KB of <clinit>, well under the JVM's 64 KB
GRID = 2001
RANDOM_CHECKS = 400
LADDER = [10 ** (e / 4.0) for e in range(-48, -12)]  # 1e-12 .. ~1e-3, quarter-decade steps


def parse(path):
    """{(var, power): [(A, B, C), ...]} in file order (descending amplitude within each series)."""
    series = {}
    with open(path) as f:
        for line in f:
            if line.startswith(" VSOP87 VERSION"):
                continue
            if len(line) < 131:
                raise SystemExit(f"{path}: short line {line!r}")
            var = int(line[3])
            power = int(line[4])
            # ⚠️ Fixed columns, not whitespace: a negative amplitude can run into its neighbour.
            a = float(line[79:97])
            b = float(line[97:111])
            c = float(line[111:131])
            series.setdefault((var, power), []).append((a, b, c))
    return series


def evaluate(series, t, keep=None):
    """L, B, R arrays over the instants `t` (millennia), optionally restricted to `keep[(var,pow)]`
    = number of leading terms to use (None = all)."""
    out = {}
    for var in (1, 2, 3):
        total = np.zeros_like(t)
        for power in range(6):
            terms = series.get((var, power), [])
            n = len(terms) if keep is None else keep.get((var, power), 0)
            if n == 0:
                continue
            arr = np.array(terms[:n])
            # A cos(B + C T), summed over the terms, times T^power
            total += (arr[:, 0][:, None] * np.cos(arr[:, 1][:, None] + arr[:, 2][:, None] * t[None, :])).sum(axis=0) * t ** power
        out[var] = total
    return out[1], out[2], out[3]


def rectangular(lbr):
    L, B, R = lbr
    return np.stack([R * np.cos(B) * np.cos(L), R * np.cos(B) * np.sin(L), R * np.sin(B)])


def kept_for(series, eps):
    """How many leading terms each (var, power) series keeps at threshold eps: the terms are in
    descending amplitude within a series, so the kept prefix is contiguous."""
    keep = {}
    for key, terms in series.items():
        power = key[1]
        scale = TMAX ** power
        n = 0
        for a, _, _ in terms:
            if a * scale >= eps:
                n += 1
            else:
                break
        keep[key] = n
    return keep


def worst_geocentric_arcsec(full_xyz, trunc_xyz, delta_au):
    """Largest angle the truncation moves the geocentric DIRECTION by, over the grid."""
    err = np.linalg.norm(full_xyz - trunc_xyz, axis=0)
    return float((err / delta_au).max() / ARCSEC)


def fmt(x):
    """Shortest round-trip literal — Python's repr and Kotlin's parser agree on IEEE doubles."""
    s = repr(float(x))
    if "e" in s or "E" in s:
        mant, exp = s.split("e")
        if "." not in mant:
            mant += ".0"
        s = f"{mant}e{int(exp)}"
    elif "." not in s:
        s += ".0"
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dir")
    ap.add_argument("--target", type=float, default=0.5, help="geocentric direction error bound, arcsec")
    ap.add_argument("--out", default="core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Vsop87Tables.kt")
    ap.add_argument("--pins", default=None, help="write reference values for Vsop87Test here (json)")
    args = ap.parse_args()

    t_grid = np.linspace(-TMAX, TMAX, GRID)
    rng = np.random.RandomState(87)
    t_rand = rng.uniform(-TMAX, TMAX, RANDOM_CHECKS)

    full = {}
    for name, suffix in PLANETS:
        path = os.path.join(args.dir, f"VSOP87B.{suffix}")
        series = parse(path)
        full[name] = (series, rectangular(evaluate(series, t_grid)), rectangular(evaluate(series, t_rand)))
    earth_xyz, earth_rand = full["Earth"][1], full["Earth"][2]

    # The distance each planet's error is seen across. For the Earth itself the closest thing that
    # is ever looked at from it decides: Venus at inferior conjunction, a quarter of an AU.
    delta = {}
    delta_rand = {}
    for name, _ in PLANETS:
        if name == "Earth":
            continue
        delta[name] = np.linalg.norm(full[name][1] - earth_xyz, axis=0)
        delta_rand[name] = np.linalg.norm(full[name][2] - earth_rand, axis=0)
    delta["Earth"] = np.min(np.stack([delta[n] for n in ("Mercury", "Venus", "Mars")]), axis=0)
    delta_rand["Earth"] = np.min(np.stack([delta_rand[n] for n in ("Mercury", "Venus", "Mars")]), axis=0)

    report = {}
    tables = {}
    pins = {}
    for name, _ in PLANETS:
        series, xyz_full, xyz_rand_full = full[name]
        chosen = None
        for eps in reversed(LADDER):  # coarsest first: the first that meets the target is the smallest table
            keep = kept_for(series, eps)
            xyz = rectangular(evaluate(series, t_grid, keep))
            worst = worst_geocentric_arcsec(xyz_full, xyz, delta[name])
            if worst <= args.target:
                chosen = (eps, keep, worst)
                break
        if chosen is None:
            raise SystemExit(f"{name}: no threshold on the ladder meets {args.target} arcsec")
        eps, keep, worst_grid = chosen
        xyz_rand = rectangular(evaluate(series, t_rand, keep))
        worst_rand = worst_geocentric_arcsec(xyz_rand_full, xyz_rand, delta_rand[name])
        n_terms = sum(keep.values())
        n_full = sum(len(v) for v in series.values())
        if 3 * n_terms > MAX_DOUBLES_PER_OBJECT:
            raise SystemExit(f"{name}: {3 * n_terms} doubles would exceed the per-object bytecode budget")
        report[name] = dict(eps=eps, terms=n_terms, full=n_full, worst_grid=worst_grid, worst_random=worst_rand)
        tables[name] = (series, keep)

        # Pins: the FULL series at three instants, so the Kotlin evaluator over the truncated table
        # can be held to the bound above rather than to itself.
        pin_t = np.array([0.0, -0.05, 0.075])
        Lf, Bf, Rf = evaluate(series, pin_t)
        Lt, Bt, Rt = evaluate(series, pin_t, keep)
        pins[name] = {
            "jdTT": [2451545.0 + tt * 365250.0 for tt in pin_t],
            "full": [[float(Lf[i] % (2 * math.pi)), float(Bf[i]), float(Rf[i])] for i in range(3)],
            "truncated": [[float(Lt[i] % (2 * math.pi)), float(Bt[i]), float(Rt[i])] for i in range(3)],
            "terms": n_terms,
        }

    lines = []
    w = lines.append
    w("// GENERATED by tools/sky/build_vsop87.py — DO NOT EDIT BY HAND. Regenerate with:")
    w("//     python3 tools/sky/build_vsop87.py <dir holding VSOP87B.mer .. VSOP87B.nep> --target %s" % fmt(args.target))
    w("//")
    w("// VSOP87B (Bretagnon & Francou 1988, CDS VI/81): heliocentric spherical L, B (radians) and R (AU),")
    w("// J2000 dynamical ecliptic and equinox, each coordinate a sum over T^k (k = 0..5, T in Julian")
    w("// millennia of TT from J2000) of A·cos(B + C·T). Terms are (A, B, C) triples, flattened.")
    w("//")
    w("// Truncated so the GEOCENTRIC direction error against the full 35,080-term theory stays under")
    w("// %s arcsec over 1900–2100 (2,001-point grid, then re-measured on 400 random instants):" % fmt(args.target))
    w("//")
    w("//   planet    kept / full   threshold     worst on grid   worst random")
    for name, _ in PLANETS:
        r = report[name]
        w("//   %-8s %5d / %5d   %-10s    %6.3f\"          %6.3f\"" % (name, r["terms"], r["full"], "%.2e" % r["eps"], r["worst_grid"], r["worst_random"]))
    w("//")
    w("// ⚠️ One object per planet because a doubleArrayOf literal is built element by element in the")
    w("// class's static initialiser and the JVM refuses any method over 64 KB; all eight in one object")
    w("// would not load. The builder enforces %d doubles per object." % MAX_DOUBLES_PER_OBJECT)
    w("@file:Suppress(\"MagicNumber\", \"LargeClass\")")
    w("")
    w("package dev.mascwa.pulse.core.telemetry")
    w("")
    for name, _ in PLANETS:
        series, keep = tables[name]
        w("internal object Vsop87%s {" % name)
        w("    const val TERMS = %d" % report[name]["terms"])
        for var, letter in VARS.items():
            powers = []
            for power in range(6):
                n = keep.get((var, power), 0)
                powers.append(series.get((var, power), [])[:n])
            # Drop trailing empty powers so the evaluator's loop is as short as the data.
            while powers and not powers[-1]:
                powers.pop()
            w("    @JvmField")
            w("    val %s: Array<DoubleArray> = arrayOf(" % letter)
            for power, terms in enumerate(powers):
                if not terms:
                    w("        doubleArrayOf(), // T^%d: nothing survives the cut" % power)
                    continue
                w("        doubleArrayOf( // T^%d, %d terms" % (power, len(terms)))
                for a, b, c in terms:
                    w("            %s, %s, %s," % (fmt(a), fmt(b), fmt(c)))
                w("        ),")
            w("    )")
        w("}")
        w("")
    text = "\n".join(lines) + "\n"
    with open(args.out, "w") as f:
        f.write(text)
    print("wrote %s (%d bytes)" % (args.out, len(text)))
    for name, _ in PLANETS:
        r = report[name]
        print("  %-8s %5d / %5d terms  eps=%.2e  worst grid %.3f\"  random %.3f\"" % (name, r["terms"], r["full"], r["eps"], r["worst_grid"], r["worst_random"]))
    if args.pins:
        with open(args.pins, "w") as f:
            json.dump(pins, f, indent=1)
        print("pins -> %s" % args.pins)


if __name__ == "__main__":
    main()
