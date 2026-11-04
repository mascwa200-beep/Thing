package dev.mascwa.pulse.feature.phone

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.TelecomManager

/**
 * Placing a call, and the three things the phone screen hands to other apps: a text, a new contact,
 * and an edit.
 *
 * ## Placing a call
 *
 * Through [TelecomManager.placeCall], which is how a phone app is meant to call: Telecom routes it,
 * and — as the default phone app — an emergency number is dialled directly and handed to Android's
 * own emergency call screen (see `AppSuspension` for why that screen must survive).
 *
 * ⚠️ **When LCARS may not place the call, the number goes to Android's BUILT-IN dialler by package,
 * never to "whatever answers DIAL".** Once LCARS is the phone app, an implicit DIAL comes straight
 * back here, and a number would bounce between this screen and itself. The built-in dialler shows it
 * ready to call, one tap away.
 *
 * ⚠️ **The URI is built with `Uri.fromParts`, never `Uri.parse("tel:$number")`**: a `#` in a code such
 * as `*#06#` is a fragment to `parse`, which would dial `*` and drop the rest. `fromParts` encodes it.
 */
internal object PhoneCalls {

    sealed interface Outcome {
        /** Telecom has the call. Whether it connects is Telecom's to say, on the call screen. */
        data object Placed : Outcome

        /** The built-in dialler has the number, ready to call. */
        data object HandedToSystem : Outcome

        /** Nothing could take it, and [why] says so in words. */
        data class Refused(val why: String) : Outcome
    }

    fun canCall(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    fun call(context: Context, number: String): Outcome {
        val n = number.trim()
        if (n.isEmpty()) return Outcome.Refused("There is no number to call.")
        return place(context, Uri.fromParts("tel", n, null))
    }

    /** Voicemail, by Telecom's own scheme, which reaches whatever number the carrier gave it. */
    fun voicemail(context: Context): Outcome = place(context, Uri.fromParts("voicemail", "", null))

    private fun place(context: Context, uri: Uri): Outcome {
        val telecom = context.getSystemService(TelecomManager::class.java)
        if (telecom != null && canCall(context)) {
            val placed = runCatching { telecom.placeCall(uri, Bundle()) }.isSuccess
            if (placed) return Outcome.Placed
        }
        return handToSystem(context, uri, telecom)
    }

    private fun handToSystem(context: Context, uri: Uri, telecom: TelecomManager?): Outcome {
        val system = runCatching { telecom?.systemDialerPackage }.getOrNull()
        if (system.isNullOrBlank() || system == context.packageName) {
            return Outcome.Refused("LCARS may not place calls, and the phone's own dialler could not be found.")
        }
        val intent = Intent(Intent.ACTION_DIAL, uri).setPackage(system).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Outcome.HandedToSystem
        } catch (_: ActivityNotFoundException) {
            Outcome.Refused("The phone's own dialler would not open.")
        } catch (_: SecurityException) {
            Outcome.Refused("The phone's own dialler would not open.")
        }
    }

    /** A text to [number], in whatever app sends texts. */
    fun text(context: Context, number: String): Boolean =
        start(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number.trim(), null)))

    /** Android's Contacts app, ready to save [number] to a new or existing contact. */
    fun addToContacts(context: Context, number: String): Boolean =
        start(
            context,
            Intent(Intent.ACTION_INSERT_OR_EDIT)
                .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                .putExtra(ContactsContract.Intents.Insert.PHONE, number.trim()),
        )

    /** Android's Contacts app, open on the contact [id] / [lookupKey]. */
    fun openContact(context: Context, id: Long, lookupKey: String): Boolean {
        // A lookup URI survives a contact being re-aggregated; Android gives none for a blank key,
        // and the plain id is then the best there is.
        val uri: Uri = ContactsContract.Contacts.getLookupUri(id, lookupKey)
            ?: ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
        return start(context, Intent(Intent.ACTION_VIEW, uri))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
