package dev.mascwa.pulse.core.telemetry

/**
 * What to call a star, and what constellation it is in.
 *
 * The bundled catalogue carries a Bayer letter, a Flamsteed number and a three-letter constellation
 * abbreviation, because that is what the Bright Star Catalogue records. None of those is what a
 * person says out loud. This turns them into "Sirius", "α Canis Majoris" or "61 Cygni", in that
 * order of preference.
 *
 * ⚠️ **Both tables are facts, not content.** The 88 constellations and their abbreviations are the
 * IAU's, fixed in 1922 and unchanged since; the proper names are the IAU Working Group on Star Names'
 * approved list. Neither is anybody's creative work, and the catalogue they key into is credited
 * where it is bundled.
 *
 * ⚠️ **`StarCatalogAssetTest` walks the real asset and requires every abbreviation in it to be in
 * [CONSTELLATIONS], and every key in [PROPER] to match a star that is actually there.** A missing
 * constellation would render as a bare "CMa" on a tap, and a mistyped proper-name key would simply
 * never fire — both silent, which is why the check is mechanical rather than a read-through.
 *
 * ⚠️ **That check earned itself immediately: eight of the first eighty names were keyed to nothing.**
 * Seven were multiple stars the catalogue records as separate components — Acrux is α¹ Cru, not
 * "α Cru"; Albireo is β¹ Cyg; Regor is γ² Vel; Acrab β¹ Sco; Rasalgethi α¹ Her; Zubenelgenubi α² Lib;
 * Algieba γ¹ Leo — and each name goes to the BRIGHTER component, confirmed against the magnitudes in
 * the asset rather than assumed. The eighth was a plain content error of mine: Alrescha is α Piscium
 * and I had it on δ Cephei, which the mechanical check could never have caught because δ Cep is a
 * perfectly real star. Reading the table is still worth doing.
 */
object StarNames {

    /** IAU three-letter abbreviation to the name people use. All 88. */
    val CONSTELLATIONS: Map<String, String> = mapOf(
        "And" to "Andromeda", "Ant" to "Antlia", "Aps" to "Apus", "Aqr" to "Aquarius",
        "Aql" to "Aquila", "Ara" to "Ara", "Ari" to "Aries", "Aur" to "Auriga",
        "Boo" to "Boötes", "Cae" to "Caelum", "Cam" to "Camelopardalis", "Cnc" to "Cancer",
        "CVn" to "Canes Venatici", "CMa" to "Canis Major", "CMi" to "Canis Minor",
        "Cap" to "Capricornus", "Car" to "Carina", "Cas" to "Cassiopeia", "Cen" to "Centaurus",
        "Cep" to "Cepheus", "Cet" to "Cetus", "Cha" to "Chamaeleon", "Cir" to "Circinus",
        "Col" to "Columba", "Com" to "Coma Berenices", "CrA" to "Corona Australis",
        "CrB" to "Corona Borealis", "Crv" to "Corvus", "Crt" to "Crater", "Cru" to "Crux",
        "Cyg" to "Cygnus", "Del" to "Delphinus", "Dor" to "Dorado", "Dra" to "Draco",
        "Equ" to "Equuleus", "Eri" to "Eridanus", "For" to "Fornax", "Gem" to "Gemini",
        "Gru" to "Grus", "Her" to "Hercules", "Hor" to "Horologium", "Hya" to "Hydra",
        "Hyi" to "Hydrus", "Ind" to "Indus", "Lac" to "Lacerta", "Leo" to "Leo",
        "LMi" to "Leo Minor", "Lep" to "Lepus", "Lib" to "Libra", "Lup" to "Lupus",
        "Lyn" to "Lynx", "Lyr" to "Lyra", "Men" to "Mensa", "Mic" to "Microscopium",
        "Mon" to "Monoceros", "Mus" to "Musca", "Nor" to "Norma", "Oct" to "Octans",
        "Oph" to "Ophiuchus", "Ori" to "Orion", "Pav" to "Pavo", "Peg" to "Pegasus",
        "Per" to "Perseus", "Phe" to "Phoenix", "Pic" to "Pictor", "Psc" to "Pisces",
        "PsA" to "Piscis Austrinus", "Pup" to "Puppis", "Pyx" to "Pyxis", "Ret" to "Reticulum",
        "Sge" to "Sagitta", "Sgr" to "Sagittarius", "Sco" to "Scorpius", "Scl" to "Sculptor",
        "Sct" to "Scutum", "Ser" to "Serpens", "Sex" to "Sextans", "Tau" to "Taurus",
        "Tel" to "Telescopium", "Tri" to "Triangulum", "TrA" to "Triangulum Australe",
        "Tuc" to "Tucana", "UMa" to "Ursa Major", "UMi" to "Ursa Minor", "Vel" to "Vela",
        "Vir" to "Virgo", "Vol" to "Volans", "Vul" to "Vulpecula",
    )

    /**
     * The genitive form, which is what a Bayer designation actually uses.
     *
     * ⚠️ "α Canis Major" is wrong and "α Canis Majoris" is right — the Bayer letter takes the
     * genitive, always has, and getting it wrong is the tell of an app that pasted a table together.
     * Only the ones that differ from the nominative are listed; [genitive] falls back to the name
     * itself, which covers Ara, Leo, Lynx and the rest that happen to be the same either way.
     */
    private val GENITIVE: Map<String, String> = mapOf(
        "And" to "Andromedae", "Ant" to "Antliae", "Aps" to "Apodis", "Aqr" to "Aquarii",
        "Aql" to "Aquilae", "Ara" to "Arae", "Ari" to "Arietis", "Aur" to "Aurigae",
        "Boo" to "Boötis", "Cae" to "Caeli", "Cam" to "Camelopardalis", "Cnc" to "Cancri",
        "CVn" to "Canum Venaticorum", "CMa" to "Canis Majoris", "CMi" to "Canis Minoris",
        "Cap" to "Capricorni", "Car" to "Carinae", "Cas" to "Cassiopeiae", "Cen" to "Centauri",
        "Cep" to "Cephei", "Cet" to "Ceti", "Cha" to "Chamaeleontis", "Cir" to "Circini",
        "Col" to "Columbae", "Com" to "Comae Berenices", "CrA" to "Coronae Australis",
        "CrB" to "Coronae Borealis", "Crv" to "Corvi", "Crt" to "Crateris", "Cru" to "Crucis",
        "Cyg" to "Cygni", "Del" to "Delphini", "Dor" to "Doradus", "Dra" to "Draconis",
        "Equ" to "Equulei", "Eri" to "Eridani", "For" to "Fornacis", "Gem" to "Geminorum",
        "Gru" to "Gruis", "Her" to "Herculis", "Hor" to "Horologii", "Hya" to "Hydrae",
        "Hyi" to "Hydri", "Ind" to "Indi", "Lac" to "Lacertae", "Leo" to "Leonis",
        "LMi" to "Leonis Minoris", "Lep" to "Leporis", "Lib" to "Librae", "Lup" to "Lupi",
        "Lyn" to "Lyncis", "Lyr" to "Lyrae", "Men" to "Mensae", "Mic" to "Microscopii",
        "Mon" to "Monocerotis", "Mus" to "Muscae", "Nor" to "Normae", "Oct" to "Octantis",
        "Oph" to "Ophiuchi", "Ori" to "Orionis", "Pav" to "Pavonis", "Peg" to "Pegasi",
        "Per" to "Persei", "Phe" to "Phoenicis", "Pic" to "Pictoris", "Psc" to "Piscium",
        "PsA" to "Piscis Austrini", "Pup" to "Puppis", "Pyx" to "Pyxidis", "Ret" to "Reticuli",
        "Sge" to "Sagittae", "Sgr" to "Sagittarii", "Sco" to "Scorpii", "Scl" to "Sculptoris",
        "Sct" to "Scuti", "Ser" to "Serpentis", "Sex" to "Sextantis", "Tau" to "Tauri",
        "Tel" to "Telescopii", "Tri" to "Trianguli", "TrA" to "Trianguli Australis",
        "Tuc" to "Tucanae", "UMa" to "Ursae Majoris", "UMi" to "Ursae Minoris", "Vel" to "Velorum",
        "Vir" to "Virginis", "Vol" to "Volantis", "Vul" to "Vulpeculae",
    )

    /**
     * Proper names, keyed by the Bayer designation as it appears in the catalogue.
     *
     * ⚠️ Deliberately short. Every star brighter than about second magnitude, plus the handful that
     * are famous for something other than brightness — Polaris for pointing north, Algol and Mira
     * for varying, Mizar for the naked-eye double, Albireo for the colour. Stretching to hundreds of
     * names would mean listing names nobody says, and the designation is a perfectly good answer for
     * a star that has not earned one.
     */
    private val PROPER: Map<String, String> = mapOf(
        "α CMa" to "Sirius", "α Car" to "Canopus", "α Boo" to "Arcturus",
        "α¹ Cen" to "Rigil Kentaurus", "α Lyr" to "Vega", "α Aur" to "Capella",
        "β Ori" to "Rigel", "α CMi" to "Procyon", "α Eri" to "Achernar",
        "α Ori" to "Betelgeuse", "β Cen" to "Hadar", "α Aql" to "Altair",
        "α Tau" to "Aldebaran", "α Vir" to "Spica", "α Sco" to "Antares",
        "β Gem" to "Pollux", "α PsA" to "Fomalhaut", "β Cru" to "Mimosa",
        "α Cyg" to "Deneb", "α¹ Cru" to "Acrux", "α Leo" to "Regulus",
        "ε CMa" to "Adhara", "α Gem" to "Castor", "λ Sco" to "Shaula",
        "γ Cru" to "Gacrux", "γ Ori" to "Bellatrix", "β Tau" to "Elnath",
        "β Car" to "Miaplacidus", "ε Ori" to "Alnilam", "α Gru" to "Alnair",
        "ζ Ori" to "Alnitak", "γ² Vel" to "Regor", "ε UMa" to "Alioth",
        "α Per" to "Mirfak", "α UMa" to "Dubhe", "δ CMa" to "Wezen",
        "η UMa" to "Alkaid", "ε Sgr" to "Kaus Australis", "θ Sco" to "Sargas",
        "β Aur" to "Menkalinan", "α Pav" to "Peacock", "δ Vel" to "Alsephina",
        "α UMi" to "Polaris", "α Hya" to "Alphard", "α And" to "Alpheratz",
        "γ Gem" to "Alhena", "β CMa" to "Mirzam", "α Ari" to "Hamal",
        "κ Ori" to "Saiph", "β Cet" to "Diphda", "σ Sgr" to "Nunki",
        "θ Cen" to "Menkent", "α Cas" to "Schedar", "β And" to "Mirach",
        "β UMi" to "Kochab", "α Oph" to "Rasalhague", "β Per" to "Algol",
        "α Cep" to "Alderamin", "ζ UMa" to "Mizar", "β¹ Cyg" to "Albireo",
        "ο Cet" to "Mira", "α Psc" to "Alrescha", "ε Peg" to "Enif",
        "α Peg" to "Markab", "β Peg" to "Scheat", "γ Peg" to "Algenib",
        "α Cet" to "Menkar", "η Tau" to "Alcyone", "β¹ Sco" to "Acrab",
        "δ Sco" to "Dschubba", "γ Dra" to "Eltanin", "α¹ Her" to "Rasalgethi",
        "α² Lib" to "Zubenelgenubi", "β Leo" to "Denebola", "γ¹ Leo" to "Algieba",
        "α Aqr" to "Sadalmelik", "β Aqr" to "Sadalsuud", "α Del" to "Sualocin",
    )

    /** The constellation's name, or the abbreviation itself when it is not one we know. */
    fun constellation(abbreviation: String): String =
        CONSTELLATIONS[abbreviation] ?: abbreviation

    /** The genitive, for a Bayer designation. Falls back to the nominative and then the abbreviation. */
    fun genitive(abbreviation: String): String =
        GENITIVE[abbreviation] ?: CONSTELLATIONS[abbreviation] ?: abbreviation

    /**
     * What to put on a tap, best available first.
     *
     * ⚠️ Returns null rather than an empty string when there is nothing to say. Most of the
     * catalogue past fourth magnitude has no designation at all, and a blank label drawn beside a
     * dot reads as a rendering fault where no label reads as an unremarkable star.
     */
    fun label(bayer: String, flamsteed: String, constellation: String): String? = when {
        bayer.isNotBlank() -> PROPER["$bayer $constellation"] ?: "$bayer ${genitive(constellation)}"
        flamsteed.isNotBlank() -> "$flamsteed ${genitive(constellation)}"
        else -> null
    }

    /** The short form for a crowded chart: "α CMa", "61 Cyg", or nothing. */
    fun shortLabel(bayer: String, flamsteed: String, constellation: String): String? = when {
        bayer.isNotBlank() -> PROPER["$bayer $constellation"] ?: "$bayer $constellation"
        flamsteed.isNotBlank() -> "$flamsteed $constellation"
        else -> null
    }

    /**
     * Only for the test that walks the real asset — every key here must name a star the catalogue
     * actually carries, or a proper name is shipped that nothing can ever display.
     *
     * ⚠️ **Public, and `internal` was wrong.** `internal` is MODULE-scoped, and the one caller is
     * `StarCatalogAssetTest` in `:app` — so it could not see this at all and CI refused to compile
     * the test source. The same trap is already recorded against `Stardate.clockOf`. A member whose
     * entire stated purpose is a guard in another module cannot be `internal`.
     */
    fun properKeys(): Set<String> = PROPER.keys

    /**
     * What colour a star actually looks, from its B-V index, as packed ARGB.
     *
     * ⚠️ **Real, not decorative.** B-V is a measurement of how much brighter a star is in one filter
     * than another, and it maps almost directly onto what the eye sees: Rigel at −0.03 is blue-white,
     * Betelgeuse at +1.85 is visibly orange. A chart drawn in one colour looks wrong to anybody who
     * has looked up.
     *
     * ⚠️ **Null in, null out, and that is the contract rather than an oversight.** About three per
     * cent of the catalogue has no measured colour, and the honest answer there is the drawing
     * surface's own ink rather than a guess — which is a palette question, and palettes belong to
     * the platform. Returning a made-up white here would put a claim about a measurement into a
     * value nobody could tell from a real one.
     *
     * ⚠️ It returns an **Int**, not a Compose `Color`, for the reason `Oracle.urgencyArgb` does: this
     * module carries no UI dependency, and two consoles now draw the same catalogue. One table, so
     * the phone and the companion cannot end up disagreeing about what colour Betelgeuse is — the
     * duplicated-definition drift this project has corrected six times.
     */
    fun colourArgb(bv: Double?): Int? = when {
        bv == null -> null
        bv < BV_EDGES[0] -> BAND_ARGB[0]
        bv < BV_EDGES[1] -> BAND_ARGB[1]
        bv < BV_EDGES[2] -> BAND_ARGB[2]
        bv < BV_EDGES[3] -> BAND_ARGB[3]
        bv < BV_EDGES[4] -> BAND_ARGB[4]
        else -> BAND_ARGB[5]
    }

    /**
     * The same six colours, from Gaia's `bp_rp` instead of B−V.
     *
     * ⚠️ **The two scales do NOT share a zero point, and assuming they roughly do is the mistake
     * this function exists to avoid.** A star at B−V = 0.00 — an A0, white — measures **+0.23** in
     * `bp_rp`. Reaching for the familiar `bp_rp ≈ 1.2 × (B−V)` would therefore put the first edge at
     * zero and paint every white star blue, across sixteen million of them, in a way nothing would
     * flag.
     *
     * ⚠️ **The edges below were MEASURED, not derived.** Each B−V edge in [BV_EDGES] corresponds to
     * a main-sequence effective temperature (A0 ≈ 9700 K, F0 ≈ 7300, G0 ≈ 5950, K0 ≈ 5250,
     * M0 ≈ 4000); Gaia DR3 was asked for the mean `bp_rp` of every star brighter than magnitude 11
     * within ±150 K of each, over 151,056 stars. The relation is empirical and it is not linear,
     * which is exactly why it had to be measured rather than recalled.
     *
     * Two colour scales, six colours, one vocabulary: each source keeps the measurement it actually
     * made, and neither is converted into the other behind the reader's back.
     */
    fun colourArgbFromBpRp(bpRp: Double?): Int? = when {
        bpRp == null -> null
        bpRp < BP_RP_EDGES[0] -> BAND_ARGB[0]
        bpRp < BP_RP_EDGES[1] -> BAND_ARGB[1]
        bpRp < BP_RP_EDGES[2] -> BAND_ARGB[2]
        bpRp < BP_RP_EDGES[3] -> BAND_ARGB[3]
        bpRp < BP_RP_EDGES[4] -> BAND_ARGB[4]
        else -> BAND_ARGB[5]
    }

    /** Blue-white through to orange: what a star of each temperature actually looks like. */
    private val BAND_ARGB = intArrayOf(
        0xFFBBD2FF.toInt(),
        0xFFE4ECFF.toInt(),
        0xFFFFF6E8.toInt(),
        0xFFFFE7BE.toInt(),
        0xFFFFCD96.toInt(),
        0xFFFFB27A.toInt(),
    )

    /** The B−V band edges, unchanged since this table was a private function in one screen. */
    private val BV_EDGES = doubleArrayOf(0.0, 0.3, 0.6, 1.0, 1.5)

    /** The same five boundaries in Gaia's `bp_rp`, measured — see [colourArgbFromBpRp]. */
    private val BP_RP_EDGES = doubleArrayOf(0.228, 0.490, 0.780, 1.207, 2.159)
}
