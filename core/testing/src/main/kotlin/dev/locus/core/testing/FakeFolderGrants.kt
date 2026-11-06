package dev.locus.core.testing

import dev.locus.core.agent.tools.DocEntry
import dev.locus.core.agent.tools.DocTree
import dev.locus.core.agent.tools.DocTreeException
import dev.locus.core.agent.tools.DocTreeException.Problem
import dev.locus.core.data.folders.FolderGrants
import dev.locus.core.data.folders.PickedFolder
import java.io.File
import java.io.InputStream

/**
 * Connected folders over plain directories: a "tree URI" is `file://` plus a directory's path. Grants
 * can be revoked ([revoke]) and a provider made read-only ([readOnlyProvider]) to test those states.
 */
class FakeFolderGrants : FolderGrants {
    val held = mutableSetOf<String>()
    val readOnlyProvider = mutableSetOf<String>()

    fun uriOf(dir: File): String = "file://" + dir.absolutePath

    fun revoke(uri: String) {
        held -= uri
    }

    override fun take(uri: String): Boolean {
        held += uri
        return uri !in readOnlyProvider
    }

    override fun release(uri: String) {
        held -= uri
    }

    override fun holds(uri: String) = uri in held

    override fun describe(uri: String): PickedFolder {
        val dir = File(uri.removePrefix("file://"))
        return PickedFolder(uri, dir.name, "Phone storage › Documents/${dir.name}", writable = uri !in readOnlyProvider)
    }

    override fun tree(uri: String): DocTree = DirectoryTree(File(uri.removePrefix("file://")), { uri in held }, { uri !in readOnlyProvider })
}

/** A [DocTree] over a directory, for tests and screenshots. */
class DirectoryTree(private val root: File, private val granted: () -> Boolean = { true }, private val writable: () -> Boolean = { true }) : DocTree {
    private fun at(path: List<String>): File {
        if (!granted()) throw DocTreeException(Problem.PERMISSION_LOST, "no grant")
        if (!root.isDirectory) throw DocTreeException(Problem.GONE, "gone")
        val f = path.fold(root) { d, p -> File(d, p) }
        if (!f.exists()) throw DocTreeException(Problem.NOT_FOUND, "missing")
        return f
    }

    private fun entry(f: File) = DocEntry(f.name, f.isDirectory, if (f.isFile) f.length() else 0, f.lastModified(), writable(), writable(), writable(), writable())

    private fun mutable() {
        if (!writable()) throw DocTreeException(Problem.UNSUPPORTED, "read-only provider")
    }

    override fun root(): DocEntry = entry(at(emptyList()))
    override fun list(path: List<String>) = at(path).listFiles().orEmpty().map(::entry)
    override fun open(path: List<String>): InputStream = at(path).inputStream()
    override fun write(path: List<String>, bytes: ByteArray) {
        mutable()
        at(path).writeBytes(bytes)
    }
    override fun createFile(parent: List<String>, name: String): String {
        mutable()
        File(at(parent), name).createNewFile()
        return name
    }
    override fun createDirectory(parent: List<String>, name: String): String {
        mutable()
        File(at(parent), name).mkdir()
        return name
    }
    override fun delete(path: List<String>) {
        mutable()
        at(path).deleteRecursively()
    }
    override fun rename(path: List<String>, newName: String): String {
        mutable()
        val f = at(path)
        f.renameTo(File(f.parentFile, newName))
        return newName
    }
}
