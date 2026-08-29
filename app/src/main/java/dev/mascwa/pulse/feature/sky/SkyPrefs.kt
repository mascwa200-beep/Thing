package dev.mascwa.pulse.feature.sky

import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.sky.SkyPreferences

/**
 * This application's answer to the star map's one remembered preference.
 *
 * ⚠️ **An adapter over the whole settings blob rather than a store of its own**, which is the
 * opposite of what the standalone app does and right for the same reason its choice is right there:
 * that app has four preferences and this one has well over a hundred, all in a single encrypted
 * DataStore record. A second store here would mean a second file, a second flush and a second thing
 * `MainActivity.onStop` has to remember. Same shape as [SkyDevice] one file over: an adapter is what
 * a seam is for.
 *
 * ⚠️ **`update` performs a read-modify-write on the whole record**, so a write here costs the same
 * as any other settings change. Acceptable because it happens when somebody presses a chip, not per
 * frame — and the view model only calls it on a genuine change of mode, never per sensor sample.
 */
class SkyPrefs(private val settings: SettingsRepository) : SkyPreferences {

    override suspend fun followByDefault(): Boolean = settings.current().skyFollowByDefault

    override suspend fun setFollowByDefault(on: Boolean) {
        settings.update { it.copy(skyFollowByDefault = on) }
    }
}
