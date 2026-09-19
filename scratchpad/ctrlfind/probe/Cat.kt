package dev.mascwa.pulse.feature.settings
// real signature: enum class SettingsCategory(val title: String, val blurb: String,
//   val tag: String, val icon: ImageVector, val keywords: String = "")
// The icon is the only Android type; dropped here because searchRecords() never reads it.
enum class SettingsCategory(val title: String, val blurb: String, val keywords: String = "") {
    SYSTEM("System", "Updates", "version apk"),
    INTERFACE("Interface", "Theme", "appearance"),
    REGION("Region & units", "Country", "locale"),
    NOTIFICATIONS("Notifications", "Alerts", "push"),
    CONTENT("Content & feeds", "Refresh", "refresh"),
    SECURITY("Security & privacy", "Sensing", "ambient"),
    DEVICE("Device & owner", "Hardware", "hardware"),
    KEYS("API keys", "Optional", "token"),
    SAFETY("Safety (SOS)", "Medical card", "emergency"),
    STORAGE("Storage & data", "Cache", "cache"),
}
