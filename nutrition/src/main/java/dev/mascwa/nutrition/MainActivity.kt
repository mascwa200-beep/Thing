package dev.mascwa.nutrition

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.mascwa.nutrition.data.NutritionContainer
import dev.mascwa.nutrition.ui.NutritionApp
import dev.mascwa.nutrition.ui.NutritionTheme
import dev.mascwa.pulse.feature.health.HealthViewModel

/**
 * The whole application: one activity, six tabs, no gate.
 *
 * ⚠️ **The view model is the shared one, verbatim.** Every figure this app shows — a calorie target,
 * a weight trend, what a portion contributes — comes out of the same `HealthViewModel` the LCARS
 * application's HEALTH tab uses, because these are numbers somebody eats to and two copies of that
 * arithmetic would eventually disagree. What differs between the applications is the dependency
 * bundle handed to it, and nothing else.
 */
class MainActivity : ComponentActivity() {

    private val container by lazy { NutritionContainer(this) }

    /**
     * ⚠️ `by viewModels` rather than constructing it in `setContent`, so it survives a rotation.
     * Built in a composable it would be discarded and rebuilt on every configuration change, and
     * with it the day being viewed, a half-typed weight and every in-flight lookup.
     */
    private val vm: HealthViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HealthViewModel(container.healthDeps) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NutritionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    NutritionApp(vm)
                }
            }
        }
    }
}
