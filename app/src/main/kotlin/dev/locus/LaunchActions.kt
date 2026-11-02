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
}
