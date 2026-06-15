package dev.mascwa.pulse.feature.fuel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.data.fuel.FuelData
import dev.mascwa.pulse.data.fuel.FuelRepository
import dev.mascwa.pulse.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class FuelViewModel(
    private val repo: FuelRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<Async<FuelData>>(Async(loading = true))
    val state: StateFlow<Async<FuelData>> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.settings
                .map { it.countryCode to it.apiKeys.eia }
                .distinctUntilChanged()
                .collect { refresh(force = false) }
        }
    }

    fun refresh(force: Boolean = true) {
        viewModelScope.launch { _state.load(force) { repo.fetch(it) } }
    }
}
