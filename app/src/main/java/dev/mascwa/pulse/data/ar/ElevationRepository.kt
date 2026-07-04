package dev.mascwa.pulse.data.ar

import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.BuildingFootprints
import dev.mascwa.pulse.core.telemetry.ElevationField
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * Fetches a small **real elevation grid** around the player — the invisible ground anchor for the AR
 * wasteland — via the free, keyless **Open-Meteo elevation API** (Copernicus DEM, batched: one request for
 * all samples). The grid matches [ElevationField]'s convention (row-major, iz south→north outer, ix west→east
 * inner, spanning ±[HALF] m around the fix) so the parsed response drops straight in.
 *
 * On-device only; fully defensive — any network/parse failure → null, and the renderer stays flat-anchored to
 * its procedural dunes.
 */
class ElevationRepository(
    private val http: HttpClient,
) {
    /** A DEM elevation field around ([lat], [lon]), or null on any failure. */
    suspend fun near(lat: Double, lon: Double): ElevationField? {
        val n = RES + 1
        val step = 2f * HALF / RES
        val mLat = BuildingFootprints.M_PER_DEG_LAT
        val mLon = BuildingFootprints.metersPerDegLon(lat)
        val lats = StringBuilder()
        val lons = StringBuilder()
        for (iz in 0..RES) {
            val north = -HALF + iz * step
            val plat = lat + north / mLat
            for (ix in 0..RES) {
                val east = -HALF + ix * step
                val plon = lon + east / mLon
                if (lats.isNotEmpty()) { lats.append(','); lons.append(',') }
                lats.append(plat); lons.append(plon)
            }
        }
        val url = "https://api.open-meteo.com/v1/elevation" +
            "?latitude=" + URLEncoder.encode(lats.toString(), "UTF-8") +
            "&longitude=" + URLEncoder.encode(lons.toString(), "UTF-8")
        return runCatching {
            val text = http.getString(url)
            val arr = http.json.parseToJsonElement(text).jsonObject["elevation"]?.jsonArray
                ?: return@runCatching null
            if (arr.size != n * n) return@runCatching null
            val elev = FloatArray(n * n)
            for (i in 0 until n * n) {
                val v = arr[i].jsonPrimitive.doubleOrNull ?: return@runCatching null
                elev[i] = v.toFloat()
            }
            ElevationField(HALF, RES, elev).takeIf { it.isValid }
        }.getOrNull()
    }

    private companion object {
        const val HALF = 60f  // matches WastelandRenderer.WORLD_HALF (the terrain extent)
        const val RES = 8     // 9×9 = 81 samples — within Open-Meteo's 100-point batch limit
    }
}
