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
     *  DeviceGateScreen, NavScreen, NewsScreen). */
    val Close: ImageVector by lazy {
        ImageVector.Builder(name = "LcarsClose", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = W, viewportHeight = H)
            .apply {
                path(fill = BLACK) {
                    moveTo(4f, 6f); lineTo(6f, 4f); lineTo(20f, 18f); lineTo(18f, 20f); close()
                    moveTo(20f, 6f); lineTo(18f, 4f); lineTo(4f, 18f); lineTo(6f, 20f); close()
                }
            }.build()
    }
}
