package dev.mascwa.pulse.feature.launcher

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the home screen remembers: the apps pinned to its dock, in the order they were pinned.
 *
 * A small preferences file of its own rather than a field on the app's settings, because a home
 * screen is read on every press of the Home button and the app's settings are a Keystore-decrypted
 * blob of some two hundred fields. Nothing here is a secret — it is a list of app names.
 *
 * ⚠️ Every read and write is on the IO dispatcher: the FIRST read of a preferences file parses it
 * on whatever thread asks, and the home screen's thread is the one the whole phone is waiting on.
 */
class HomeStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** The pinned app keys, oldest pin first. */
    suspend fun pins(): List<String> = withContext(Dispatchers.IO) {
        prefs.getString(KEY_PINS, null).orEmpty().split(SEPARATOR).filter { it.isNotBlank() }
    }

    /**
     * Pin [key] if it is not pinned, unpin it if it is, and return the dock as it now stands. Null
     * when the dock is full and the pin was refused — see [HomeDirectory.togglePin].
     */
    suspend fun togglePin(key: String): List<String>? = withContext(Dispatchers.IO) {
        val current = prefs.getString(KEY_PINS, null).orEmpty().split(SEPARATOR).filter { it.isNotBlank() }
        val next = HomeDirectory.togglePin(current, key) ?: return@withContext null
        // commit(), not apply(): the answer is drawn straight back, and a pin that had not landed
        // when the process was reclaimed would silently come undone.
        prefs.edit().putString(KEY_PINS, next.joinToString(SEPARATOR)).commit()
        next
    }

    private companion object {
        const val FILE = "lcars_home"
        const val KEY_PINS = "pins"

        /** A key is a component name, a `#` and a number, so it can never contain a line break. */
        const val SEPARATOR = "\n"
    }
}
