package dev.mascwa.pulse.data.context

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import dev.mascwa.pulse.data.repository.UsageRepository
import dev.mascwa.pulse.data.location.LocationProvider
import kotlinx.coroutines.*
import java.time.LocalDateTime
import java.time.LocalTime

class ContextMonitor(
    private val context: Context,
    private val usageRepository: UsageRepository,
    private val locationProvider: LocationProvider,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + Job())
) {
    private companion object {
        private const val TAG = "ContextMonitor"
        private const val POLLING_INTERVAL_MS = 60000L
        private const val BATTERY_THRESHOLD = 20
    }

    private var monitoringJob: Job? = null
    private var lastDetectedContext: DetectedContext? = null
    private val contextShiftListeners = mutableListOf<(ContextShift) -> Unit>()

    fun startMonitoring() {
        if (monitoringJob?.isActive == true) return

        monitoringJob = scope.launch {
            while (isActive) {
                try {
                    val currentContext = captureCurrentContext()
                    detectContextShifts(currentContext)
                    lastDetectedContext = currentContext
                } catch (e: Exception) {
                    Log.e(TAG, "Error during context monitoring", e)
                }
                delay(POLLING_INTERVAL_MS)
            }
        }
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
    }

    fun addContextShiftListener(listener: (ContextShift) -> Unit) {
        contextShiftListeners.add(listener)
    }

    fun removeContextShiftListener(listener: (ContextShift) -> Unit) {
        contextShiftListeners.remove(listener)
    }

    private suspend fun captureCurrentContext(): DetectedContext {
        val now = LocalDateTime.now()
        val timeOfDay = determineTimeOfDay(now.toLocalTime())
        val currentLocation = locationProvider.getCurrentLocation()
        val batteryLevel = getBatteryLevel()
        val appUsage = usageRepository.getRecentUsageStats()

        return DetectedContext(
            timestamp = now,
            timeOfDay = timeOfDay,
            location = currentLocation,
            batteryLevel = batteryLevel,
            appUsageCount = appUsage.size,
            isLowBattery = batteryLevel <= BATTERY_THRESHOLD
        )
    }

    private suspend fun detectContextShifts(currentContext: DetectedContext) {
        val previous = lastDetectedContext ?: return

        val shifts = mutableListOf<ContextShift>()

        // Detect time of day shift
        if (previous.timeOfDay != currentContext.timeOfDay) {
            shifts.add(
                ContextShift(
                    type = ContextShiftType.TIME_OF_DAY_CHANGE,
                    timestamp = currentContext.timestamp,
                    previousValue = previous.timeOfDay,
                    currentValue = currentContext.timeOfDay,
                    metadata = mapOf("transition" to "${previous.timeOfDay}->${currentContext.timeOfDay}")
                )
            )
            Log.d(TAG, "Time of day shift detected: ${previous.timeOfDay} -> ${currentContext.timeOfDay}")
        }

        // Detect location change
        if (previous.location != null && currentContext.location != null) {
            val distance = calculateDistance(previous.location, currentContext.location)
            if (distance > 100) { // 100 meters threshold
                shifts.add(
                    ContextShift(
                        type = ContextShiftType.LOCATION_CHANGE,
                        timestamp = currentContext.timestamp,
                        previousValue = previous.location,
                        currentValue = currentContext.location,
                        metadata = mapOf("distance_meters" to distance.toString())
                    )
                )
                Log.d(TAG, "Location shift detected: ${distance}m movement")
            }
        }

        // Detect battery threshold crossing
        if (!previous.isLowBattery && currentContext.isLowBattery) {
            shifts.add(
                ContextShift(
                    type = ContextShiftType.BATTERY_LOW,
                    timestamp = currentContext.timestamp,
                    previousValue = previous.batteryLevel,
                    currentValue = currentContext.batteryLevel,
                    metadata = mapOf("threshold" to BATTERY_THRESHOLD.toString())
                )
            )
            Log.d(TAG, "Low battery threshold crossed: ${currentContext.batteryLevel}%")
        } else if (previous.isLowBattery && !currentContext.isLowBattery) {
            shifts.add(
                ContextShift(
                    type = ContextShiftType.BATTERY_RECOVERED,
                    timestamp = currentContext.timestamp,
                    previousValue = previous.batteryLevel,
                    currentValue = currentContext.batteryLevel,
                    metadata = mapOf("threshold" to BATTERY_THRESHOLD.toString())
                )
            )
            Log.d(TAG, "Battery recovered: ${currentContext.batteryLevel}%")
        }

        // Notify listeners of all detected shifts
        shifts.forEach { shift ->
            contextShiftListeners.forEach { listener ->
                try {
                    listener(shift)
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying context shift listener", e)
                }
            }
        }
    }

    private fun determineTimeOfDay(time: LocalTime): TimeOfDay {
        return when {
            time.hour in 5..11 -> TimeOfDay.MORNING
            time.hour in 12..16 -> TimeOfDay.AFTERNOON
            time.hour in 17..20 -> TimeOfDay.EVENING
            else -> TimeOfDay.NIGHT
        }
    }

    private fun calculateDistance(location1: LocationData, location2: LocationData): Double {
        val lat1 = location1.latitude
        val lon1 = location1.longitude
        val lat2 = location2.latitude
        val lon2 = location2.longitude

        val earthRadiusM = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)

        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadiusM * c
    }

    private fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 50
    }

    fun getCurrentContext(): DetectedContext? = lastDetectedContext

    fun destroy() {
        stopMonitoring()
        scope.cancel()
    }
}