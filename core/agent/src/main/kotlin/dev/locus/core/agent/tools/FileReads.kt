package dev.locus.core.agent.tools

import java.io.File
import java.security.MessageDigest

/**
 * Which files each chat has read, and the version it read, so the edit tools can refuse to change a
 * file the chat hasn't looked at (Claude Code's read-before-edit rule). Kept in memory only, keyed by
 * the chat (or helper) id from [dev.locus.core.agent.ToolCallContext]: after Locus restarts a chat
 * reads a file again before changing it, which costs one call and never loses work.
 *
 * Reading any part of a file with read_file counts, as do the chat's own writes and edits.
 */
class FileReads(private val maxChats: Int = 64, private val maxFilesPerChat: Int = 4_096) {
    /** What the chat last saw of a file: its size and time, and a hash to tell a touch from a change. */
    private data class Stamp(val modified: Long, val length: Long, val sha256: String)

    // Access-ordered, so the least recently used chat is dropped first.
    private val chats = object : LinkedHashMap<String, LinkedHashMap<String, Stamp>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkedHashMap<String, Stamp>>) = size > maxChats
    }

    /** The state of [file] for [chat]'s purposes. */
    enum class State { NEW_FILE, READ, NOT_READ, CHANGED }

    /** Records that [chat] has seen [file] as it is now on disk. */
    @Synchronized
    fun record(chat: String, file: File) {
        if (!file.isFile) return
        val files = chats.getOrPut(chat) {
            object : LinkedHashMap<String, Stamp>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Stamp>) = size > maxFilesPerChat
            }
        }
        files[file.canonicalPath] = stamp(file)
    }

    @Synchronized
    fun state(chat: String, file: File): State {
        if (!file.exists()) return State.NEW_FILE
        val seen = chats[chat]?.get(file.canonicalPath) ?: return State.NOT_READ
        if (file.lastModified() == seen.modified && file.length() == seen.length) return State.READ
        // Touched but not changed (a formatter that left it alone, a `touch`) still counts as read.
        val now = stamp(file)
        if (now.sha256 != seen.sha256) return State.CHANGED
        chats[chat]?.put(file.canonicalPath, now)
        return State.READ
    }

    /** Forgets everything [chat] read, e.g. when the chat is deleted. */
    @Synchronized
    fun forget(chat: String) {
        chats.remove(chat)
    }

    private fun stamp(file: File): Stamp {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return Stamp(file.lastModified(), file.length(), digest.digest().joinToString("") { "%02x".format(it) })
    }
}
