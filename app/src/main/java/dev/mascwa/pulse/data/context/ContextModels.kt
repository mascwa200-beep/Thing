package dev.mascwa.pulse.data.context

import java.time.LocalDateTime

data class ContextState(
    val timeOfDay: TimeOfDay,
    val location: LocationContext,
    val batteryLevel: Int,
    val timestamp: LocalDateTime
)

enum class TimeOfDay {
    EARLY_MORNING,   // 4-7 AM
    MORNING,         // 7-12 PM
    AFTERNOON,       // 12-5 PM
    EVENING,         // 5-9 PM
    NIGHT            // 9-4 AM
}

data class LocationContext(
    val latitude: Double,
    val longitude: Double,
    val category: LocationCategory,
    val timestamp: LocalDateTime
) {
    fun distanceTo(other: LocationContext): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            latitude, longitude,
            other.latitude, other.longitude,
            results
        )
        return results[0]
    }
}

enum class LocationCategory {
    HOME,
    WORK,
    TRANSIT,
    OTHER
}

data class ContextShift(
    val type: ShiftType,
    val previousState: ContextState,
    val currentState: ContextState,
    val timestamp: LocalDateTime
)

enum class ShiftType {
    TIME_OF_DAY_CHANGE,
    LOCATION_CHANGE,
    BATTERY_THRESHOLD_CROSSED,
    LOCATION_AND_TIME_CHANGE
}

data class ContextLog(
    val id: String,
    val shift: ContextShift,
    val detectedAt: LocalDateTime,
    val isProcessed: Boolean = false
)