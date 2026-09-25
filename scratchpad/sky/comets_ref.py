"""Skyfield/DE421 reference for CometsTest: the nine inline fixtures, ASTROMETRIC and APPARENT.

Reproduces the fixtures as they stand (astrometric, true equinox of date — the first block must
match the committed RA/Dec to the last digit, which is what proves this harness builds the same
orbit the original did) and then prints the APPARENT place beside it, which is what the solver
produces once it applies annual aberration the way the planets have since S3.

⚠️ The instant is the app's own: `Ephemeris.julianDateTT(epochMs)` is JD(UTC) + a CONSTANT
TT−UTC, so the reference is built with `ts.tt_jd` on that exact number rather than from a UTC
datetime through Skyfield's own ΔT table — the test's KDoc says "no clock difference can hide
inside a tolerance", and that is the sentence this line keeps true.

⚠️ `.apparent()` carries gravitational light deflection by the Sun as well as aberration. The app
does not (neither does its planet path); at the fixtures' elongations that is a few milliarcseconds
against a bar of one arcsecond or more, and the last column says how much of the astrometric→
apparent move is aberration alone so a failing fixture can be attributed rather than guessed at.
"""
import sys
S = '/tmp/claude-0/-home-user-Thing/0cffb3a7-44ca-5d60-a23c-dc8d9cd6a052/scratchpad'
sys.path.insert(0, S + '/py')
from skyfield.api import load
from skyfield.keplerlib import _KeplerOrbit
from skyfield.data.spice import inertial_frames
from skyfield.constants import GM_SUN_Pitjeva_2005_km3_s2 as GM_SUN
from skyfield.units import Angle
import numpy as np

ts = load.timescale(builtin=True)
eph = load(S + '/eph/de421.bsp')
earth, sun = eph['earth'], eph['sun']

EPOCH_MS = 1788220800000            # 2026-09-01T00:00:00Z, CometsTest.epochMs
TT_MINUS_UTC = float(sys.argv[1]) if len(sys.argv) > 1 else 69.184   # the app's constant
jd_tt = 2440587.5 + EPOCH_MS / 86400000.0 + TT_MINUS_UTC / 86400.0
t = ts.tt_jd(jd_tt)

# name, q, e, perihelion JD(TT), w, node, i — verbatim from CometsTest.fixtures
FIX = [
    ("1P/Halley",                    0.571114, 0.968020, 2474039.6476, 112.1962,  59.2960, 162.1871),
    ("2P/Encke",                     0.338624, 0.847311, 2461446.7281, 187.2865, 334.0194,  11.3479),
    ("C/1995 O1 (Hale-Bopp)",        0.924542, 0.994899, 2450536.5341, 130.7191, 281.7980,  89.7393),
    ("342P/SOHO",                    0.051750, 0.982976, 2461444.2817,  27.7057,  73.2548,  11.6741),
    ("C/2023 A3 (Tsuchinshan-ATLAS)",0.391359, 1.000177, 2460581.3196, 308.5764,  21.6641, 139.1010),
    ("1I/`Oumuamua",                 0.255240, 1.199252, 2458005.9886, 241.6845,  24.5997, 122.6778),
    ("2I/Borisov",                   1.997724, 3.345952, 2458826.5572, 209.2911, 307.8024,  44.2624),
    ("3I/ATLAS",                     1.356507, 6.139884, 2460977.9825, 128.0055, 322.1535, 175.1129),
    ("C/2020 P4-C (SOHO)",           0.089005, 1.013174, 2459066.6372, 115.7125, 165.5622,  37.2249),
]


def orbit(q, e, jd_peri, w, node, i, name):
    # mpc.comet_orbit, without the pandas row.
    if e == 1.0:
        p = q * 2.0
    else:
        a = q / (1.0 - e)
        p = a * (1.0 - e * e)
    c = _KeplerOrbit._from_periapsis(p, e, i, node, w, ts.tt_jd(jd_peri), GM_SUN, 10, name)
    c._rotation = inertial_frames['ECLIPJ2000'].T
    return c


def sep_arcsec(ra1, dec1, ra2, dec2):
    r1, d1, r2, d2 = np.radians([ra1, dec1, ra2, dec2])
    c = np.sin(d1) * np.sin(d2) + np.cos(d1) * np.cos(d2) * np.cos(r1 - r2)
    return np.degrees(np.arccos(np.clip(c, -1.0, 1.0))) * 3600.0


print(f"# TT-UTC {TT_MINUS_UTC} s, jd_tt {jd_tt:.9f}")
print("# name | astrometric ra dec | apparent ra dec | delta AU | move\" | aberration-only\"")
for name, q, e, jd, w, node, i in FIX:
    astro = earth.at(t).observe(sun + orbit(q, e, jd, w, node, i, name))
    ra_a, dec_a, dist = astro.radec(epoch='date')
    app = astro.apparent()
    ra_p, dec_p, _ = app.radec(epoch='date')
    # Aberration alone: displace the astrometric unit vector by Earth's barycentric velocity / c,
    # in the same frame the radec above is measured in — so the last column isolates what the app
    # is about to add from the deflection it is not.
    v = earth.at(t).velocity.au_per_d          # barycentric, AU/day, ICRS
    C_AU_PER_DAY = 173.1446326846693
    u = astro.position.au / np.linalg.norm(astro.position.au)
    u2 = u + v / C_AU_PER_DAY
    u2 /= np.linalg.norm(u2)
    from skyfield.positionlib import ICRF
    ab = ICRF(u2, t=t)
    ra_b, dec_b, _ = ab.radec(epoch='date')
    move = sep_arcsec(ra_a.degrees, dec_a.degrees, ra_p.degrees, dec_p.degrees)
    ab_only = sep_arcsec(ra_a.degrees, dec_a.degrees, ra_b.degrees, dec_b.degrees)
    print(f"{name} | {ra_a.degrees!r} {dec_a.degrees!r} | {ra_p.degrees!r} {dec_p.degrees!r} | "
          f"{dist.au!r} | {move:.3f} | {ab_only:.3f}")
