package dev.mascwa.sky

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.mascwa.pulse.data.sky.ConstellationCatalog
import dev.mascwa.pulse.data.sky.DeepSkyCatalog
import dev.mascwa.pulse.data.sky.DeepStarCatalog
import dev.mascwa.pulse.data.sky.MilkyWayCatalog
import dev.mascwa.pulse.data.sky.StarCatalog
import dev.mascwa.pulse.feature.sky.SkyMapViewModel

/**
 * Everything this application owns, which is five catalogue readers and one sensor adapter.
 *
 * ⚠️ **Every member is `by lazy`, so constructing this opens no file and touches no sensor.** The
 * deep catalogue memory-maps twenty-five megabytes on its first read and the bright one parses eight
 * thousand rows; doing either during `Application.onCreate` would put both in front of the first
 * frame for no reason. The view model asks for them when it loads, off the main thread.
 *
 * ⚠️ **One instance for the process, held by [SkyApplication].** Two containers would mean two
 * `DeepStarCatalog`s each holding their own mapping of the same file — twice the address space for
 * one catalogue, and two mutexes guarding nothing in common. The activity reads this one rather
 * than building its own, which is the mistake the nutrition application's container records having
 * made.
 */
class SkyContainer(private val context: Context) {

    val starCatalog by lazy { StarCatalog(context) }
    val deepStarCatalog by lazy { DeepStarCatalog(context) }
    val constellationCatalog by lazy { ConstellationCatalog(context) }
    val deepSkyCatalog by lazy { DeepSkyCatalog(context) }
    val milkyWayCatalog by lazy { MilkyWayCatalog(context) }

    /**
     * Where this phone is and where it is aimed.
     *
     * ⚠️ Held here rather than made per screen, because it registers a sensor listener and a second
     * one would leave the first arming the hardware behind a control that reads as off — the same
     * shape [SkyHardware.startAttitude] is guarded against internally.
     */
    val hardware by lazy { SkyHardware(context) }

    /**
     * ⚠️ A factory rather than `viewModels()` with a no-argument constructor, because
     * [SkyMapViewModel] takes six dependencies and there is nowhere else to hand them to it. The
     * `@Suppress` is the standard shape for this API: the cast is checked one line above it.
     */
    val viewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SkyMapViewModel::class.java)) {
                "SkyContainer builds only SkyMapViewModel, not ${modelClass.name}"
            }
            return SkyMapViewModel(
                starCatalog,
                deepStarCatalog,
                constellationCatalog,
                deepSkyCatalog,
                milkyWayCatalog,
                hardware,
            ) as T
        }
    }
}
