package dev.locus.core.model

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deleting and copying folder trees without ever following a symbolic link
 * (docs/design/linux-isolation.md, "Symbolic links Linux plants").
 *
 * Kotlin's `File.deleteRecursively` and `File.copyRecursively` walk with `File.isDirectory`, which
 * follows links: a link to a folder is entered, and a link to a file is copied as its contents. Linux
 * programs run as Locus's own uid and can put links anywhere they can write (the rootfs, `/tmp`,
 * `/workspace`), and proot stores a link's target exactly as given, so a link can name any host path,
 * such as `/data/data/dev.locus/databases`. Deleting such a tree with `deleteRecursively` deletes
 * Locus's own data; copying it with `copyRecursively` copies Locus's private files into a folder
 * Linux can read. Every tree Linux can write goes through here instead.
 */
object FileTrees {
    /** Whether [file] is itself a symbolic link (not whether its target exists). */
    fun isLink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

    /** Whether [file] exists as an entry, a dangling link included. */
    fun exists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    /**
     * Deletes [root] and everything below it. A link is removed as a link; nothing it points at is
     * entered or touched. True when nothing is left at [root] (also when there was nothing there).
     *
     * Not race-free against a program still running in the tree: one that swaps a folder for a link
     * between the walk's look at it and its listing gets the link's target emptied (Java has no
     * `unlinkat`/`openat`). Delete trees Linux can no longer name (the trash), or with Linux stopped.
     */
    fun delete(root: File): Boolean {
        val path = root.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        runCatching {
            // walkFileTree without FOLLOW_LINKS reports a link as a file, even a link to a folder.
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    runCatching { Files.delete(file) }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    runCatching { Files.delete(file) }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    runCatching { Files.delete(dir) }
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    }

    /**
     * The bytes of the regular files at or below [root], never following a link: a link counts as
     * nothing and nothing it points at is entered, so a link Linux planted (`/workspace/x ->
     * /data/data/dev.locus`) can't make a folder look bigger or make the walk leave it. Unreadable
     * entries are skipped. [onFile] runs before each entry is counted; a caller stops a long walk by
     * throwing from it (a coroutine's `ensureActive`). 0 when nothing is at [root].
     */
    fun size(root: File, onFile: () -> Unit = {}): Long {
        val path = root.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return 0
        var total = 0L
        // walkFileTree without FOLLOW_LINKS reports a link as a file (even a link to a folder), with its own attributes.
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                onFile()
                if (attrs.isRegularFile) total += attrs.size()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return total
    }

    /**
     * Writes [bytes] to [relative] (a `/`-separated path) below [root] without following a link, for
     * files Locus manages inside a tree Linux can write (the rootfs's `/etc/resolv.conf`). No folder on
     * the way may be a link, and whatever is at the name (a link included) is replaced by a new regular
     * file through a rename, so the bytes never land outside [root]. Missing folders are created.
     * Throws [IOException] when a folder on the way is a link or not a folder, or the write fails.
     *
     * A same-uid program could still swap a folder for a link between the check and the write; Java
     * has no `openat(O_NOFOLLOW)`. The final name, the usual target, has no such window.
     */
    fun replaceFile(root: File, relative: String, bytes: ByteArray) {
        val parts = relative.split('/').filter { it.isNotEmpty() }
        require(parts.isNotEmpty() && parts.none { it == "." || it == ".." }) { "Not a plain relative path: $relative" }
        var dir = root.toPath()
        if (Files.isSymbolicLink(dir)) throw IOException("$root is a link")
        for (part in parts.dropLast(1)) {
            dir = dir.resolve(part)
            when {
                Files.isSymbolicLink(dir) -> throw IOException("$dir is a link")
                !Files.exists(dir, LinkOption.NOFOLLOW_LINKS) -> Files.createDirectory(dir)
                !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) -> throw IOException("$dir isn't a folder")
            }
        }
        val target = dir.resolve(parts.last())
        val temp = dir.resolve(".${parts.last()}.locus-${System.nanoTime()}-${(0..0xffff).random()}")
        try {
            // O_CREAT|O_EXCL|O_NOFOLLOW: it fails rather than follow a link planted at the temporary name, and
            // NoFollowFiles checks where the new file really is (a folder swapped for a link since the
            // check above) before writing a byte.
            NoFollowFiles.create(root, temp, bytes)
            // rename(2) replaces the entry at the name, a link included; it never writes through one. A
            // folder swapped for a link now makes it fail (the temporary file isn't at the link's target).
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(temp) }
            throw e
        }
    }

    /**
     * Writes [text] to [file], which lies below [root] (the workspace) in a folder Linux can write
     * (`/workspace/.locus/out`), replacing whatever is at that name, a link included, instead of
     * writing through it. Every folder from [root] down is checked as in [replaceFile], so a folder
     * Linux swapped for a link while a command ran (`.locus -> /data/data/dev.locus/...`) is refused
     * rather than written into. Missing folders are created. Throws [IOException] when [file] isn't
     * below [root] or a folder on the way is a link.
     */
    fun writeReplacing(root: File, file: File, text: String) {
        val base = root.absoluteFile.toPath().normalize()
        val path = file.absoluteFile.toPath().normalize()
        if (path == base || !path.startsWith(base)) throw IOException("$file isn't inside $root")
        replaceFile(root, base.relativize(path).joinToString("/"), text.toByteArray())
    }

    /**
     * Copies [from] (a file, a folder, or a link) to [to]. Links are copied as links, with the same
     * target text; nothing they point at is read. Folders are merged into an existing folder at [to].
     * An existing file at a destination is replaced when [overwrite], otherwise this throws
     * [FileAlreadyExistsException]. Throws [IOException] when something can't be copied.
     *
     * A file is opened with `O_NOFOLLOW`, but a *folder* a running program swaps for a link between
     * the walk's look at it and its listing is still entered (no `openat` in Java).
     */
    fun copy(from: File, to: File, overwrite: Boolean = false) {
        val source = from.toPath()
        val target = to.toPath()
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw java.nio.file.NoSuchFileException(from.path)
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val dest = target.resolve(source.relativize(dir).toString())
                if (Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS)) return FileVisitResult.CONTINUE
                if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
                    if (!overwrite) throw FileAlreadyExistsException(dest.toString())
                    Files.delete(dest)
                }
                Files.createDirectories(dest)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val dest = target.resolve(source.relativize(file).toString())
                if (Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS)) {
                    if (!overwrite) throw FileAlreadyExistsException(dest.toString())
                    if (!delete(dest.toFile())) throw IOException("Couldn't replace $dest")
                }
                if (overwrite) Files.deleteIfExists(dest) // Removes a link at the name; never follows it.
                if (attrs.isSymbolicLink || !attrs.isRegularFile) {
                    // With NOFOLLOW_LINKS a link is copied as a link, never as its target's contents.
                    Files.copy(file, dest, LinkOption.NOFOLLOW_LINKS)
                } else {
                    // Files.copy opens a regular source without O_NOFOLLOW, so a file Linux swaps for a
                    // link after the walk saw it would be read through the link. Open it with
                    // NOFOLLOW_LINKS (O_NOFOLLOW) instead, and create the copy with CREATE_NEW (O_EXCL).
                    Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
                        Files.newOutputStream(dest, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { input.copyTo(it) }
                    }
                    // Keep the executable bit (scripts in a repository); a failure only loses the mode.
                    runCatching { Files.setPosixFilePermissions(dest, Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)) }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = throw exc
        })
    }
}
