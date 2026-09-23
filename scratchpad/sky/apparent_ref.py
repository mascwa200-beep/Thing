"""Skyfield/DE421 reference for S4: sidereal time (GMST, GAST) at the two EphemerisTest instants, and
apparent places of fixed J2000 star directions — RA/Dec in the true equinox of date and alt/az at the
owner's site with refraction off — at five instants 2000–2045.

⚠️ Two timescales, on purpose. The RA/Dec of date is TT-driven and is taken from Skyfield's default
timescale (its ΔT table). The alt/az is UT1-driven, and Skyfield's UT1 carries DUT1 — real from the
IERS table before ~2026, then EXTRAPOLATED (−1.35 s by 2045, which is 20″ of hour angle nobody can
know today). The app ignores DUT1 by design, as Stellarium does, so a second timescale with a fixed
ΔT equal to TT−UTC (so UT1 = UTC) isolates the arithmetic under test from a quantity that is not a
fact; the real-DUT1 alt/az is printed beside it so the residual it costs is measured, not assumed.

Stars are read out of the bundled bright catalogue (core/sky/src/main/assets/sky/stars.tsv), so the
fixtures are directions the chart actually draws.
"""
import sys
from datetime import datetime, timezone
S = '/tmp/claude-0/-home-user-Thing/0cffb3a7-44ca-5d60-a23c-dc8d9cd6a052/scratchpad'
sys.path.insert(0, S + '/py')
from skyfield.api import load, wgs84, Star

ts = load.timescale(builtin=True)


def ts_ut1_is_utc(ms):
    """A timescale whose ΔT equals TT−UTC AT THIS INSTANT (32.184 s + the leap seconds then standing),
    so that UT1 == UTC exactly. ⚠️ Not a single fixed 69.184: before 2017 fewer leap seconds stood,
    and a fixed value put the year-2000 rows 5 s (75\") off. The app itself holds TT−UTC at 69.184
    everywhere, which is 5 s wrong on a year-2000 THEORY instant — nothing on a star, 2.7\" on the
    Moon — and that is its known approximation, measured separately."""
    t = ts.from_datetime(datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc))
    tt_minus_utc = float(t.delta_t) + float(t.dut1)  # (TT - UT1) + (UT1 - UTC); 69.184 from 2017 on
    return load.timescale(builtin=True, delta_t=tt_minus_utc)
eph = load(S + '/eph/de421.bsp')
earth = eph['earth']
site = earth + wgs84.latlon(42.7875, -86.1089)
site_fixed = earth + wgs84.latlon(42.7875, -86.1089)


def at(tsx, ms):
    return tsx.from_datetime(datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc))


print("# ---- sidereal time, degrees ----")
for ms in (1767225600000, 1782777600000):
    t = at(ts, ms)
    tf = at(ts_ut1_is_utc(ms), ms)
    print(f"{ms}\tgmst={t.gmst * 15:.6f}\tgast={t.gast * 15:.6f}\tgast_fixed_dut1_0={tf.gast * 15:.6f}\tdut1={float(t.dut1):.4f}\tcheck_dut1_fixed={float(tf.dut1):.6f}")

print("# ---- stars ----")
rows = []
with open('/home/user/Thing/core/sky/src/main/assets/sky/stars.tsv') as f:
    header = f.readline().rstrip('\n').split('\t')
    for line in f:
        if line.startswith('#') or not line.strip():
            continue  # the file carries further comment lines after its header
        rows.append(line.rstrip('\n').split('\t'))
# The bundled catalogue: `# ra`, `dec` (degrees, J2000), mag, bv, bayer, flamsteed, constellation, pm.
assert header[0].lstrip('# ').strip() == 'ra' and header[1] == 'dec', header
ira, idec, ibayer, icon = 0, 1, 4, 6
# The file is sorted brightest first, so row 0 is Sirius: a sanity check that the columns are degrees.
assert abs(float(rows[0][ira]) - 101.287) < 0.01 and abs(float(rows[0][idec]) + 16.716) < 0.01, rows[0]

# Ten across the sky: the brightest row in each declination band.
picked = []
bands = [(-90, -50), (-50, -25), (-25, -5), (-5, 10), (10, 25), (25, 40), (40, 55), (55, 70), (70, 80), (80, 90)]
for lo, hi in bands:
    for r in rows:
        d = float(r[idec])
        if lo <= d < hi:
            picked.append(r)
            break
ms_list = [946684800000, 1340668800000, 1734652800000, 1997308800000, 2391292800000]
print("# ms\tname\tra_j2000\tdec_j2000\tra_date\tdec_date\talt_fixed\taz_fixed\talt_real\taz_real\tdut1")
for r in picked:
    ra = float(r[ira]); dec = float(r[idec])
    name = (r[ibayer] + ' ' + r[icon]).strip() or f"{ra:.3f},{dec:.3f}"
    star = Star(ra_hours=ra / 15.0, dec_degrees=dec)
    for ms in ms_list:
        t = at(ts, ms)
        tf = at(ts_ut1_is_utc(ms), ms)
        app = earth.at(t).observe(star).apparent()
        rad, decd, _ = app.radec(epoch='date')
        alt_f, az_f, _ = site_fixed.at(tf).observe(star).apparent().altaz()
        alt_r, az_r, _ = site.at(t).observe(star).apparent().altaz()
        print(f"{ms}\t{name}\t{ra:.6f}\t{dec:.6f}\t{rad._degrees:.7f}\t{decd.degrees:.7f}\t"
              f"{alt_f.degrees:.7f}\t{az_f.degrees:.7f}\t{alt_r.degrees:.7f}\t{az_r.degrees:.7f}\t{float(t.dut1):.4f}")
