package dev.mascwa.pulse.feature.jarvis

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class ContextualAwarenessViewModel(private val repository: UserPreferencesRepository) : ViewModel() {

    private val _userPreferences = MutableLiveData<List<UserPreference>>()
    val userPreferences: LiveData<List<UserPreference>> = _userPreferences

    private val _projects = MutableLiveData<List<Project>>()
    val projects: LiveData<List<Project>> = _projects

    init {
        loadUserPreferences()
        loadProjects()
    }

    private fun loadUserPreferences() {
        viewModelScope.launch {
            _userPreferences.value = repository.getUserPreferences()
        }
    }

    private fun loadProjects() {
        viewModelScope.launch {
            _projects.value = repository.getOngoingProjects()
        }
    }

    fun updateUserPreference(preference: UserPreference) {
        viewModelScope.launch {
            repository.updateUserPreference(preference)
            loadUserPreferences()
        }
    }

    fun addProject(project: Project) {
        viewModelScope.launch {
            repository.addProject(project)
            loadProjects()
        }
    }
}