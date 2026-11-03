package dev.locus.feature.library

import dev.locus.core.data.repository.ConnectorEntry
import dev.locus.core.data.repository.KeyState

/**
 * What a remote connector's card says about its key or sign-in. A draft never reads "Signed in" or
 * "Saved": a key that exists was saved for something the user already set up, so the card says
 * whose it is and where it goes (docs/design/mcp-connectors.md, "Keys belong to an address").
 */
internal fun connectorKeyNote(entry: ConnectorEntry): String? {
    val c = entry.connector
    val key = entry.key
    if (c.auth.type == "none") return null
    if (c.auth.type == "oauth") {
        return when {
            key is KeyState.Elsewhere -> "The saved sign-in is for ${key.where.joinToString()}, not ${c.host}, so Locus won't use it here. Sign in again" +
                if (entry.draft) " after approving." else "."
            key != KeyState.Ready -> "This server needs you to sign in with your account in the browser" + if (entry.draft) " after approving." else "."
            entry.draft -> "Would use the sign-in you already made for ${c.host}. Approve only if you meant to set this server up again."
            else -> "Signed in. Locus keeps the sign-in encrypted on this phone and renews it automatically."
        }
    }
    val how = if (c.auth.type == "bearer") "a bearer token" else "${c.auth.type} ${c.auth.name}"
    val shared = entry.sharedWith.takeIf { it.isNotEmpty() }?.let { " It's also ${it.joinToString()}'s key." }.orEmpty()
    return "Key “${c.auth.secret}”, sent to ${c.host} as $how. " + when (key) {
        KeyState.Missing -> "Not added yet."
        KeyState.Unconfirmed -> "A key with this name is saved, but from before Locus recorded where each key goes, so it isn't sent until you confirm it's for ${c.host}.$shared"
        is KeyState.Elsewhere -> "A key with this name is saved for ${key.where.joinToString()}, so Locus won't send it here. Add a key for this connector."
        KeyState.Ready -> if (entry.draft) "Would use the key you already saved for ${c.host}.$shared" else "Saved.$shared"
    }
}
