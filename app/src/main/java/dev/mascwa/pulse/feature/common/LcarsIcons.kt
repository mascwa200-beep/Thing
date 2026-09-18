package dev.mascwa.pulse.feature.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Original, hand-drawn glyphs replacing stock Material icons — angular/blocky silhouettes (straight lines
 * and simple polygons only, no curves) matching this app's own established LCARS vocabulary
 * ([LcarsGeometry.kt]'s swept blocks, [Nightwire.kt]/`CyberUi.kt`'s chamfers) rather than Material's rounded,
 * softly-curved icon language. Each is a drop-in [ImageVector] — pass it wherever `Icons.Filled.X` was
 * passed before (`Icon(LcarsIcons.ArrowBack, ...)`); [Icon]'s own `tint` recolors the whole glyph at call
 * sites exactly as it does for a stock icon, so every path below is built with a plain black fill and never
 * needs its own color.
 *
 * Wave 1: the 7 bottom-nav destination icons plus `ArrowBack` (32 call sites — by a wide margin the single
 * most-used icon in the app) — all wired to their call sites. Wave 2 (this addition): the next-highest-
 * frequency stock icons — `Refresh` (7 sites), `Delete` (5), `PlayArrow` (4), `Search`/`Close` (3 each).
 * Wave 2's call-site wiring and further icon waves are follow-up work — additive only until wired.
 *
 * Wave 3: the three icons inside the shared loading/error/empty/stale states — `Warning`, `Inbox` and
 * `CloudOff` — and these are wired. They are worth more than their count suggests: those four
 * composables render on sixteen screens, so a rounded Material triangle was the most-repeated foreign
 * shape left in the interface, and it appeared precisely when something had gone wrong.
 */
object LcarsIcons {

    private const val W = 24f
    private const val H = 24f
    private val BLACK = SolidColor(Color.Black)

    /** A bold arrow silhouette (wide triangular head + rectangular shaft) — replaces
     *  `Icons.AutoMirrored.Filled.ArrowBack`, this app's single most-used icon (32 call sites, almost every
     *  screen's back button). */
    val ArrowBack: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsArrowBack", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK) {
                    moveTo(3f, 12f)
                    lineTo(11f, 4f)
                    lineTo(11f, 9f)
                    lineTo(21f, 9f)
                    lineTo(21f, 15f)
                    lineTo(11f, 15f)
                    lineTo(11f, 20f)
                    close()
                }
            }.build()
    }

    /** A simplified house pentagon — replaces `Icons.Filled.Home`/`Icons.Outlined.Home`, the PULSE
     *  (home tab) bottom-nav icon. */
    val Home: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsHome", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK) {
                    moveTo(12f, 3f)
                    lineTo(21f, 10f)
                    lineTo(18f, 10f)
                    lineTo(18f, 20f)
                    lineTo(6f, 20f)
                    lineTo(6f, 10f)
                    lineTo(3f, 10f)
                    close()
                }
            }.build()
    }

    /** A framed "document" ring (evenodd hole — outer silhouette minus an inset rectangle) with a folded
     *  top-right corner — replaces `Icons.Filled.Article`, the NEWS bottom-nav icon. */
    val Article: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsArticle", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK, pathFillType = PathFillType.EvenOdd) {
                    moveTo(5f, 2f)
                    lineTo(14f, 2f)
                    lineTo(19f, 7f)
                    lineTo(19f, 22f)
                    lineTo(5f, 22f)
                    close()
                    moveTo(7f, 5f)
                    lineTo(13f, 5f)
                    lineTo(17f, 8.5f)
                    lineTo(17f, 20f)
                    lineTo(7f, 20f)
                    close()
                }
            }.build()
    }

    /** Three ascending solid bars — replaces `Icons.AutoMirrored.Filled.ShowChart`, the MARKETS bottom-nav
     *  icon. Reuses this app's own established "ascending block bars" idiom (see [ImpactBar] in
     *  `feature/news/NewsComponents.kt`, [LcarsFillRow]). */
    val ShowChart: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsShowChart", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK) {
                    moveTo(3f, 14f); lineTo(8f, 14f); lineTo(8f, 21f); lineTo(3f, 21f); close()
                    moveTo(10f, 9f); lineTo(15f, 9f); lineTo(15f, 21f); lineTo(10f, 21f); close()
                    moveTo(17f, 3f); lineTo(22f, 3f); lineTo(22f, 21f); lineTo(17f, 21f); close()
                }
            }.build()
    }

    /** An octagonal sun disc with four angular ray spikes (N/S/E/W) — replaces `Icons.Filled.WbSunny`, the
     *  WX (weather) bottom-nav icon. Angular rays instead of Material's thin curved-cap lines. */
    val WbSunny: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsWbSunny", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK) {
                    // octagonal disc
                    moveTo(9f, 8f); lineTo(15f, 8f); lineTo(18f, 11f); lineTo(18f, 15f)
                    lineTo(15f, 18f); lineTo(9f, 18f); lineTo(6f, 15f); lineTo(6f, 11f); close()
                    // top ray
                    moveTo(10.5f, 2f); lineTo(13.5f, 2f); lineTo(12f, 6.5f); close()
                    // bottom ray
                    moveTo(10.5f, 22f); lineTo(13.5f, 22f); lineTo(12f, 17.5f); close()
                    // left ray
                    moveTo(2f, 10.5f); lineTo(2f, 13.5f); lineTo(6.5f, 12f); close()
                    // right ray
                    moveTo(22f, 10.5f); lineTo(22f, 13.5f); lineTo(17.5f, 12f); close()
                }
            }.build()
    }

    /** A 4-pointed angular sparkle/star — replaces `Icons.Filled.AutoAwesome`, the COMPUTER (J.A.R.V.I.S.)
     *  bottom-nav icon. */
    val AutoAwesome: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsAutoAwesome", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK) {
                    moveTo(12f, 2f)
                    lineTo(14.5f, 9.5f)
                    lineTo(22f, 12f)
                    lineTo(14.5f, 14.5f)
                    lineTo(12f, 22f)
                    lineTo(9.5f, 14.5f)
                    lineTo(2f, 12f)
                    lineTo(9.5f, 9.5f)
                    close()
                }
            }.build()
    }

    /** A 2×2 grid of solid blocks — replaces `Icons.Filled.GridView`, the TOOLS bottom-nav icon. */
    val GridView: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsGridView", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK) {
                    moveTo(3f, 3f); lineTo(10f, 3f); lineTo(10f, 10f); lineTo(3f, 10f); close()
                    moveTo(14f, 3f); lineTo(21f, 3f); lineTo(21f, 10f); lineTo(14f, 10f); close()
                    moveTo(3f, 14f); lineTo(10f, 14f); lineTo(10f, 21f); lineTo(3f, 21f); close()
                    moveTo(14f, 14f); lineTo(21f, 14f); lineTo(21f, 21f); lineTo(14f, 21f); close()
                }
            }.build()
    }

    /** A concentric-octagon "bolt/nut" ring (evenodd hole) — replaces `Icons.Filled.Settings`, the SYS
     *  bottom-nav icon. An angular control/hardware abstraction instead of Material's toothed gear. */
    val Settings: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsSettings", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK, pathFillType = PathFillType.EvenOdd) {
                    moveTo(8f, 2f); lineTo(16f, 2f); lineTo(22f, 8f); lineTo(22f, 16f)
                    lineTo(16f, 22f); lineTo(8f, 22f); lineTo(2f, 16f); lineTo(2f, 8f); close()
                    moveTo(10.5f, 6f); lineTo(13.5f, 6f); lineTo(18f, 10.5f); lineTo(18f, 13.5f)
                    lineTo(13.5f, 18f); lineTo(10.5f, 18f); lineTo(6f, 13.5f); lineTo(6f, 10.5f); close()
                }
            }.build()
    }

    /** Two opposing arrow-shafts (same silhouette as [ArrowBack], mirrored/compressed into a top and bottom
     *  band) suggesting rotational flow — replaces `Icons.Filled.Refresh` (7 call sites: PipBoyScreen,
     *  RadarScreen, QuestsScreen, ObjectivesScreen, MarketsScreen, SettingsScreen, WeatherScreen). An angular
     *  two-arrow cycle instead of Material's curved circular-arrow glyph. */
    val Refresh: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsRefresh", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK) {
                    // top band, arrow pointing right
                    moveTo(21f, 7f); lineTo(13f, 3f); lineTo(13f, 5.5f); lineTo(3f, 5.5f)
                    lineTo(3f, 8.5f); lineTo(13f, 8.5f); lineTo(13f, 11f); close()
                    // bottom band, arrow pointing left
                    moveTo(3f, 17f); lineTo(11f, 13f); lineTo(11f, 15.5f); lineTo(21f, 15.5f)
                    lineTo(21f, 18.5f); lineTo(11f, 18.5f); lineTo(11f, 21f); close()
                }
            }.build()
    }

    /** A lidded bin silhouette (lid + handle + tapered body) — replaces `Icons.Filled.Delete` (5 call sites,
     *  all in SettingsScreen). */
    val Delete: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsDelete", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK) {
                    moveTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 6f); lineTo(9f, 6f); close()
                    moveTo(4f, 6f); lineTo(20f, 6f); lineTo(20f, 8f); lineTo(4f, 8f); close()
                    moveTo(6f, 8f); lineTo(18f, 8f); lineTo(17f, 21f); lineTo(7f, 21f); close()
                }
            }.build()
    }

    /** A plain right-pointing triangle — replaces `Icons.Filled.PlayArrow` (4 call sites, all in
     *  SpotifyBody). Already a straight-line shape in Material's own glyph, so a direct match. */
    val PlayArrow: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsPlayArrow", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK) {
                    moveTo(6f, 4f); lineTo(20f, 12f); lineTo(6f, 20f); close()
                }
            }.build()
    }

    /** An octagonal scan-reticle ("lens", evenodd hole, mirrors [Settings]'s ring technique at smaller
     *  scale) with a diagonal handle bar — replaces `Icons.Filled.Search` (3 call sites: HomeScreen,
     *  NewsScreen, WeatherScreen). An angular sci-fi scan reticle instead of Material's round magnifier. */
    val Search: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsSearch", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK, pathFillType = PathFillType.EvenOdd) {
                    moveTo(7f, 3f); lineTo(11f, 3f); lineTo(15f, 7f); lineTo(15f, 11f)
                    lineTo(11f, 15f); lineTo(7f, 15f); lineTo(3f, 11f); lineTo(3f, 7f); close()
                    moveTo(7.5f, 5f); lineTo(10.5f, 5f); lineTo(13f, 7.5f); lineTo(13f, 10.5f)
                    lineTo(10.5f, 13f); lineTo(7.5f, 13f); lineTo(5f, 10.5f); lineTo(5f, 7.5f); close()
                }
                path(fill = BLACK) {
                    moveTo(14f, 15f); lineTo(15f, 14f); lineTo(22f, 21f); lineTo(21f, 22f); close()
                }
            }.build()
    }

    /** Two crossing diagonal bars forming an X — replaces `Icons.Filled.Close` (3 call sites:
     *  DeviceNotice, NavScreen, NewsScreen). */
    val Close: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsClose", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK) {
                    moveTo(4f, 6f); lineTo(6f, 4f); lineTo(20f, 18f); lineTo(18f, 20f); close()
                    moveTo(20f, 6f); lineTo(18f, 4f); lineTo(4f, 18f); lineTo(6f, 20f); close()
                }
            }.build()
    }

    // ---- Wave 3: the MAPS & SKY set --------------------------------------------------------
    // The four sky/map screens had no glyphs at all and fell back to emoji (🌙, ☿♀♂♃♄, ⚠) in
    // running text, which never picks up an `Icon` tint and renders at the mercy of the system
    // emoji font. These are the same angular vocabulary as the waves above.

    /** A body with two crossed orbit rings — the satellite/orbital marker. */
    val Satellite: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsSatellite", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK) { // body
                    moveTo(10f, 10f); lineTo(14f, 10f); lineTo(14f, 14f); lineTo(10f, 14f); close()
                }
                path(fill = BLACK) { // solar wings
                    moveTo(2f, 11f); lineTo(9f, 11f); lineTo(9f, 13f); lineTo(2f, 13f); close()
                    moveTo(15f, 11f); lineTo(22f, 11f); lineTo(22f, 13f); lineTo(15f, 13f); close()
                }
                path(fill = BLACK) { // antenna
                    moveTo(11.2f, 3f); lineTo(12.8f, 3f); lineTo(12.8f, 9f); lineTo(11.2f, 9f); close()
                    moveTo(8f, 2f); lineTo(16f, 2f); lineTo(16f, 3.6f); lineTo(8f, 3.6f); close()
                }
            }.build()
    }

    /** A four-point angular star — the "visible tonight" / sky-chart marker. */
    val Star: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsStar", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK) {
                    moveTo(12f, 1f); lineTo(14.5f, 9.5f); lineTo(23f, 12f); lineTo(14.5f, 14.5f)
                    lineTo(12f, 23f); lineTo(9.5f, 14.5f); lineTo(1f, 12f); lineTo(9.5f, 9.5f); close()
                }
            }.build()
    }

    /** A hard-edged crescent (octagon minus an offset octagon) — the Moon marker. */
    val Moon: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsMoon", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK, pathFillType = PathFillType.EvenOdd) {
                    moveTo(9f, 2f); lineTo(15f, 2f); lineTo(21f, 8f); lineTo(21f, 16f)
                    lineTo(15f, 22f); lineTo(9f, 22f); lineTo(3f, 16f); lineTo(3f, 8f); close()
                    moveTo(13f, 4f); lineTo(19f, 4f); lineTo(24f, 9f); lineTo(24f, 15f)
                    lineTo(19f, 20f); lineTo(13f, 20f); lineTo(8f, 15f); lineTo(8f, 9f); close()
                }
            }.build()
    }

    /** A disc with eight straight rays — the Sun / solar-activity marker. */
    val Sun: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsSun", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK) {
                    moveTo(9f, 9f); lineTo(15f, 9f); lineTo(15f, 15f); lineTo(9f, 15f); close()
                    moveTo(11f, 1f); lineTo(13f, 1f); lineTo(13f, 7f); lineTo(11f, 7f); close()
                    moveTo(11f, 17f); lineTo(13f, 17f); lineTo(13f, 23f); lineTo(11f, 23f); close()
                    moveTo(1f, 11f); lineTo(7f, 11f); lineTo(7f, 13f); lineTo(1f, 13f); close()
                    moveTo(17f, 11f); lineTo(23f, 11f); lineTo(23f, 13f); lineTo(17f, 13f); close()
                }
            }.build()
    }

    /** Concentric sweep arcs with a scan line — the radar/scope marker. */
    val Radar: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsRadar", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK, pathFillType = PathFillType.EvenOdd) {
                    moveTo(12f, 2f); lineTo(19f, 5f); lineTo(22f, 12f); lineTo(19f, 19f)
                    lineTo(12f, 22f); lineTo(5f, 19f); lineTo(2f, 12f); lineTo(5f, 5f); close()
                    moveTo(12f, 4f); lineTo(18f, 6f); lineTo(20f, 12f); lineTo(18f, 18f)
                    lineTo(12f, 20f); lineTo(6f, 18f); lineTo(4f, 12f); lineTo(6f, 6f); close()
                }
                path(fill = BLACK) { // sweep hand from centre to the upper right
                    moveTo(11.2f, 11.2f); lineTo(12.8f, 12.8f); lineTo(19f, 6.6f); lineTo(17.4f, 5f); close()
                }
                path(fill = BLACK) { // hub
                    moveTo(10.5f, 10.5f); lineTo(13.5f, 10.5f); lineTo(13.5f, 13.5f); lineTo(10.5f, 13.5f); close()
                }
            }.build()
    }

    /** Three stacked offset plates — the map "layers" control. */
    val Layers: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsLayers", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK) {
                    moveTo(12f, 2f); lineTo(22f, 7f); lineTo(12f, 12f); lineTo(2f, 7f); close()
                    moveTo(12f, 13.4f); lineTo(20.2f, 9.3f); lineTo(22f, 10.2f); lineTo(12f, 15.2f)
                    lineTo(2f, 10.2f); lineTo(3.8f, 9.3f); close()
                    moveTo(12f, 16.6f); lineTo(20.2f, 12.5f); lineTo(22f, 13.4f); lineTo(12f, 18.4f)
                    lineTo(2f, 13.4f); lineTo(3.8f, 12.5f); close()
                }
            }.build()
    }

    /**
     * A hard-edged warning triangle with a rectangular bar and a square dot.
     *
     * Replaces `Icons.Filled.WarningAmber`. This one reaches further than most: every error state in
     * the app draws it, on sixteen screens, so a rounded Material triangle was the most-repeated
     * foreign shape left in the interface.
     */
    val Warning: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsWarning", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK, pathFillType = PathFillType.EvenOdd) {
                    // Outer triangle, flat-topped apex so it reads at 16dp instead of vanishing.
                    moveTo(12f, 2.5f); lineTo(23f, 21.5f); lineTo(1f, 21.5f); close()
                    // The bar and the dot are punched out, so the glyph works on any fill.
                    moveTo(10.9f, 9f); lineTo(13.1f, 9f); lineTo(13.1f, 15f); lineTo(10.9f, 15f); close()
                    moveTo(10.9f, 16.6f); lineTo(13.1f, 16.6f); lineTo(13.1f, 19f); lineTo(10.9f, 19f); close()
                }
            }.build()
    }

    /**
     * An open tray: a squared-off box with a slot cut across it.
     *
     * Replaces `Icons.Filled.Inbox`, which every empty state draws.
     */
    val Inbox: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsInbox", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK, pathFillType = PathFillType.EvenOdd) {
                    moveTo(2f, 4f); lineTo(22f, 4f); lineTo(22f, 20f); lineTo(2f, 20f); close()
                    // The slot: a full-width band with the tray's mouth notched out of its middle.
                    moveTo(4.2f, 12.4f); lineTo(9f, 12.4f); lineTo(9f, 14.6f); lineTo(15f, 14.6f)
                    lineTo(15f, 12.4f); lineTo(19.8f, 12.4f); lineTo(19.8f, 17.8f); lineTo(4.2f, 17.8f); close()
                }
            }.build()
    }

    /**
     * A blocky cloud with a bar struck through it — the offline mark.
     *
     * Replaces `Icons.Filled.CloudOff` on the stale-data banner. Drawn as stacked rectangles rather
     * than the usual lobed silhouette, which is the whole point: this vocabulary has no curves.
     */
    val CloudOff: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsCloudOff", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK) {
                    moveTo(7f, 8f); lineTo(17f, 8f); lineTo(17f, 12f); lineTo(20f, 12f)
                    lineTo(20f, 17f); lineTo(4f, 17f); lineTo(4f, 12f); lineTo(7f, 12f); close()
                }
                // The strike, drawn as a parallelogram so it stays a straight-edged shape.
                path(fill = BLACK) {
                    moveTo(3.2f, 4.4f); lineTo(5.1f, 2.5f); lineTo(21.5f, 18.9f)
                    lineTo(19.6f, 20.8f); close()
                }
            }.build()
    }

    /**
     * A blocky operator silhouette — head block over a squared torso — for the HEALTH bottom-nav tab.
     *
     * ⚠️ A figure rather than a heart or a cross. A heart reads as "favourite" everywhere else in a phone
     * and a cross reads as an emergency, and this tab is neither: it is your body, what you eat and the
     * plan built from them. Straight lines only, like every other glyph here.
     */
    val Vitals: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsVitals", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                // Head.
                path(fill = BLACK) {
                    moveTo(9.5f, 2.5f)
                    lineTo(14.5f, 2.5f)
                    lineTo(14.5f, 8f)
                    lineTo(9.5f, 8f)
                    close()
                }
                // Shoulders down to the waist — a trapezoid, wider at the top.
                path(fill = BLACK) {
                    moveTo(5.5f, 10f)
                    lineTo(18.5f, 10f)
                    lineTo(16.5f, 21.5f)
                    lineTo(7.5f, 21.5f)
                    close()
                }
            }.build()
    }
}
