"""Skyfield/DE421 reference for S3: apparent RA/Dec of date, topocentric alt/az (no refraction)
at the owner's site, and Mallama magnitudes, for seven planets at the twelve PlanetCalcTest instants.
Also the Sun's geocentric apparent RA/Dec/distance at the same instants."""
import sys, json, math
from datetime import datetime, timezone
S = '/tmp/claude-0/-home-user-Thing/0cffb3a7-44ca-5d60-a23c-dc8d9cd6a052/scratchpad'
sys.path.insert(0, S + '/py')
from skyfield.api import load, wgs84
from skyfield.magnitudelib import planetary_magnitude
ts = load.timescale(builtin=True)


def ts_ut1_is_utc(ms):
    """ΔT := TT−UTC at this instant, so UT1 == UTC. The alt/az fixtures use it because the app
    ignores DUT1 by design (as Stellarium does) and a reference carrying it — real before 2026,
    EXTRAPOLATED to −1.35 s by 2045 — would fail a tight bar over a quantity nobody can know."""
    t = ts.from_datetime(datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc))
    return load.timescale(builtin=True, delta_t=float(t.delta_t) + float(t.dut1))  # = TT - UTC


eph = load(S + '/eph/de421.bsp')
earth = eph['earth']; sun = eph['sun']
site = earth + wgs84.latlon(42.7875, -86.1089)
ms_list = [946684800000, 1078012800000, 1209340800000, 1340668800000, 1471996800000, 1603324800000,
           1734652800000, 1865980800000, 1997308800000, 2128636800000, 2259964800000, 2391292800000]
planets = [('Mercury','mercury'),('Venus','venus'),('Mars','mars'),('Jupiter','jupiter barycenter'),
           ('Saturn','saturn barycenter'),('Uranus','uranus barycenter'),('Neptune','neptune barycenter')]
out = []
for ms in ms_list:
    # ⚠️ NOT ts.utc(1970,1,1,0,0, ms/1000): Skyfield looks the leap seconds up for the DATE PART
    # (1970 → the table's first entry, 10 s) and then adds the seconds, so every instant lands
    # 22–27 s early in TT and in UT1 — 400 arcseconds of rotation and a few of Mercury.
    t = ts.from_datetime(datetime.fromtimestamp(ms/1000.0, tz=timezone.utc))
    row = {'ms': ms, 'bodies': {}}
    for name, key in planets:
        astro = earth.at(t).observe(eph[key])
        app = astro.apparent()
        ra, dec, dist = app.radec(epoch='date')
        tf = ts_ut1_is_utc(ms).from_datetime(datetime.fromtimestamp(ms/1000.0, tz=timezone.utc))
        alt, az, d2 = site.at(tf).observe(eph[key]).apparent().altaz()
        mag = float(planetary_magnitude(astro))
        # ⚠️ planetary_magnitude 'shamelessly treats the Sun as sitting at the SSB' (its own
        # comment), which is 0.005-0.01 AU wrong — a tenth of a magnitude on Mercury. magSun is
        # the same formula fed the TRUE heliocentric vectors, which is what the app computes.
        import numpy as np
        from skyfield import magnitudelib as ml
        helio = (eph[key].at(t) - sun.at(t)).xyz.au
        geo = astro.xyz.au
        r_h = float(np.linalg.norm(helio)); d_g = float(np.linalg.norm(geo))
        ph = float(np.degrees(np.arccos(np.clip(np.dot(helio, geo)/(r_h*d_g), -1, 1))))
        fn = ml._FUNCTIONS[{'mercury':199,'venus':299,'mars':499,'jupiter barycenter':599,'saturn barycenter':699,'uranus barycenter':799,'neptune barycenter':899}[key]]
        if fn is ml._saturn_magnitude or fn is ml._uranus_magnitude:
            pole = ml._SATURN_POLE if fn is ml._saturn_magnitude else ml._URANUS_POLE
            sl = float(np.degrees(np.arccos(np.dot(pole, helio)/r_h))) - 90.0
            el = float(np.degrees(np.arccos(np.dot(pole, geo)/d_g))) - 90.0
            magSun = float(fn(r_h, d_g, ph, sl, el))
        elif fn is ml._neptune_magnitude:
            magSun = float(fn(r_h, d_g, ph, t.J))
        else:
            magSun = float(fn(r_h, d_g, ph))
        row['bodies'][name] = {'ra': ra._degrees, 'dec': dec.degrees, 'au': dist.au,
                               'alt': alt.degrees, 'az': az.degrees, 'mag': mag, 'magSun': magSun}
    sra, sdec, sd = earth.at(t).observe(sun).apparent().radec(epoch='date')
    row['sun'] = {'ra': sra._degrees, 'dec': sdec.degrees, 'au': sd.au}
    out.append(row)
json.dump(out, open(S + '/vsop87/planets_ref.json','w'), indent=1)
for row in out:
    print(row['ms'], ' '.join('%s %.5f %.5f m%.2f' % (n[:3], b['ra'], b['dec'], b['mag']) for n,b in row['bodies'].items()))
