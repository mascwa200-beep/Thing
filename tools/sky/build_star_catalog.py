#!/usr/bin/env python3
"""
Turn the Yale Bright Star Catalogue into the compact asset the sky map reads.

⚠️ **Run this against a freshly downloaded catalogue, never against a hand-edited asset.** The
output is derived data; the source of truth is the BSC and nothing in the repository should ever be
the only copy of a star's position.

    curl -o bsc5.gz https://cdsarc.cds.unistra.fr/ftp/V/50/catalog.gz
    python3 tools/sky/build_star_catalog.py bsc5.gz app/src/main/assets/sky/stars.tsv

Source: Bright Star Catalogue, 5th Revised Ed. (Hoffleit D., Warren Jr W.H., 1991), catalogue V/50
at the Centre de Données astronomiques de Strasbourg. Positions are J2000.

⚠️ **The byte offsets below are from the catalogue's own ReadMe and are not negotiable.** It is a
fixed-width format with no delimiters, so an offset that is wrong by one produces numbers that parse
perfectly and describe the wrong sky. They were read from
https://cdsarc.cds.unistra.fr/ftp/V/50/ReadMe rather than recalled, and the check at the bottom
re-derives Sirius from the output to prove the whole chain.
"""
import sys, gzip, io

# Bytes 5-14 of each record hold the Bayer/Flamsteed designation in four fixed sub-fields.
GREEK = {
    "Alp": "α", "Bet": "β", "Gam": "γ", "Del": "δ", "Eps": "ε",
    "Zet": "ζ", "Eta": "η", "The": "θ", "Iot": "ι", "Kap": "κ",
    "Lam": "λ", "Mu": "μ", "Nu": "ν", "Xi": "ξ", "Omi": "ο",
    "Pi": "π", "Rho": "ρ", "Sig": "σ", "Tau": "τ", "Ups": "υ",
    "Phi": "φ", "Chi": "χ", "Psi": "ψ", "Ome": "ω",
}
SUPER = {"1": "¹", "2": "²", "3": "³", "4": "⁴", "5": "⁵"}

# The faintest a healthy eye sees from a genuinely dark site. Going deeper adds stars nobody can
# check against the real sky; stopping brighter would leave the fainter half of every constellation
# missing at high zoom.
MAG_LIMIT = 6.5

# ⚠️ **The variable-star designation is deliberately NOT carried, and the reason is measured.** The
# catalogue puts one on 2,040 of the 8,404 stars inside this limit — 24% — but almost all of them
# are NSV cross-reference numbers on stars that vary by hundredths of a magnitude. Arcturus, Vega,
# Capella, Rigel and Procyon all carry one. A "variable" flag derived from this field would mark the
# five most familiar stars in the sky as variable, which is true in a way nobody means and worse
# than saying nothing.
#
# ⚠️ The honest consequence, stated rather than hidden: for a star that genuinely does swing, the
# magnitude here is the catalogue's figure and may be its MAXIMUM. Across the whole naked-eye sky
# that matters for exactly one entry — HR 5958, the recurrent nova T Coronae Borealis, listed at
# V = 2.0 (its outburst) while it normally sits near tenth magnitude. It has no Bayer or Flamsteed
# name, so it plots as an unlabelled second-magnitude dot in Corona Borealis that is not usually
# there. One star out of 8,404, and this note is where it is written down.


def parse(path):
    opener = gzip.open if str(path).endswith(".gz") else open
    out = []
    with opener(path, "rt", encoding="latin-1") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if len(line) < 107:
                continue
            rah, ram, ras = line[75:77].strip(), line[77:79].strip(), line[79:83].strip()
            sign, ded, dem, des = line[83], line[84:86].strip(), line[86:88].strip(), line[88:90].strip()
            vmag = line[102:107].strip()
            if not (rah and ram and ras and ded and dem and vmag):
                continue          # the fourteen retained non-stars, and a few records with no position
            try:
                ra = (int(rah) + int(ram) / 60.0 + float(ras) / 3600.0) * 15.0
                dec = int(ded) + int(dem) / 60.0 + int(des) / 3600.0
                if sign == "-":
                    dec = -dec
                mag = float(vmag)
            except ValueError:
                continue
            if mag > MAG_LIMIT:
                continue
            name = line[4:14]
            flam = name[0:3].strip()
            bayer3 = name[3:6].strip()
            sup = name[6:7].strip()
            con = name[7:10].strip()
            bayer = GREEK.get(bayer3, "")
            if bayer and sup:
                bayer += SUPER.get(sup, sup)
            bv = line[109:114].strip()
            # ⚠️ Bytes 149-160, arcsec/yr, converted to MILLIarcsec/yr — the unit the deep Gaia
            # catalogue is stored in, so the app has one unit for proper motion rather than two.
            # The ReadMe's own note settles the convention: "the proper motion in RA is the
            # projected motion (cos(DE).d(RA)/dt)", which is Gaia's mu-alpha-star. Getting that
            # wrong is invisible at the equator and grows without limit toward the poles.
            pm_ra = _mas(line[148:154])
            pm_dec = _mas(line[154:160])
            out.append((ra, dec, mag, bv, bayer, flam, con, pm_ra, pm_dec))
    return out


def _mas(field):
    """Arcsec/yr as written, in mas/yr; 0 where the catalogue records none."""
    t = field.strip()
    if not t:
        return 0.0
    try:
        return float(t) * 1000.0
    except ValueError:
        return 0.0


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 1
    stars = parse(sys.argv[1])
    stars.sort(key=lambda s: s[2])          # brightest first, so a truncated read is still useful
    lines = ["# ra\tdec\tmag\tbv\tbayer\tflamsteed\tconstellation\tpmra_mas\tpmdec_mas",
             "# Bright Star Catalogue V/50 (Hoffleit & Warren 1991), CDS Strasbourg. J2000.",
             "# pmra_mas is the PROJECTED motion cos(dec).d(ra)/dt, as the catalogue states it."]
    for ra, dec, mag, bv, bayer, flam, con, pm_ra, pm_dec in stars:
        lines.append(
            f"{ra:.4f}\t{dec:.4f}\t{mag:.2f}\t{bv}\t{bayer}\t{flam}\t{con}"
            f"\t{pm_ra:.1f}\t{pm_dec:.1f}"
        )
    body = "\n".join(lines) + "\n"
    with open(sys.argv[2], "w", encoding="utf-8") as fh:
        fh.write(body)

    # Prove the chain rather than assume it: Sirius is HR 2491, alpha Canis Majoris, V = -1.46, and
    # sits at 06h45m09s -16d42m58s in J2000. If an offset were out by one this would not match.
    ra, dec, mag, bv, bayer, flam, con, pm_ra, pm_dec = stars[0]
    assert abs(ra - 101.2871) < 0.001, ra
    assert abs(dec - (-16.7161)) < 0.001, dec
    assert abs(mag - (-1.46)) < 0.001, mag
    assert bayer == "α" and con == "CMa", (bayer, con)
    # ⚠️ And its proper motion, which is among the fastest of any bright star and therefore the one
    # entry where a wrong offset would be obvious. The record reads "-0.553-1.205" with the parallax
    # "+.375" immediately after, so the two fields are cleanly bounded and these are the catalogue's
    # OWN numbers rather than the modern ones. My first version of this assertion said -546/-1223,
    # which are the HIPPARCOS values, and it was wrong here: the BSC is FK5 from 1991 and differs by
    # about 7 and 18 mas/yr. Over the 26 years the app carries them that is a fifth and a half of an
    # arcsecond -- immaterial to a chart, and material to an assertion written from memory.
    assert abs(pm_ra - (-553.0)) < 1.0, pm_ra
    assert abs(pm_dec - (-1205.0)) < 1.0, pm_dec
    moving = sum(1 for s in stars if s[7] or s[8])
    named = sum(1 for s in stars if s[4] or s[5])
    print(f"{len(stars)} stars to magnitude {MAG_LIMIT}, {named} with a designation, "
          f"{moving} with a measured proper motion, {len(body) / 1024:.1f} kB")
    print(f"brightest: {bayer} {con} at V={mag}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
