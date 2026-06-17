package dev.mascwa.pulse.core.telemetry

/**
 * Turns a [DeviceContext] into the assistant's proactive, in-character remarks. Fully
 * deterministic and offline — it never fabricates facts, it just narrates real device
 * state, which keeps it testable and trustworthy.
 */
class BanterContextEngine {

    /** A context-aware opening line shown when the console comes up. */
    fun greeting(ctx: DeviceContext): String {
        val time = when (ctx.dayPart) {
            DayPart.MORNING -> "Good morning, sir."
            DayPart.AFTERNOON -> "Good afternoon, sir."
            DayPart.EVENING -> "Good evening, sir."
            DayPart.NIGHT -> "Burning the midnight oil, sir."
        }
        val power = when {
            ctx.isCriticalBattery -> " Battery's critical at ${ctx.batteryPct}% — I'd plug in now."
            ctx.isLowBattery -> " Battery's low at ${ctx.batteryPct}%. Say \"lockdown\" for a lean profile."
            ctx.isCharging && ctx.batteryPct >= 0 -> " Charging — ${ctx.batteryPct}% and climbing."
            ctx.batteryPct >= 0 -> " Power at ${ctx.batteryPct}%."
            else -> ""
        }
        val net = when (ctx.network) {
            NetworkKind.OFFLINE -> " You're offline — everything I do stays on-device anyway."
            NetworkKind.VPN -> " VPN's up."
            else -> ""
        }
        return "$time Matrix online.$power$net".trim()
    }

    /** A one-off remark when context crosses a notable threshold, or null if nothing's worth saying. */
    fun reactTo(old: DeviceContext?, now: DeviceContext): String? {
        if (old == null) return null
        return when {
            !old.isCharging && now.isCharging -> "Charger detected — ${now.batteryPct}%."
            old.isCharging && !now.isCharging -> "Unplugged. On battery at ${now.batteryPct}%."
            !old.isCriticalBattery && now.isCriticalBattery ->
                "Battery critical — ${now.batteryPct}%. Plug in, or say \"lockdown\"."
            !old.isLowBattery && now.isLowBattery ->
                "Battery down to ${now.batteryPct}%. Want a low-power lockdown?"
            !old.isPowerSave && now.isPowerSave -> "Power-save engaged. Trimming background work."
            old.network != NetworkKind.OFFLINE && now.network == NetworkKind.OFFLINE ->
                "Network dropped. I'm fully offline-capable, so we continue."
            else -> null
        }
    }

    /** A precise, deterministic telemetry read-out for the "status" intent. */
    fun statusReport(ctx: DeviceContext): String = buildString {
        append("Systems nominal.\n")
        append("• Power: ")
        append(if (ctx.batteryPct >= 0) "${ctx.batteryPct}%" else "unknown")
        append(if (ctx.isCharging) " (charging · ${ctx.powerSource.name.lowercase()})" else " on battery")
        if (ctx.isPowerSave) append(" · power-save on")
        append("\n• Network: ").append(ctx.network.name.lowercase())
        append("\n• Local time: ").append("${ctx.hour}:00 (").append(ctx.dayPart.name.lowercase()).append(")")
    }
}
