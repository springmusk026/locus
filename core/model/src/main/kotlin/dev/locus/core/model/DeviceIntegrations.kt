package dev.locus.core.model

/**
 * The phone integrations the agent can use as tools (docs/design/android-bridge.md): each reads
 * something the user has, or lets the user hand something over, and none of them operates another app.
 * They are Ask by default, ask for an Android permission only when first used, and are offered only to
 * chats that find tools on demand (docs/design/tool-discovery.md), so no older chat's tool list changes.
 */
object DeviceIntegrations {
    /** Upcoming events from the phone's calendars (CalendarContract, READ_CALENDAR asked on first use). */
    const val CALENDAR_READ = "calendar_read"

    /** A contact the user picks in the system picker; Locus never holds READ_CONTACTS. */
    const val CONTACTS_PICK = "contacts_pick"

    /** Photos the user picks in the Photo Picker, copied into the workspace's inbox. */
    const val PHOTOS_PICK = "photos_pick"

    val ALL: Set<String> = setOf(CALENDAR_READ, CONTACTS_PICK, PHOTOS_PICK)

    fun isIntegration(toolName: String): Boolean = toolName in ALL
}
