package dev.locus.core.agent.tools

import java.io.File

/**
 * An existing text file the write and edit tools change: a workspace file ([FileEdit]) or one in a
 * connected folder ([ConnectedEdit]). Each keeps its earlier version in the trash before a change.
 */
internal sealed interface EditTarget {
    /** Its path as the agent sees it. */
    val display: String

    /** Its path as change records keep it (the whole workspace's, or `folders/<name>/…`). */
    val stored: String

    fun state(chat: String): FileReads.State

    fun record(chat: String)

    fun readText(): String

    fun backup(): TrashEntry

    fun write(text: String)
}

internal class FileEdit(private val ws: Workspace, private val reads: FileReads, val file: File) : EditTarget {
    override val display get() = ws.relative(file)
    override val stored get() = ws.stored(file)
    override fun state(chat: String) = reads.state(chat, file)
    override fun record(chat: String) = reads.record(chat, file)
    override fun readText() = file.readText()
    override fun backup() = ws.backup(file, TrashEntry.Reason.OVERWRITTEN)
    override fun write(text: String) = ws.writeText(file, text)
}

internal class ConnectedEdit(private val c: ConnectedFiles, private val reads: FileReads, val target: ConnectedFiles.Target) : EditTarget {
    /** The bytes last read or written, so recording the read doesn't read the file again. */
    private var last: ByteArray? = null

    override val display get() = target.path
    override val stored get() = target.path
    override fun state(chat: String) = c.readState(reads, chat, target)
    override fun record(chat: String) = c.recordRead(reads, chat, target, last ?: c.readBytes(target))
    override fun readText() = c.readBytes(target).also { last = it }.decodeToString()
    override fun backup() = c.backup(target, TrashEntry.Reason.OVERWRITTEN)
    override fun write(text: String) {
        val bytes = text.toByteArray()
        c.requireWritable(target)
        c.writeFile(target, bytes)
        last = bytes
    }
}
