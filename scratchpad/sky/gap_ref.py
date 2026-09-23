#!/usr/bin/env python3
"""The Stellarium gap: what the star map draws against what Skyfield/DE421 says is actually there.

Two halves. `gen` writes a fixture of random J2000 directions and a few instants; `compare` reads
the Kotlin probe's answers for the same fixture (StellariumGapProbe.kt, run through runmain.sh)
and scores them against Skyfield's APPARENT topocentric alt/az — which is what Stellarium draws by
default — with refraction both off and on.

    python3 gap_ref.py gen   > gap_dirs.tsv
    ./runmain.sh StellariumGapProbe.kt dev.mascwa.pulse.core.telemetry.StellariumGapProbeKt < gap_dirs.tsv > gap_ours.tsv
    python3 gap_ref.py compare gap_dirs.tsv gap_ours.tsv

Site: the owner's (Holland, Michigan). Deterministic seed so the "before" and every "after" score
the same sky.
"""
import math
import random
import sys

SCRATCH = "/tmp/claude-0/-home-user-Thing/0cffb3a7-44ca-5d60-a23c-dc8d9cd6a052/scratchpad"
sys.path.insert(0, SCRATCH + "/py")

LAT, LON = 42.7875, -86.1089
# 2026-09-23T02:00Z, 2026-12-21T06:00Z, 2027-06-21T04:00Z
INSTANTS_MS = [1_790_128_800_000, 1_797_832_000_000, 1_813_536_000_000]
N = 500


def gen():
    rng = random.Random(20260923)
    print("# ms\tra_deg\tdec_deg")
    for ms in INSTANTS_MS:
        for _ in range(N):
            ra = rng.uniform(0.0, 360.0)
            # Uniform on the sphere, not in declination.
            dec = math.degrees(math.asin(rng.uniform(-1.0, 1.0)))
            print(f"{ms}\t{ra:.6f}\t{dec:.6f}")
        # The Sun and the Moon ride the same fixture, flagged by a body name.
        print(f"{ms}\tSUN\t0")
        print(f"{ms}\tMOON\t0")


def sep_arcsec(alt1, az1, alt2, az2):
    a1, a2 = math.radians(alt1), math.radians(alt2)
    d = math.radians(az1 - az2)
    c = math.sin(a1) * math.sin(a2) + math.cos(a1) * math.cos(a2) * math.cos(d)
    return math.degrees(math.acos(max(-1.0, min(1.0, c)))) * 3600.0


def compare(dirs_path, ours_path):
    from skyfield.api import load, Star, wgs84
    from skyfield.timelib import Time
    eph = load(SCRATCH + "/eph/de421.bsp")
    ts = load.timescale()
    earth = eph["earth"]
    site = earth + wgs84.latlon(LAT, LON)

    ours = {}
    for line in open(ours_path):
        if line.startswith("#") or not line.strip():
            continue
        ms, key, alt, az = line.rstrip("\n").split("\t")
        ours[(int(ms), key)] = (float(alt), float(az))

    rows = []  # (band, refracted_err, unrefracted_err, alt)
    bodies = []
    for line in open(dirs_path):
        if line.startswith("#") or not line.strip():
            continue
        ms_s, ra_s, dec_s = line.rstrip("\n").split("\t")
        ms = int(ms_s)
        t = ts.from_datetime(__import__("datetime").datetime.fromtimestamp(ms / 1000.0, __import__("datetime").timezone.utc))
        if ra_s in ("SUN", "MOON"):
            body = eph["sun"] if ra_s == "SUN" else eph["moon"]
            app = site.at(t).observe(body).apparent()
            alt, az, _ = app.altaz()
            altr, azr, _ = app.altaz(temperature_C=10.0, pressure_mbar=1010.0)
            o = ours.get((ms, ra_s))
            if o:
                bodies.append((ra_s, ms, sep_arcsec(alt.degrees, az.degrees, *o),
                               sep_arcsec(altr.degrees, azr.degrees, *o), alt.degrees))
            continue
        key = f"{float(ra_s):.6f},{float(dec_s):.6f}"
        o = ours.get((ms, key))
        if not o:
            continue
        star = Star(ra_hours=float(ra_s) / 15.0, dec_degrees=float(dec_s))
        app = site.at(t).observe(star).apparent()
        alt, az, _ = app.altaz()
        altr, azr, _ = app.altaz(temperature_C=10.0, pressure_mbar=1010.0)
        rows.append((alt.degrees, sep_arcsec(alt.degrees, az.degrees, *o),
                     sep_arcsec(altr.degrees, azr.degrees, *o)))

    def stats(vals):
        v = sorted(vals)
        if not v:
            return "n=0"
        return (f"n={len(v):4d}  median {v[len(v)//2]:8.1f}\"  p95 {v[int(len(v)*0.95)]:8.1f}\"  "
                f"worst {v[-1]:8.1f}\"")

    print("STARS — error of the chart's drawn position against Skyfield apparent alt/az")
    for name, lo, hi in [("all above horizon", 0, 91), ("0–5° up", 0, 5), ("5–20° up", 5, 20),
                         ("20–90° up", 20, 91)]:
        band = [r for r in rows if lo <= r[0] < hi]
        print(f"  {name:18s} vs Skyfield WITHOUT refraction: {stats([r[1] for r in band])}")
        print(f"  {'':18s} vs Skyfield WITH    refraction: {stats([r[2] for r in band])}")
    print("BODIES — the same, for the Sun and Moon through Ephemeris.sunPosition/moonPosition")
    for name, ms, e0, e1, alt in bodies:
        print(f"  {name:4s} @{ms}  alt {alt:6.1f}°  no-refraction {e0:7.1f}\"   refraction {e1:7.1f}\"")


if __name__ == "__main__":
    if sys.argv[1] == "gen":
        gen()
    else:
        compare(sys.argv[2], sys.argv[3])
