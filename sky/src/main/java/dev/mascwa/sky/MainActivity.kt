package dev.mascwa.sky

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.mascwa.pulse.feature.sky.SkyMapViewModel

/**
 * The whole application: one activity, one map, no gate and no navigation.
 *
 * ⚠️ **The view model is the shared one, verbatim** — the same `SkyMapViewModel` the LCARS
 * application's sky map drives, so where a star is drawn is decided once. What differs between the
 * two applications is the dependency bundle handed to it ([SkyHardware] here, `SkyDevice` there)
 * and the chrome around the chart, and nothing else.
 *
 * ⚠️ `by viewModels` rather than constructing it in `setContent`, so it survives a rotation. Built
 * in a composable it would be discarded and rebuilt on every configuration change — and with it the
 * bright catalogue's 8,404 parsed rows, the memory-mapped deep catalogue, the constellation shapes,
 * the deep-sky layer and the Milky Way raster, all re-read from disk for a turn of the wrist.
 */
class MainActivity : ComponentActivity() {

    private val container by lazy { (application as SkyApplication).container }

    private val vm: SkyMapViewModel by viewModels { container.viewModelFactory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SkyTheme {
                Surface(Modifier.fillMaxSize()) {
                    StarMapScreen(vm, container.hardware.hasAttitudeSensor)
                }
            }
        }
    }
}
