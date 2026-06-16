package dev.mascwa.pulse.core.telemetry

/** Where the device is drawing power from. */
enum class PowerSource { BATTERY, AC, USB, WIRELESS, UNKNOWN }

/** Coarse classification of the active network transport. */
enum class NetworkKind { WIFI, CELLULAR, ETHERNET, VPN, OFFLINE, OTHER }

/** Rough time-of-day bucket, used to colour the assistant's banter. */
enum class DayPart { NIGHT, MORNING, AFTERNOON, EVENING }

/**
 * A coarse, privacy-safe snapshot of the device the assistant reasons about: power,
 * connectivity and local time. Everything here is read from system services on demand —
 * nothing is stored or transmitted.
 */
data class DeviceContext(
    val batteryPct: Int,        // 0..100, or -1 when unknown
    val isCharging: Boolean,
    val powerSource: PowerSource,
    val isPowerSave: Boolean,
    val network: NetworkKind,
    val dayPart: DayPart,
    val hour: Int,              // 0..23 local
    val timestamp: Long,
) {
    val isLowBattery: Boolean get() = batteryPct in 0..19 && !isCharging
    val isCriticalBattery: Boolean get() = batteryPct in 0..9 && !isCharging
    val isOffline: Boolean get() = network == NetworkKind.OFFLINE
}
