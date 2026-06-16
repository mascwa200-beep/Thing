package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BanterContextEngineTest {

    private val banter = BanterContextEngine()

    private fun context(
        batteryPct: Int,
        charging: Boolean,
        network: NetworkKind = NetworkKind.WIFI,
        powerSave: Boolean = false,
    ) = DeviceContext(
        batteryPct = batteryPct,
        isCharging = charging,
        powerSource = if (charging) PowerSource.AC else PowerSource.BATTERY,
        isPowerSave = powerSave,
        network = network,
        dayPart = DayPart.MORNING,
        hour = 10,
        timestamp = 0L,
    )

    @Test
    fun noReactionWithoutPriorContext() {
        assertNull(banter.reactTo(null, context(50, charging = false)))
    }

    @Test
    fun chargerConnectionIsAnnounced() {
        val line = banter.reactTo(context(40, charging = false), context(41, charging = true))
        assertNotNull(line)
        assertTrue(line!!.contains("Charger"))
    }

    @Test
    fun crossingIntoLowBatteryPrompts() {
        assertNotNull(banter.reactTo(context(30, charging = false), context(15, charging = false)))
    }

    @Test
    fun trivialChangeIsSilent() {
        assertNull(banter.reactTo(context(50, charging = false), context(51, charging = false)))
    }

    @Test
    fun statusReportIncludesPowerAndNetwork() {
        val report = banter.statusReport(context(77, charging = true, network = NetworkKind.WIFI))
        assertTrue(report.contains("77%"))
        assertTrue(report.lowercase().contains("wifi"))
    }

    @Test
    fun offlineGreetingMentionsOnDevice() {
        val greeting = banter.greeting(context(60, charging = false, network = NetworkKind.OFFLINE))
        assertTrue(greeting.lowercase().contains("offline"))
    }
}
