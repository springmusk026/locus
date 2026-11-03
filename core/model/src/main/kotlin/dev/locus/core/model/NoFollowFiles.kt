package dev.locus.core.model

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Opens a file for writing without following a link at its name (`O_NOFOLLOW`), and says where the
 * opened descriptor really is. The JVM version ([JvmNoFollowOpener]) can't see the descriptor's path;
 * the app installs one built on `android.system.Os` that can (`/proc/self/fd`), which is what closes
 * the race with a folder swapped for a link ([NoFollowFiles]).
 */
interface NoFollowOpener {
    /**
     * Opens [path] for writing with `O_NOFOLLOW`: a new file with `O_CREAT|O_EXCL` when [create], else
     * an existing one, without `O_CREAT`. Nothing is truncated yet. Throws [IOException] when [path] is a
     * link (`ELOOP`), already exists although [create] ([FileAlreadyExistsException]), or can't be opened.
     */
    fun open(path: Path, create: Boolean): Opened

    /** The path the kernel reports for the folder [dir] (opened without following a link), or null when it can't tell. */
    fun kernelPath(dir: Path): String?

    interface Opened : AutoCloseable {
        /** Where the opened file really is, as the kernel reports it; null when the platform can't tell. */
        val realPath: String?

        /** Truncates the file and writes [bytes] through the descriptor. */
        fun replaceContent(bytes: ByteArray)

        /**
         * Truncates the file and returns a stream that writes through the descriptor, for content too
         * large to hold (downloads, imports). Closing the stream doesn't close the descriptor. The
         * default collects the bytes and hands them to [replaceContent] when the stream is closed.
         */
        fun stream(): OutputStream = object : ByteArrayOutputStream() {
            override fun close() = replaceContent(toByteArray())
        }
    }
}

/** `java.nio`'s `NOFOLLOW_LINKS`, which `UnixChannelFactory` turns into `O_NOFOLLOW`. It can't see where a descriptor is. */
object JvmNoFollowOpener : NoFollowOpener {
    override fun open(path: Path, create: Boolean): NoFollowOpener.Opened {
        val options = mutableSetOf<java.nio.file.OpenOption>(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        if (create) options += StandardOpenOption.CREATE_NEW
        val channel = FileChannel.open(path, options)
        return object : NoFollowOpener.Opened {
            override val realPath: String? = null

            override fun replaceContent(bytes: ByteArray) {
                channel.truncate(0)
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
            }

            override fun stream(): OutputStream {
                channel.truncate(0)
                return object : OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        val buffer = ByteBuffer.wrap(b, off, len)
                        while (buffer.hasRemaining()) channel.write(buffer)
                    }
                }
            }

            override fun close() = channel.close()
        }
    }

    override fun kernelPath(dir: Path): String? = null
}

/**
 * Writes files Locus manages inside a tree Linux can write (`/workspace`) without following a link
 * a Linux program planted there (docs/design/linux-isolation.md, "Closing the write race").
 *
 * 1. Every folder from the root down is checked with `lstat` (none may be a link), and missing ones
 *    are made with `mkdir`, which never follows a link at its name.
 * 2. The file is opened with `O_NOFOLLOW` ([NoFollowOpener]): a link at the file's own name is refused
 *    by the kernel, so the last component has no race at all. A new file is opened with `O_EXCL`.
 * 3. Before a byte is written or anything truncated, the opened descriptor's real path
 *    (`/proc/self/fd/<n>` on Android) must be the intended file. A folder swapped for a link between
 *    the check and the open is caught here: nothing is written, and a file the open created at the
 *    link's target is removed again. Where the platform can't tell the path (the JVM in tests), the
 *    folders are checked once more instead, which narrows the window but doesn't close it.
 *
 * What stays open: a folder created in step 1 through a folder swapped at that moment lands at the
 * link's target, empty (no file is written into it).
 */
object NoFollowFiles {
    /** The platform's opener; the app installs the `android.system.Os` one at startup. */
    @Volatile var opener: NoFollowOpener = JvmNoFollowOpener

    /** Thrown when a link stands where Locus would write, or the file moved while it was opened. */
    class LinkException(message: String) : IOException(message)

    /**
     * Writes [bytes] to [file], which lies below [root], replacing its contents in place. [root]'s
     * parent must be a folder of Locus's own that Linux can't name; [root] itself may be one Linux can
     * replace. Missing folders are created.
     */
    fun write(root: File, file: File, bytes: ByteArray, opener: NoFollowOpener = this.opener) {
        openChecked(root, file, opener).use { it.replaceContent(bytes) }
    }

    /**
     * Like [write], for content streamed into [file] by [block] (a download, an import): the same
     * checks run before the file is truncated and the first byte is written. A partly written file is
     * left as it is when [block] throws; the caller removes it.
     */
    inline fun <T> writeStream(root: File, file: File, opener: NoFollowOpener = this.opener, block: (OutputStream) -> T): T =
        openChecked(root, file, opener).use { opened -> opened.stream().use(block) }

    /** Opens [file] below [root] for [write] and [writeStream], checked as they describe; nothing is truncated yet. */
    @PublishedApi
    internal fun openChecked(root: File, file: File, opener: NoFollowOpener): NoFollowOpener.Opened {
        val base = root.absoluteFile.toPath().normalize()
        val target = file.absoluteFile.toPath().normalize()
        if (target == base || !target.startsWith(base)) throw IOException("$file isn't inside $root")
        folders(base, target.parent, create = true)
        if (Files.isSymbolicLink(target)) throw LinkException("${base.relativize(target)} is a link; Locus won't write through it.")
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw IOException("${base.relativize(target)} is a folder.")
        var created = !Files.exists(target, LinkOption.NOFOLLOW_LINKS)
        val opened = try {
            opener.open(target, create = created)
        } catch (e: FileAlreadyExistsException) {
            // Something appeared at the name since the look above; open it as existing (a link is still refused).
            created = false
            opener.open(target, create = false)
        } catch (e: IOException) {
            throw if (Files.isSymbolicLink(target)) LinkException("${base.relativize(target)} is a link; Locus won't write through it.") else e
        }
        try {
            verify(base, target, opened, created, opener)
        } catch (e: Throwable) {
            opened.close()
            throw e
        }
        return opened
    }

    /**
     * Opens a new file at [target] below [root] with `O_CREAT|O_EXCL|O_NOFOLLOW`, checks where it really
     * is as [write] does, and writes [bytes]. For a temporary name that is then renamed over the real
     * one ([FileTrees.replaceFile]). Throws [FileAlreadyExistsException] when the name is taken.
     */
    fun create(root: File, target: Path, bytes: ByteArray, opener: NoFollowOpener = this.opener) {
        val base = root.absoluteFile.toPath().normalize()
        opener.open(target, create = true).use {
            verify(base, target, it, created = true, opener)
            it.replaceContent(bytes)
        }
    }

    /**
     * The anchor is [base]'s parent: a folder of Locus's own that Linux can't name (`files/` for the
     * workspace, `files/linux/` for the rootfs), so no link can stand in it. The kernel's path of the
     * opened file must be the anchor's kernel path plus exactly the intended components; a link
     * anywhere below the anchor, [base] included, makes them differ. Both paths come from the kernel,
     * so a folder reached under another name (`/data/data` and `/data/user/0`) still compares equal.
     */
    private fun verify(base: Path, target: Path, opened: NoFollowOpener.Opened, created: Boolean, opener: NoFollowOpener) {
        val real = opened.realPath
        val anchor = base.parent
        val anchorPath = anchor?.let(opener::kernelPath)
        if (real != null) {
            if (anchorPath != null && real == anchorPath.trimEnd('/') + "/" + anchor.relativize(target).joinToString("/")) return
            // The platform can tell where the file is, so an anchor it can't open (itself a link, or gone)
            // is refused too, rather than falling back to the weaker check below.
            if (created) runCatching { Files.deleteIfExists(Paths.get(real)) }
            throw LinkException("A folder on the way to ${base.relativize(target)} was replaced with a link while Locus wrote it; nothing was written.")
        }
        // Without the descriptor's path, check the folders again: narrows the window, doesn't close it.
        folders(base, target.parent, create = false)
    }

    /** Checks each folder from [base] down to [dir]: none may be a link; missing ones are made when [create]. */
    private fun folders(base: Path, dir: Path?, create: Boolean) {
        if (dir == null || !dir.startsWith(base)) throw IOException("$dir isn't inside $base")
        var at = base
        for (part in base.relativize(dir)) {
            if (part.toString().isEmpty()) continue
            at = at.resolve(part)
            when {
                Files.isSymbolicLink(at) -> throw LinkException("${base.relativize(at)} is a link; Locus won't write through it.")
                !Files.exists(at, LinkOption.NOFOLLOW_LINKS) -> {
                    if (!create) throw IOException("${base.relativize(at)} was removed while Locus wrote into it.")
                    // mkdir(2) fails on an existing name, a link included; it never follows one.
                    runCatching { Files.createDirectory(at) }
                    if (Files.isSymbolicLink(at) || !Files.isDirectory(at, LinkOption.NOFOLLOW_LINKS)) {
                        throw LinkException("${base.relativize(at)} is a link; Locus won't write through it.")
                    }
                }
                !Files.isDirectory(at, LinkOption.NOFOLLOW_LINKS) -> throw IOException("${base.relativize(at)} isn't a folder.")
            }
        }
    }
}
