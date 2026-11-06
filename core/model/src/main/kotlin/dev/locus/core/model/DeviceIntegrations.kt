package dev.locus.core.model

/**
 * The phone integrations the agent can use as tools (docs/design/android-bridge.md and
 * docs/design/phone-integrations.md): each reads or writes something the user has, or lets the user
 * hand something over, and none of them operates another app. They ask for an Android permission only
 * when first used, and are offered only to chats that find tools on demand
 * (docs/design/tool-discovery.md), so no older chat's tool list changes.
 */
object DeviceIntegrations {
    /** Upcoming events from the phone's calendars (CalendarContract, READ_CALENDAR asked on first use). */
    const val CALENDAR_READ = "calendar_read"

    /** A contact the user picks in the system picker; Locus never holds READ_CONTACTS. */
    const val CONTACTS_PICK = "contacts_pick"

    /** Photos the user picks in the Photo Picker, copied into the workspace's inbox. */
    const val PHOTOS_PICK = "photos_pick"

    /** A new event in the one calendar the user lets Locus change (Settings → Phone). */
    const val CALENDAR_CREATE = "calendar_create"

    /** A change to an event in that calendar; a repeating event only as a whole series. */
    const val CALENDAR_UPDATE = "calendar_update"

    /** Deletes an event in that calendar; always confirmed. */
    const val CALENDAR_DELETE = "calendar_delete"

    /** A Locus notification at a time, with no model run when it fires. */
    const val REMIND_ME = "remind_me"

    /** The reminders still to come. */
    const val REMINDERS_LIST = "reminders_list"

    /** Cancels one reminder. */
    const val REMINDER_CANCEL = "reminder_cancel"

    /** Opens the Contacts app's "new contact" screen filled in; the user saves it there. No permission. */
    const val CONTACT_ADD = "device_contact_add"

    /** Saves a workspace file wherever the user picks in the system "Save as" picker. */
    const val SAVE_FILE = "device_save_file"

    /** Copies text (scrubbed of vault secrets, marked sensitive) to the clipboard. */
    const val COPY = "device_copy"

    /** The calendar tools that write. */
    val CALENDAR_WRITES: Set<String> = setOf(CALENDAR_CREATE, CALENDAR_UPDATE, CALENDAR_DELETE)

    val ALL: Set<String> = setOf(
        CALENDAR_READ, CONTACTS_PICK, PHOTOS_PICK,
        CALENDAR_CREATE, CALENDAR_UPDATE, CALENDAR_DELETE,
        REMIND_ME, REMINDERS_LIST, REMINDER_CANCEL,
        CONTACT_ADD, SAVE_FILE, COPY,
    )

    fun isIntegration(toolName: String): Boolean = toolName in ALL
}
