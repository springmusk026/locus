package dev.locus

/**
 * Actions MainActivity takes from Locus's own ways in (docs/design/android-bridge.md): the static
 * launcher shortcuts (res/xml/shortcuts.xml, which repeats these strings) and the quick settings tile.
 * They only open screens; none of them sends or runs anything.
 */
object LaunchActions {
    const val NEW_CHAT = "dev.locus.action.NEW_CHAT"
    const val VOICE = "dev.locus.action.VOICE"
    const val WORKSPACE = "dev.locus.action.WORKSPACE"

    /** Home with the ask bar focused (the tile). */
    const val ASK = "dev.locus.action.ASK"

    val ALL = setOf(NEW_CHAT, VOICE, WORKSPACE, ASK)

    /**
     * The action to take for an intent's [action]: Android's assist gesture (Locus as the default digital
     * assistant, the AssistLocus alias) opens the ask bar like the tile. Its extras are never read.
     */
    fun resolve(action: String?): String? = when (action) {
        android.content.Intent.ACTION_ASSIST -> ASK
        in ALL -> action
        else -> null
    }
}
