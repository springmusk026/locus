package dev.locus.core.data.device

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.provider.ContactsContract

/** The intents and clip the phone hand-off tools build, kept apart so they're tested (docs/design/phone-integrations.md). */
object PhoneIntents {
    /**
     * The Contacts app's "new contact" screen, filled in. developer.android.com/training/contacts-provider/modify-data:
     * "create a new Intent object with the action ContactsContract.Intents.Insert.ACTION. Set the MIME type to
     * ContactsContract.RawContacts.CONTENT_TYPE"; no permission, since the Contacts app does the writing and the
     * user can cancel.
     */
    fun insertContact(contact: NewContact): Intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE
        putExtra(ContactsContract.Intents.Insert.NAME, contact.name)
        val phones = listOf(ContactsContract.Intents.Insert.PHONE, ContactsContract.Intents.Insert.SECONDARY_PHONE, ContactsContract.Intents.Insert.TERTIARY_PHONE)
        contact.phones.take(3).forEachIndexed { i, p -> putExtra(phones[i], p) }
        val emails = listOf(ContactsContract.Intents.Insert.EMAIL, ContactsContract.Intents.Insert.SECONDARY_EMAIL, ContactsContract.Intents.Insert.TERTIARY_EMAIL)
        contact.emails.take(3).forEachIndexed { i, e -> putExtra(emails[i], e) }
        contact.company?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
        contact.note?.let { putExtra(ContactsContract.Intents.Insert.NOTES, it) }
    }

    /**
     * The system "Save as" picker (developer.android.com/training/data-storage/shared/documents-files): no
     * permission; "ACTION_CREATE_DOCUMENT cannot overwrite an existing file", the provider renames instead.
     */
    fun saveAs(name: String, mime: String): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(mime)
        .putExtra(Intent.EXTRA_TITLE, name)

    /**
     * Text for the clipboard, flagged sensitive so Android's copy preview and keyboards don't show it
     * (developer.android.com/develop/ui/views/touch-and-input/copy-paste: "All apps must do this, regardless
     * of the targeted API level"; the constant exists from API 33, the plain key before it).
     */
    fun sensitiveClip(label: String, text: String): ClipData = ClipData.newPlainText(label, text).apply {
        description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
    }
}
