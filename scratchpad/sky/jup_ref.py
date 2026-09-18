"""Ground truth for PlanetDisc from DE421: apparent diameters, phase angles, Saturn's ring opening.

⚠️ Galilean moon positions are NOT in DE421 (it holds only the Jupiter barycentre), so those are
checked against their published orbital radii and periods instead — see the probe that uses this.
"""
import json, math, datetime
from skyfield.api import load

ts = load.timescale()
eph = load('scratchpad/sky/de421.bsp')
earth, sun = eph['earth'], eph['sun']
AU_KM = 149597870.7

TARGETS = {'MERCURY': ('mercury barycenter', 2439.7), 'VENUS': ('venus barycenter', 6051.8),
           'MARS': ('mars barycenter', 3396.2), 'JUPITER': ('jupiter barycenter', 71492.0),
           'SATURN': ('saturn barycenter', 60268.0)}

out = []
for iso in ['2026-08-29T00:00:00Z', '2026-03-15T12:00:00Z', '2025-11-01T06:00:00Z']:
    t = ts.from_datetime(datetime.datetime.fromisoformat(iso.replace('Z', '+00:00')))
    row = {'iso': iso, 'epochMs': int(t.utc_datetime().timestamp() * 1000), 'bodies': {}}
    for name, key, rkm in [('SUN', 'sun', 696000.0), ('MOON', 'moon', 1737.4)]:
        d_km = earth.at(t).observe(eph[key]).apparent().distance().au * AU_KM
        row['bodies'][name] = {'distanceKm': d_km, 'diamDeg': 2 * math.degrees(math.asin(rkm / d_km))}
    # Heliocentric distances by straight vector difference — no light time, which is far below the
    # precision a triangle needs and avoids Skyfield's deflector lookup running off the kernel's end.
    sun_xyz = sun.at(t).xyz.au
    earth_xyz = earth.at(t).xyz.au
    r_earth = math.dist(earth_xyz, sun_xyz)
    for name, (key, rkm) in TARGETS.items():
        ast = earth.at(t).observe(eph[key]).apparent()
        ra, dec, dist = ast.radec()
        d_au = dist.au
        r_helio = math.dist(eph[key].at(t).xyz.au, sun_xyz)
        cosph = (r_helio ** 2 + d_au ** 2 - r_earth ** 2) / (2 * r_helio * d_au)
        phase = math.degrees(math.acos(max(-1.0, min(1.0, cosph))))
        row['bodies'][name] = {
            'raDeg': ra._degrees, 'decDeg': dec.degrees, 'distanceAu': d_au,
            'distanceKm': d_au * AU_KM,
            'diamDeg': 2 * math.degrees(math.asin(rkm / (d_au * AU_KM))),
            'phaseAngleDeg': phase,
            'illuminated': (1 + math.cos(math.radians(phase))) / 2,
        }
    # Saturn's ring opening angle B, computed independently from the IAU pole.
    s = row['bodies']['SATURN']
    def unit(ra_deg, dec_deg):
        r, d = math.radians(ra_deg), math.radians(dec_deg)
        return (math.cos(d) * math.cos(r), math.cos(d) * math.sin(r), math.sin(d))
    p = unit(40.589, 83.537)
    v = unit(s['raDeg'], s['decDeg'])
    s['ringOpeningDeg'] = -math.degrees(math.asin(sum(a * b for a, b in zip(p, v))))
    out.append(row)

print(json.dumps(out, indent=1))
