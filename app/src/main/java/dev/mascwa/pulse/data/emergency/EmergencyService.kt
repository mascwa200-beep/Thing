package dev.mascwa.pulse.data.emergency

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.data.settings.EmergencyCard
import dev.mascwa.pulse.data.settings.EmergencyContact

/**
 * Emergency comms: locale-aware emergency number, pre-filled SMS / dialer /
 * share intents, and (optionally) directly sending an SOS SMS with live
 * coordinates. Intent paths need no special permission; only the auto-send
 * path uses SEND_SMS.
 */
class EmergencyService(private val context: Context) {

    /** Best-effort local emergency number from the SIM/locale country. */
    fun emergencyNumber(countryIso: String?): String {
        val cc = (countryIso ?: "").uppercase()
        return EMERGENCY_NUMBERS[cc] ?: "112" // 112 works across most of the world / GSM
    }

    fun dial(number: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun buildSosMessage(
        latitude: Double?,
        longitude: Double?,
        card: EmergencyCard,
        includeCard: Boolean,
    ): String = buildString {
        append("SOS — I need help.")
        if (latitude != null && longitude != null) {
            append("\nLocation: %.5f, %.5f".format(latitude, longitude))
            append("\nMap: https://maps.google.com/?q=$latitude,$longitude")
        }
        if (includeCard && !card.isEmpty) {
            append("\n--")
            if (card.fullName.isNotBlank()) append("\nName: ${card.fullName}")
            if (card.bloodType.isNotBlank()) append("\nBlood: ${card.bloodType}")
            if (card.allergies.isNotBlank()) append("\nAllergies: ${card.allergies}")
            if (card.medications.isNotBlank()) append("\nMeds: ${card.medications}")
            if (card.conditions.isNotBlank()) append("\nConditions: ${card.conditions}")
        }
    }

    /** Open the SMS app pre-filled (no permission needed). */
    fun composeSms(contacts: List<EmergencyContact>, body: String) {
        val numbers = contacts.joinToString(";") { it.phone }
        val uri = Uri.parse("smsto:$numbers")
        val intent = Intent(Intent.ACTION_SENDTO, uri)
            .putExtra("sms_body", body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Share location/SOS via the system share sheet (no permission needed). */
    fun share(body: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, body)
        val chooser = Intent.createChooser(intent, "Send for help")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }

    fun canAutoSendSms(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.SEND_SMS,
    ) == PackageManager.PERMISSION_GRANTED

    /** Directly send the SOS SMS to each contact (requires SEND_SMS). */
    fun autoSendSms(contacts: List<EmergencyContact>, body: String): Boolean {
        if (!canAutoSendSms() || contacts.isEmpty()) return false
        return try {
            @Suppress("DEPRECATION")
            val sms = context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
            contacts.forEach { c ->
                val parts = sms.divideMessage(body)
                sms.sendMultipartTextMessage(c.phone, null, parts, null, null)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        // Common local emergency numbers; "112" is the broad fallback.
        private val EMERGENCY_NUMBERS = mapOf(
            "US" to "911", "CA" to "911", "MX" to "911",
            "GB" to "999", "IE" to "112", "AU" to "000", "NZ" to "111",
            "IN" to "112", "JP" to "110", "CN" to "110", "KR" to "112",
            "ZA" to "10111", "BR" to "190", "AR" to "911", "RU" to "112",
        )
    }
}
