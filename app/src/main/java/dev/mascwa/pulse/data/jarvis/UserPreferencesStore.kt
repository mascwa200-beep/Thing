package dev.mascwa.pulse.data.jarvis

import android.content.Context
import android.content.SharedPreferences

class UserPreferencesStore(context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("user_preferences", Context.MODE_PRIVATE)

    fun saveUserPreference(key: String, value: String) {
        sharedPreferences.edit().putString(key, value).apply()
    }

    fun getUserPreference(key: String): String? {
        return sharedPreferences.getString(key, null)
    }

    fun saveUserInterests(interests: List<String>) {
        sharedPreferences.edit().putStringSet("user_interests", interests.toSet()).apply()
    }

    fun getUserInterests(): List<String> {
        return sharedPreferences.getStringSet("user_interests", emptySet())?.toList() ?: emptyList()
    }

    fun saveOngoingProjects(projects: List<String>) {
        sharedPreferences.edit().putStringSet("ongoing_projects", projects.toSet()).apply()
    }

    fun getOngoingProjects(): List<String> {
        return sharedPreferences.getStringSet("ongoing_projects", emptySet())?.toList() ?: emptyList()
    }
}