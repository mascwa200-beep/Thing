package dev.mascwa.pulse.jarvis.agent

import android.content.Context
import dev.mascwa.pulse.core.util.composeEmail
import dev.mascwa.pulse.core.util.composeSms
import dev.mascwa.pulse.core.util.createCalendarEvent
import dev.mascwa.pulse.core.util.dialNumber
import dev.mascwa.pulse.core.util.openCamera
import dev.mascwa.pulse.core.util.openContacts
import dev.mascwa.pulse.core.util.openSettingsPanel
import dev.mascwa.pulse.core.util.openSpotifySearch
import dev.mascwa.pulse.core.util.openUrl
import dev.mascwa.pulse.core.util.searchMaps
import dev.mascwa.pulse.core.util.setAlarm
import dev.mascwa.pulse.core.util.setTimer

/**
 * Device-action tools: each OPENS the relevant app pre-filled so the user makes the final call/send/save.
 * No dangerous permissions and nothing is sent autonomously — the human stays in the loop. Args use
 * " | " to separate fields (documented per tool's [JarvisTool.usage]).
 */

private fun parts(arg: String): List<String> = arg.split("|").map { it.trim() }

/** Parse a loose time ("8:30", "08:30", "8", "7 pm", "7:15am") into hour(0-23)/minute, or null. */
private fun parseTime(text: String): Pair<Int, Int>? {
    val t = text.trim().lowercase()
    val pm = t.contains("pm")
    val am = t.contains("am")
    val m = Regex("(\\d{1,2})(?:\\s*[:.]\\s*(\\d{1,2}))?").find(t) ?: return null
    var hour = m.groupValues[1].toIntOrNull() ?: return null
    val min = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
    if (pm && hour < 12) hour += 12
    if (am && hour == 12) hour = 0
    if (hour !in 0..23 || min !in 0..59) return null
    return hour to min
}

class CallTool(private val context: Context) : JarvisTool {
    override val name = "call"
    override val usage = "call <number> — open the dialer pre-filled (you tap call)"
    override suspend fun run(arg: String): String {
        val number = arg.trim()
        if (number.isBlank()) return "Give me a phone number to dial."
        dialNumber(context, number)
        return "Opened the dialer for $number, sir."
    }
}

class SmsTool(private val context: Context) : JarvisTool {
    override val name = "sms"
    override val usage = "sms <number> | <message> — open a text pre-filled (you tap send)"
    override suspend fun run(arg: String): String {
        val p = parts(arg)
        val number = p.getOrElse(0) { "" }
        if (number.isBlank()) return "Give me a number, then | and the message."
        return if (composeSms(context, number, p.getOrElse(1) { "" })) "Opened a text to $number, sir."
        else "No app available to send texts."
    }
}

class EmailTool(private val context: Context) : JarvisTool {
    override val name = "email"
    override val usage = "email <address> | <subject> | <body> — open an email pre-filled (you tap send)"
    override suspend fun run(arg: String): String {
        val p = parts(arg)
        return if (composeEmail(context, p.getOrElse(0) { "" }, p.getOrElse(1) { "" }, p.getOrElse(2) { "" }))
            "Opened an email draft, sir." else "No email app available."
    }
}

class CalendarEventTool(private val context: Context) : JarvisTool {
    override val name = "event"
    override val usage = "event <title> | <details> — open a new calendar event for you to set the time"
    override suspend fun run(arg: String): String {
        val p = parts(arg)
        val title = p.getOrElse(0) { "" }
        if (title.isBlank()) return "Give me an event title."
        return if (createCalendarEvent(context, title, p.getOrElse(1) { "" }, 0))
            "Opened a new event \"$title\" — set the time, sir." else "No calendar app available."
    }
}

class AlarmTool(private val context: Context) : JarvisTool {
    override val name = "alarm"
    override val usage = "alarm <HH:MM> | <label> — set an alarm"
    override suspend fun run(arg: String): String {
        val p = parts(arg)
        val time = parseTime(p.getOrElse(0) { "" }) ?: return "Give me a time like 7:30 or 9 pm."
        return if (setAlarm(context, time.first, time.second, p.getOrElse(1) { "" }))
            "Alarm set for %02d:%02d, sir.".format(time.first, time.second) else "No clock app available."
    }
}

class TimerTool(private val context: Context) : JarvisTool {
    override val name = "timer"
    override val usage = "timer <minutes> | <label> — start a countdown timer"
    override suspend fun run(arg: String): String {
        val p = parts(arg)
        val minutes = p.getOrElse(0) { "" }.filter { it.isDigit() }.toIntOrNull()
            ?: return "Give me a number of minutes."
        return if (setTimer(context, minutes * 60, p.getOrElse(1) { "" }))
            "Timer started for $minutes min, sir." else "No clock app available."
    }
}

class CameraTool(private val context: Context) : JarvisTool {
    override val name = "camera"
    override val usage = "camera — open the camera to take a photo"
    override suspend fun run(arg: String): String =
        if (openCamera(context)) "Opening the camera, sir." else "No camera app available."
}

class ContactsTool(private val context: Context) : JarvisTool {
    override val name = "contacts"
    override val usage = "contacts — open the contacts app"
    override suspend fun run(arg: String): String =
        if (openContacts(context)) "Opening contacts, sir." else "No contacts app available."
}

class SpotifyTool(private val context: Context) : JarvisTool {
    override val name = "spotify"
    override val usage = "spotify <song or artist> — open Spotify on a search"
    override suspend fun run(arg: String): String {
        if (arg.isBlank()) return "What should I look up on Spotify?"
        return if (openSpotifySearch(context, arg)) "Opening Spotify for \"${arg.trim()}\", sir." else "Couldn't open Spotify."
    }
}

class MapsTool(private val context: Context) : JarvisTool {
    override val name = "maps"
    override val usage = "maps <place or address> — open maps to a place"
    override suspend fun run(arg: String): String {
        if (arg.isBlank()) return "Where to, sir?"
        return if (searchMaps(context, arg)) "Opening maps for \"${arg.trim()}\", sir." else "No maps app available."
    }
}

class SettingsTool(private val context: Context) : JarvisTool {
    override val name = "settings"
    override val usage = "settings <wifi|bluetooth|location|display|sound|battery|data|nfc|apps|airplane> — open a settings screen"
    override suspend fun run(arg: String): String =
        if (openSettingsPanel(context, arg)) "Opening settings, sir." else "Couldn't open settings."
}

class OpenLinkTool(private val context: Context) : JarvisTool {
    override val name = "open"
    override val usage = "open <url> — open a link or app deep-link"
    override suspend fun run(arg: String): String {
        if (arg.isBlank()) return "Give me a URL to open."
        openUrl(context, arg.trim())
        return "Opening ${arg.trim()}, sir."
    }
}

class TorchTool(context: Context) : JarvisTool {
    private val tools = dev.mascwa.pulse.data.sensors.SurvivalTools(context)
    override val name = "torch"
    override val usage = "torch <on|off> — turn the flashlight on or off"
    override suspend fun run(arg: String): String {
        if (!tools.torchAvailable()) return "No flashlight on this device, sir."
        val off = arg.lowercase().trim().let { it == "off" || it == "false" || it == "0" || it == "stop" }
        tools.setTorch(!off)
        return if (off) "Flashlight off, sir." else "Flashlight on, sir."
    }
}

class ClipboardTool(private val context: Context) : JarvisTool {
    override val name = "clip"
    override val usage = "clip <text> — copy text to the clipboard"
    override suspend fun run(arg: String): String {
        if (arg.isBlank()) return "Give me something to copy."
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
            ?: return "Clipboard unavailable."
        runCatching { cm.setPrimaryClip(android.content.ClipData.newPlainText("J.A.R.V.I.S.", arg.trim())) }
        return "Copied to the clipboard, sir."
    }
}
