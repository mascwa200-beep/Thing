package dev.mascwa.pulse.feature.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.usage.UsageRepository
import dev.mascwa.pulse.navigation.GROUPS
import dev.mascwa.pulse.navigation.MenuEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The MENU's one piece of state that is not the directory itself: what you reached for recently.
 *
 * Recents are RECENCY-ordered, not count-ordered — Home's launcher row (most used) answers "what do
 * I always open"; this strip answers "take me back to what I was just doing". Only menu-listed
 * destinations qualify: the bottom-nav tabs are permanently one tap away already, so a chip for one
 * would spend the strip on something the thumb can reach anyway, and the junk keys the usage store
 * used to mint (route patterns with arguments) can never match a directory route.
 */
class MenuViewModel(private val usage: UsageRepository) : ViewModel() {

    private val _recents = MutableStateFlow<List<MenuEntry>>(emptyList())
    val recents: StateFlow<List<MenuEntry>> = _recents.asStateFlow()

    init {
        viewModelScope.launch {
            val byRoute = GROUPS.flatMap { it.entries }.associateBy { it.route }
            _recents.value = usage.snapshot().features
                .asSequence()
                .filter { it.lastEpochMs > 0L && it.key in byRoute }
                .sortedByDescending { it.lastEpochMs }
                .take(RECENTS)
                .mapNotNull { byRoute[it.key] }
                .toList()
        }
    }

    private companion object {
        /** Six chips is one comfortable wrapped row at phone width; more becomes a second directory. */
        const val RECENTS = 6
    }
}
