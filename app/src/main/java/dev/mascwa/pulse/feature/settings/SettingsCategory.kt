package dev.mascwa.pulse.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The Steam-style settings taxonomy — a fixed category list on the left (or a master list on a phone),
 * each opening a detail page of the folded-in sections. Every one of the old flat sections maps to exactly
 * one category. [tag] is the short label used by the narrow icon rail; [keywords] widen search hits for the
 * category itself (individual controls are also matched by their own titles at emission time).
 */
enum class SettingsCategory(
    val title: String,
    val blurb: String,
    val tag: String,
    val icon: ImageVector,
    val keywords: String = "",
) {
    SYSTEM("System", "Updates · diagnostics · backup", "SYS", Icons.Filled.SystemUpdate,
        "version apk build install download crash diagnostics backup restore export import"),
    INTERFACE("Interface", "Theme · accessibility · home layout", "UI", Icons.Filled.Palette,
        "appearance colour color overlay contrast haptics home dashboard"),
    REGION("Region & units", "Country · language · units", "RGN", Icons.Filled.Language,
        "locale currency temperature wind precipitation clock 24 hour news"),
    NOTIFICATIONS("Notifications", "Alerts · check-ins · quiet hours", "NTF", Icons.Filled.Notifications,
        "push breaking emergency market weather safety flight survival tips digest oracle quiet hours"),
    // ⚠️ The blurb names mail, and the keywords deliberately do NOT. This is the one field that
    // reaches both the master row and device search WITHOUT being read by SettingsScreen's `vis`,
    // whose match is a plain substring over `title + keywords + the section's own keywords`. Five
    // sections live under CONTENT, so a mail word in `keywords` or `title` would make searching
    // "mail" inside Settings return all five — breaking the one surface that already worked in
    // order to fix the two that did not. "watchlist" and "mutes" left the blurb for the keywords,
    // where search still finds them.
    //
    // ⚠️ Both "mail" and "email" are spelled out because device search ranks through
    // [dev.mascwa.pulse.core.telemetry.GuideSearch], whose wordMatch scores a prefix relation and
    // nothing else — neither word is a prefix of the other, so one does not find the other.
    CONTENT("Content & feeds", "Refresh · feeds · texts · mail · email", "FEED", Icons.Filled.ViewList,
        "refresh interval wifi articles watchlist crypto rss feed muted keywords"),
    SECURITY("Security & privacy", "Sensing · network · audit ledger", "SEC", Icons.Filled.Shield,
        "ambient camera mic microphone trusted network wifi ssid encryption https audit ledger"),
    DEVICE("Device & owner", "Hardware · device owner · hardening", "DEV", Icons.Filled.PhoneAndroid,
        "hardware graphene os attestation sensors owner dpc usb camera wipe penalty commitment lock hardening restricted usage install"),
    KEYS("API keys", "Optional data-source keys", "KEY", Icons.Filled.VpnKey,
        "token api openrouter github openai google key"),
    SAFETY("Safety (SOS)", "Medical card · contacts", "SOS", Icons.Filled.MedicalServices,
        "emergency medical blood allergy contact sms"),
    STORAGE("Storage & data", "Cache · memory · reset · about", "STOR", Icons.Filled.Storage,
        "cache clear activity log profile tasks reflexes episodic memory reset about build"),
    ;

    companion object {
        val FIRST = SYSTEM
    }
}
