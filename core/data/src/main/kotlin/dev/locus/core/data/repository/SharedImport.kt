package dev.locus.core.data.repository

/**
 * What another app may hand Locus through "Share". Shared files are copied with Locus's own
 * permissions, so a `file://` URI would let any app make Locus copy its private files (the database,
 * the vault's keyset) into the workspace, where the agent and the Files app can read them. Only
 * `content://` URIs from other apps' providers are taken; Locus's own providers are refused too.
 */
object SharedImport {
    /** The largest file a share may copy into the workspace. */
    const val MAX_BYTES: Long = 200L * 1024 * 1024

    /** Why [scheme]/[authority] can't be imported, or null when it can. [ownPackage] is Locus's application id. */
    fun refusal(scheme: String?, authority: String?, ownPackage: String): String? = when {
        !scheme.equals("content", ignoreCase = true) -> "only files shared from another app can be imported"
        authority.isNullOrBlank() -> "the link has no source app"
        authority.split(';').any { isOwn(it.trim(), ownPackage) } -> "it points into Locus itself"
        else -> null
    }

    /** Fails once more than [MAX_BYTES] have been copied. */
    fun checkSize(copied: Long, max: Long = MAX_BYTES) {
        if (copied > max) throw SharedImportException("it's larger than ${max / (1024 * 1024)} MB")
    }

    private fun isOwn(authority: String, ownPackage: String): Boolean {
        val a = authority.substringAfterLast('@').lowercase() // content://user@authority
        val own = ownPackage.lowercase()
        return a == own || a.startsWith("$own.")
    }
}

class SharedImportException(message: String) : Exception(message)
