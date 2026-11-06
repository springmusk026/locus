package dev.locus.core.data.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone integrations on a real phone (docs/design/android-bridge.md):
 * - the calendar through `CalendarContract.Instances`, with READ_CALENDAR asked for on first use;
 * - a contact through the system contact picker (Android 17's `ACTION_PICK_CONTACTS`, or `ACTION_PICK`
 *   on a data type before it), which shares only what the user picks; Locus holds no contacts permission;
 * - photos through the Photo Picker, copied into the workspace.
 */
@Singleton
class AndroidPhone @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val requests: PhoneRequests,
    private val workspace: WorkspaceRepository,
) : PhoneIntegrations {

    override suspend fun calendar(from: Long, to: Long, limit: Int): UserPick<List<CalendarItem>> {
        val permission = Manifest.permission.READ_CALENDAR
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            when (requests.permission(permission)) {
                null -> return UserPick.NotOnScreen
                false -> return UserPick.Cancelled
                true -> Unit
            }
        }
        return UserPick.Picked(withContext(Dispatchers.IO) { queryInstances(from, to, limit) })
    }

    private fun queryInstances(from: Long, to: Long, limit: Int): List<CalendarItem> {
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.RRULE,
        )
        val out = mutableListOf<CalendarItem>()
        // Instances.query expands recurring events into their occurrences in [from, to].
        CalendarContract.Instances.query(context.contentResolver, projection, from, to)?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                out += CalendarItem(
                    title = c.getString(0).orEmpty(),
                    start = c.getLong(1),
                    end = c.getLong(2),
                    allDay = c.getInt(3) != 0,
                    location = c.getString(4),
                    calendar = c.getString(5),
                    description = c.getString(6),
                    eventId = c.getLong(7),
                    recurring = !c.getString(8).isNullOrBlank(),
                )
            }
        }
        return out.sortedBy { it.start }
    }

    override suspend fun pickContact(field: ContactField): UserPick<PickedContact> {
        val modern = Build.VERSION.SDK_INT >= 37
        val intent = if (modern) {
            Intent(android.provider.ContactsPickerSessionContract.ACTION_PICK_CONTACTS).putStringArrayListExtra(
                android.provider.ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS,
                ArrayList(mimes(field)),
            )
        } else {
            // Before Android 17: the picker on one data type grants a one-time read of the picked row.
            Intent(Intent.ACTION_PICK).setType(
                if (field == ContactField.EMAIL) ContactsContract.CommonDataKinds.Email.CONTENT_TYPE else ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE,
            )
        }
        val result = requests.pick(intent) ?: return UserPick.NotOnScreen
        val uri = result.getOrNull() ?: return UserPick.Cancelled
        val contact = withContext(Dispatchers.IO) {
            try {
                readContact(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } ?: return UserPick.Cancelled
        return UserPick.Picked(contact)
    }

    private fun mimes(field: ContactField) = when (field) {
        ContactField.PHONE -> listOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
        ContactField.EMAIL -> listOf(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
        ContactField.ANY -> listOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
    }

    /** The picked rows (Data-style: display name, MIME type, value), folded into one contact. */
    private fun readContact(uri: Uri): PickedContact? {
        val projection = arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1)
        var name = ""
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (name.isEmpty()) name = c.getString(0).orEmpty()
                val value = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                when (c.getString(1)) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> phones += value
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> emails += value
                }
            }
        } ?: return null
        if (name.isEmpty() && phones.isEmpty() && emails.isEmpty()) return null
        return PickedContact(name, phones.distinct(), emails.distinct())
    }

    override suspend fun calendarWriteAccess(): UserPick<Unit> {
        val needed = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        if (needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) return UserPick.Picked(Unit)
        return when (requests.permissions(needed, CALENDAR_RATIONALE)) {
            null -> UserPick.NotOnScreen
            false -> UserPick.Cancelled
            true -> UserPick.Picked(Unit)
        }
    }

    override suspend fun insertContact(contact: NewContact): UserPick<Unit> {
        val intent = PhoneIntents.insertContact(contact)
        return withContext(Dispatchers.Main) {
            if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withContext UserPick.NotOnScreen
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                UserPick.Picked(Unit)
            } catch (_: android.content.ActivityNotFoundException) {
                UserPick.Cancelled
            }
        }
    }

    override suspend fun saveAs(file: java.io.File, name: String, mime: String): UserPick<String> {
        val result = requests.pick(PhoneIntents.saveAs(name, mime)) ?: return UserPick.NotOnScreen
        val uri = result.getOrNull() ?: return UserPick.Cancelled
        return withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return@withContext UserPick.Cancelled
                val shown = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
                UserPick.Picked(shown ?: name)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                UserPick.Cancelled
            }
        }
    }

    override suspend fun copy(text: String, label: String): Boolean = withContext(Dispatchers.Main) {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return@withContext false
        runCatching { clipboard.setPrimaryClip(PhoneIntents.sensitiveClip(label, text)) }.isSuccess
    }

    override suspend fun pickPhotos(max: Int, folder: String): UserPick<List<String>> {
        val uris = requests.photos(max) ?: return UserPick.NotOnScreen
        if (uris.isEmpty()) return UserPick.Cancelled
        val paths = uris.take(max).mapNotNull { uri ->
            try {
                workspace.importShared(uri, folder)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        return UserPick.Picked(paths)
    }
}

/** What Locus says before Android's calendar prompt (docs/design/phone-integrations.md). */
internal val CALENDAR_RATIONALE = PermissionRationale(
    title = "Let Locus change your calendar?",
    message = "Locus needs calendar access to add, change or delete events when you ask. It only writes to the one calendar " +
        "you pick in Settings → Phone, and each change shows you the event first. Events sync to that calendar's account.",
    blockedHint = "Calendar access is turned off for Locus. Allow it in App info → Permissions → Calendar.",
)
