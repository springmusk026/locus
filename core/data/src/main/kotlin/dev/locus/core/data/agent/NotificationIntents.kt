package dev.locus.core.data.agent

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import java.net.URLEncoder

/**
 * What tells one notification's button apart from another's. A PendingIntent is matched on its intent's action,
 * data and component, not its extras, and the request codes in [AgentService] are hashes that can collide
 * (`"Aa"` and `"BB"` hash alike). With the same intent, two notifications would share one PendingIntent and
 * the newer extras replace the older: Allow on one call would answer another. The call or chat id goes into
 * the data, which no receiver reads.
 */
internal object NotificationIntents {
    /** The data of an intent for [kind] and [parts] (a call id, an option). Each part is escaped, so two different sets never read alike. */
    fun uri(kind: String, vararg parts: String): String =
        "locus-notification://$kind/" + parts.joinToString("/") { URLEncoder.encode(it, "UTF-8") }

    fun data(kind: String, vararg parts: String): Uri = uri(kind, *parts).toUri()

    /**
     * [intent] made distinct for an activity PendingIntent: an identifier (Android 10+, ignored when the system
     * matches the intent to an activity), so the launcher intent keeps no data. Earlier versions keep the hash.
     */
    fun forActivity(intent: Intent, kind: String, id: String): Intent =
        if (Build.VERSION.SDK_INT >= 29) intent.setIdentifier(uri(kind, id)) else intent
}
