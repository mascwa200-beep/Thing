package dev.mascwa.pulse.data.maps

/**
 * Every tile service the NAV map can draw, in one place, each stated with its licence.
 *
 * All of these are keyless and free to use, and all of them are somebody's bandwidth. The
 * attribution strings are not decoration: OpenTopoMap and EOX both require them, and a map that
 * silently drops the credit is using the data outside its licence.
 *
 * Deliberately not in `core:telemetry` — there is no logic here to test, only the addresses and
 * the terms they come with.
 */
object MapLayerCatalog {

    /**
     * A basemap: what the world itself looks like.
     *
     * [tileUrl] null means the app's own vector style, which is loaded separately and always
     * present; the raster basemaps draw over the top of it when chosen.
     */
    enum class Basemap(
        val label: String,
        val blurb: String,
        val tileUrl: String?,
        val tileSize: Int,
        val attribution: String,
        val maxZoom: Float,
    ) {
        NIGHTWIRE(
            label = "LCARS",
            blurb = "The recoloured vector map",
            tileUrl = null,
            tileSize = 512,
            attribution = "© OpenStreetMap contributors · OpenFreeMap",
            maxZoom = 20f,
        ),
        SATELLITE(
            label = "ORBITAL",
            blurb = "Cloudless satellite imagery",
            // WMTS, so the path is {z}/{row}/{col} — row before column. Confirmed against the live
            // service rather than assumed: the two orderings both return a valid image, and only
            // one of them returns the right part of the world.
            tileUrl = "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2020_3857/default/g/{z}/{y}/{x}.jpg",
            tileSize = 256,
            attribution = "Sentinel-2 cloudless 2020 by EOX IT Services (CC BY 4.0)",
            // The mosaic itself stops here; asking for deeper tiles just returns nothing.
            maxZoom = 14f,
        ),
        TOPO(
            label = "TERRAIN",
            blurb = "Contours, paths and relief",
            tileUrl = "https://tile.opentopomap.org/{z}/{x}/{y}.png",
            tileSize = 256,
            attribution = "© OpenStreetMap contributors · SRTM · OpenTopoMap (CC-BY-SA)",
            maxZoom = 17f,
        ),
    }

    /** Elevation tiles in Terrarium encoding, which is what the hillshade layer reads. */
    const val TERRAIN_DEM_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
    const val TERRAIN_DEM_ENCODING = "terrarium"
    const val TERRAIN_DEM_MAX_ZOOM = 15f
    const val TERRAIN_DEM_ATTRIBUTION = "Terrain Tiles on AWS · SRTM, ASTER, GMTED (public domain)"

    const val RAIN_ATTRIBUTION = "RainViewer"
}
