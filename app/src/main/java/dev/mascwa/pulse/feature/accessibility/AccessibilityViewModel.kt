package dev.mascwa.pulse.feature.accessibility

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dev.mascwa.pulse.navigation.Destinations
import dev.mascwa.pulse.data.UserInteractionData
import dev.mascwa.pulse.data.AccessibilityRepository

class AccessibilityViewModel(private val repository: AccessibilityRepository) : ViewModel() {

    private val _usageData = MutableLiveData<List<UserInteractionData>>()
    val usageData: LiveData<List<UserInteractionData>> = _usageData

    private val _diagnosticsStatus = MutableLiveData<Boolean>()
    val diagnosticsStatus: LiveData<Boolean> = _diagnosticsStatus

    private val _featureRecommendations = MutableLiveData<List<String>>()
    val featureRecommendations: LiveData<List<String>> = _featureRecommendations

    init {
        loadUsageData()
        performAutonomousDiagnostics()
    }

    private fun loadUsageData() {
        _usageData.value = repository.getUsageData()
    }

    private fun performAutonomousDiagnostics() {
        val status = repository.runDiagnostics()
        _diagnosticsStatus.value = status
    }

    fun fetchFeatureRecommendations() {
        val recommendations = repository.getRecommendedFeatures(_usageData.value)
        _featureRecommendations.value = recommendations
    }

    fun updateUserFeedback(feedback: String) {
        repository.updateFeedback(feedback)
        adaptFeaturesBasedOnFeedback()
    }

    private fun adaptFeaturesBasedOnFeedback() {
        val updatedFeatures = repository.adaptFeatures(_usageData.value)
        _featureRecommendations.value = updatedFeatures
    }
}