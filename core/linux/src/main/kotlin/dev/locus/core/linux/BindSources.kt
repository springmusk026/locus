package dev.locus.core.linux

import dev.locus.core.model.FileTrees
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Checks for the host folders and files Locus binds into Linux (`/tmp`, `/workspace`, the fake /proc
 * files, the shadow folder). proot resolves links in a binding's host path (`realpath2` in
 * `new_binding`, src/path/binding.c), and each of these is writable from inside Linux at its bind
 * point, so a program can delete one and leave a link to any host path in its place
 * (`rmdir /tmp; ln -s /data/data/dev.locus /tmp`). The next proot would then bind Locus's own data
 * into Linux. See docs/design/linux-isolation.md, "Bind points".
 */
internal object BindSources {
    /**
     * Makes [dir] a real folder: a link at its name (whatever it points at) is removed, never
     * followed, and the folder is created. [dir]'s parent must be one Linux can't name.
     */
    fun realDir(dir: File): File {
        val path = dir.toPath()
        if (Files.isSymbolicLink(path) || (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))) {
            FileTrees.delete(dir)
        }
        dir.mkdirs()
        return dir
    }

    /**
     * [dir] with links resolved, when that is [root] or inside it; links that stay inside (a project
     * folder Linux linked to another folder of the workspace) are fine. Throws [IllegalStateException]
     * when it leads outside, or through a link that leads nowhere. [root] itself must not be a link.
     */
    fun inside(root: File, dir: File): File {
        if (FileTrees.isLink(root)) throw IllegalStateException("The workspace folder is a link Linux made; Locus won't give it to Linux.")
        val realRoot = root.canonicalFile
        val real = dir.canonicalFile
        if (real != realRoot && !real.path.startsWith(realRoot.path + File.separator)) {
            throw IllegalStateException("This project's folder leads outside the workspace (a link Linux made?), so Locus won't give it to Linux.")
        }
        // Whatever canonicalisation couldn't resolve and is still a link is a dangling one: mkdirs and
        // proot would follow it to wherever it points once that exists.
        var at: File? = real
        while (at != null && at != realRoot) {
            if (FileTrees.isLink(at)) throw IllegalStateException("This project's folder goes through a broken link, so Locus won't give it to Linux.")
            at = at.parentFile
        }
        return real
    }

    /** Whether [file] is a regular file itself, not a link to one. */
    fun isPlainFile(file: File): Boolean = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
}
