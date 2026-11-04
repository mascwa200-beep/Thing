package dev.mascwa.pulse.data.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import dev.mascwa.pulse.feature.phone.PhoneBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android's phone book, read for the LCARS phone: every phone number and whose it is.
 *
 * ⚠️ **Read, never written.** Adding or editing a contact is handed to Android's own Contacts app,
 * which knows which account a contact belongs to and how to sync it back; a second editor that got
 * that wrong would lose somebody's phone book in a way no undo reaches.
 *
 * ⚠️ **Nothing read here leaves this class except as a return value.** It is not logged, cached to a
 * file, or reported — `PhoneRecordsNothingTest` holds the phone packages to that.
 *
 * Everything runs on the IO dispatcher: a phone book of a few thousand people is a real query.
 */
class ContactsRepository(private val context: Context) {

    /**
     * Whether the phone book may be read at all. Asked separately from [rows] because an empty phone
     * book and one LCARS was never allowed to open must not look the same on screen.
     */
    fun canRead(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /**
     * Every number in the phone book, one row each, in Android's own order. Empty without permission.
     *
     * ⚠️ **A provider that fails is a failure, not an empty phone book** — the same distinction
     * `CalendarRepository.agenda` draws, for the same reason: the screen says "could not read your
     * contacts" rather than "no contacts".
     */
    suspend fun rows(): Result<List<PhoneBook.Row>> = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext Result.success(emptyList())
        runCatching {
            val out = ArrayList<PhoneBook.Row>()
            val cursor = context.contentResolver.query(
                Phone.CONTENT_URI,
                PROJECTION,
                null,
                null,
                "${Phone.CONTACT_ID} ASC",
            ) ?: error("the contacts provider answered nothing")
            cursor.use { c ->
                val id = c.getColumnIndexOrThrow(Phone.CONTACT_ID)
                val key = c.getColumnIndexOrThrow(Phone.LOOKUP_KEY)
                val name = c.getColumnIndexOrThrow(Phone.DISPLAY_NAME_PRIMARY)
                val starred = c.getColumnIndexOrThrow(Phone.STARRED)
                val number = c.getColumnIndexOrThrow(Phone.NUMBER)
                val type = c.getColumnIndexOrThrow(Phone.TYPE)
                val label = c.getColumnIndexOrThrow(Phone.LABEL)
                val primary = c.getColumnIndexOrThrow(Phone.IS_SUPER_PRIMARY)
                val res = context.resources
                while (c.moveToNext()) {
                    val n = c.getString(number) ?: continue
                    out += PhoneBook.Row(
                        contactId = c.getLong(id),
                        lookupKey = c.getString(key).orEmpty(),
                        name = c.getString(name),
                        starred = c.getInt(starred) != 0,
                        number = n,
                        label = Phone.getTypeLabel(res, c.getInt(type), c.getString(label)).toString(),
                        primary = c.getInt(primary) != 0,
                    )
                }
            }
            out
        }
    }

    /**
     * The phone book's name for [number], however it is written, or null. Android's own lookup
     * ([ContactsContract.PhoneLookup]), which matches numbers the way the rest of the phone does.
     */
    suspend fun nameFor(number: String): String? = withContext(Dispatchers.IO) {
        if (number.isBlank() || !canRead()) return@withContext null
        runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }

    private companion object {
        val PROJECTION = arrayOf(
            Phone.CONTACT_ID,
            Phone.LOOKUP_KEY,
            Phone.DISPLAY_NAME_PRIMARY,
            Phone.STARRED,
            Phone.NUMBER,
            Phone.TYPE,
            Phone.LABEL,
            Phone.IS_SUPER_PRIMARY,
        )
    }
}
