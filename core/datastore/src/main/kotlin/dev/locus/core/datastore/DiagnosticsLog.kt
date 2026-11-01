package dev.locus.core.datastore

import android.content.Context
import java.io.File

/**
 * Locus's local crash and problem log: one small text file per entry in app-private, no-backup storage.
 * Nothing here leaves the phone unless the user shares it (Settings → About & privacy → Diagnostics).
 *
 * Writes are synchronous and plain file I/O, so the log works from an uncaught-exception handler,
 * before Hilt exists, and from DataStore's corruption handlers. Callers scrub what they record
 * (the crash recorder runs SecretScrubber's patterns); Diagnostics scrubs again with the vault's
 * values before showing or sharing anything.
 *
 * Also holds notices the user must see once, such as "your saved keys need re-entering".
 */
class DiagnosticsLog(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {

    enum class Kind { CRASH, EXIT, ERROR, STORAGE }

    data class Entry(val id: String, val kind: Kind, val at: Long, val title: String, val text: String)

    /** Records one entry; never throws (a log that crashes the app would defeat its purpose). */
    fun record(kind: Kind, title: String, text: String) {
        runCatching {
            synchronized(LOCK) {
                dir.mkdirs()
                val at = clock()
                var file = File(dir, "$at-${kind.name.lowercase()}$SUFFIX")
                var n = 1
                while (file.exists()) file = File(dir, "$at-${kind.name.lowercase()}-${n++}$SUFFIX")
                file.writeText(title.lineSequence().first().take(200) + "\n" + text.take(MAX_ENTRY_CHARS))
                prune()
            }
        }
    }

    /** Every entry, newest first. */
    fun entries(): List<Entry> = synchronized(LOCK) {
        files().mapNotNull { file ->
            val parts = file.name.removeSuffix(SUFFIX).split('-')
            val at = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val kind = Kind.entries.firstOrNull { it.name.lowercase() == parts.getOrNull(1) } ?: return@mapNotNull null
            val content = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            Entry(file.name, kind, at, content.substringBefore('\n'), content.substringAfter('\n', ""))
        }.sortedByDescending { it.at }
    }

    /** Deletes every entry (notices are kept until dismissed). */
    fun clear() = synchronized(LOCK) { files().forEach { it.delete() } }

    /** A message to show the user once, at the next chance; kept until [dismissNotices]. */
    fun addNotice(text: String) {
        runCatching { synchronized(LOCK) { dir.mkdirs(); noticeFile().appendText(text.replace('\n', ' ') + "\n") } }
    }

    fun notices(): List<String> = synchronized(LOCK) {
        runCatching { noticeFile().readLines().filter { it.isNotBlank() }.distinct() }.getOrDefault(emptyList())
    }

    fun dismissNotices() = synchronized(LOCK) { noticeFile().delete(); Unit }

    /** A small value kept beside the log (the last exit-history check). */
    fun readMark(name: String): Long? = synchronized(LOCK) { runCatching { File(dir, "$name.mark").readText().trim().toLong() }.getOrNull() }

    fun writeMark(name: String, value: Long) {
        runCatching { synchronized(LOCK) { dir.mkdirs(); File(dir, "$name.mark").writeText(value.toString()) } }
    }

    private fun files(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }?.toList().orEmpty()

    private fun noticeFile() = File(dir, "notices.txt")

    private fun prune() {
        files().sortedByDescending { it.name.substringBefore('-').toLongOrNull() ?: 0 }.drop(MAX_ENTRIES).forEach { it.delete() }
    }

    companion object {
        private const val SUFFIX = ".log"
        private const val MAX_ENTRIES = 30
        private const val MAX_ENTRY_CHARS = 32_000
        private val LOCK = Any()

        fun forApp(context: Context) = DiagnosticsLog(File(context.noBackupFilesDir, "diagnostics"))
    }
}
