package dev.locus.core.agent

/**
 * Which shell commands may be remembered as a prefix rule ("Always allow `./gradlew test`"), and
 * whether a command matches one (docs/design/remembered-approvals.md). Deliberately narrow:
 *
 * - Only a single plain command is ever remembered or matched. Anything the shell would treat as more
 *   than one command or expand at run time (`;`, `&`, `|`, `` ` ``, `$`, redirects, subshells, braces,
 *   backslashes, newlines, a leading `VAR=value`) is opaque, as in Codex's rules and Claude Code's
 *   compound-command handling, so `curl … | sh` and `ok && rm -rf ~` can never ride on a rule.
 * - A small denylist of commands is never remembered, whatever their arguments: privilege changes,
 *   recursive deletes, shells and inline code, network clients, process killing and history rewrites.
 * - No argument may point outside the workspace: no absolute path, `~` or `..`.
 *
 * The same checks run when a rule is matched, so a rule for `git` never covers `git push --force`.
 */
object CommandSafety {
    /** At most this many leading words make a prefix: `npm run test`, not the whole command line. */
    const val MAX_PREFIX_WORDS = 3

    private val SHELL_META = charArrayOf(';', '&', '|', '`', '$', '>', '<', '(', ')', '{', '}', '\\', '\n', '\r', '!')

    /** Never remembered, by the command's own name (the last part of its path). */
    private val NEVER = setOf(
        // Privilege and identity.
        "sudo", "su", "doas", "pkexec", "runuser", "chroot",
        // Deleting, wiping and overwriting.
        "rm", "shred", "dd", "truncate", "wipefs", "fdisk", "sfdisk", "parted", "mkswap", "srm",
        // Permissions and ownership, mounts.
        "chmod", "chown", "chgrp", "chattr", "setfacl", "mount", "umount", "losetup",
        // Shells, inline code and command runners: they run whatever they're given.
        "sh", "bash", "zsh", "dash", "ash", "ksh", "mksh", "fish", "csh", "tcsh", "busybox", "toybox",
        "eval", "exec", "source", ".", "env", "xargs", "nohup", "setsid", "nice", "ionice", "timeout", "time", "watch",
        "stdbuf", "command", "builtin", "script", "expect", "parallel",
        // Network clients: they can send the workspace anywhere.
        "curl", "wget", "nc", "ncat", "netcat", "socat", "telnet", "ftp", "tftp", "ssh", "scp", "sftp", "rsync", "aria2c",
        // Processes, services and the system.
        "kill", "pkill", "killall", "reboot", "shutdown", "poweroff", "halt", "init", "systemctl", "service", "rc-service",
        "crontab", "at", "batch",
        // Package managers that install system-wide.
        "apk", "apt", "apt-get", "dpkg", "rpm", "dnf", "yum", "pacman", "snap", "flatpak",
    )

    /** Interpreters: remembered only with a script named after them (`python3 build.py`), never alone or with `-c`. */
    private val INTERPRETERS = setOf(
        "python", "python2", "python3", "node", "nodejs", "deno", "bun", "perl", "ruby", "php", "lua", "luajit", "tclsh",
        "npx", "pnpx", "bunx", "uvx", "pipx", "java", "kotlin", "kotlinc", "go", "make", "awk", "gawk", "sed",
    )

    /** Subcommands never remembered, per command. */
    private val NEVER_SUBCOMMANDS = mapOf(
        "git" to setOf(
            "push", "reset", "clean", "config", "filter-branch", "filter-repo", "update-ref", "reflog", "gc", "prune",
            "rebase", "remote", "submodule", "credential", "daemon", "send-email", "archive", "bundle",
        ),
        "npm" to setOf("publish", "unpublish", "exec", "x", "login", "adduser", "token", "owner", "access", "deprecate", "config"),
        "pnpm" to setOf("publish", "exec", "dlx", "config"),
        "yarn" to setOf("publish", "dlx", "exec", "config", "npm"),
        "pip" to setOf("install", "uninstall", "download", "config"),
        "pip3" to setOf("install", "uninstall", "download", "config"),
        "cargo" to setOf("publish", "yank", "login", "owner", "install"),
        "gh" to setOf("api", "auth", "repo", "release", "secret", "gist", "pr", "issue", "workflow", "ssh-key", "gpg-key"),
        "docker" to setOf("run", "exec", "rm", "rmi", "system", "volume", "push", "login"),
        "find" to emptySet(),
        "uv" to setOf("run", "tool", "pip"),
        "gradle" to emptySet(),
    )

    /** Arguments that make an otherwise fine command dangerous. */
    private val DANGEROUS_ARGS = setOf(
        "-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprintf", "-fls",
        "--force", "-f", "--force-with-lease", "--hard", "--no-preserve-root", "--global", "-g", "--system",
        "--upload-pack", "--receive-pack", "--exec",
    )

    /**
     * Why [command] may never be remembered or matched by a rule, or null when it is one plain
     * command Locus is willing to remember a prefix of.
     */
    fun dangerReason(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return "an empty command"
        if (trimmed.any { it in SHELL_META }) return "it chains, pipes, redirects or expands (; & | \$ > < ` and the like)"
        val words = words(trimmed) ?: return "its quoting can't be read reliably"
        if (words.isEmpty()) return "an empty command"
        val first = words.first()
        if ('=' in first && !first.startsWith("-") && first.substringBefore('=').matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            return "it sets environment variables first"
        }
        words.firstOrNull { outside(it) }?.let { return "“$it” points outside the workspace" }
        val name = first.substringAfterLast('/')
        if (name in NEVER || name.startsWith("mkfs") || name.startsWith("python") && name.endsWith("-config")) {
            return "$name is never remembered"
        }
        val args = words.drop(1)
        args.firstOrNull { arg -> arg in DANGEROUS_ARGS || arg.startsWith("--force") || isShortFlagWith(arg, 'f') && name in FORCE_SENSITIVE }
            ?.let { return "“$it” makes it destructive or global" }
        val sub = args.firstOrNull { !it.startsWith("-") }
        NEVER_SUBCOMMANDS[name]?.let { banned -> if (sub != null && sub in banned) return "$name $sub is never remembered" }
        if (name in INTERPRETERS && (args.isEmpty() || args.first().startsWith("-"))) return "$name runs inline code or anything it is given"
        return null
    }

    /** Commands where a short `-f…` flag bundle (`-rf`, `-fd`) means force. */
    private val FORCE_SENSITIVE = setOf("git", "mv", "cp", "ln", "install", "rmdir", "unlink", "tar", "unzip", "zip")

    private fun isShortFlagWith(arg: String, c: Char) = arg.length > 1 && arg[0] == '-' && arg[1] != '-' && c in arg.substring(1)

    private fun outside(word: String): Boolean =
        word.startsWith("/") || word.startsWith("~") || word == ".." || word.startsWith("../") || word.contains("/../") ||
            word.endsWith("/..") || word.contains("=/") || word.contains("=~") || word.contains(":/") && !word.contains("://")

    /**
     * The prefix to offer for "Always allow" for [command]: its first words while they are plain
     * (`git status`, `./gradlew test`), at most [MAX_PREFIX_WORDS]. Null when the command is
     * dangerous, or the prefix would be only an interpreter (`python3` alone allows any script).
     */
    fun rememberablePrefix(command: String): String? {
        if (dangerReason(command) != null) return null
        val words = words(command.trim()) ?: return null
        val prefix = words.takeWhile { PLAIN.matches(it) }.take(MAX_PREFIX_WORDS)
        if (prefix.isEmpty()) return null
        val name = prefix.first().substringAfterLast('/')
        if (name in INTERPRETERS && prefix.size < 2) return null
        if (name in NEVER_SUBCOMMANDS && prefix.size < 2) return null
        return prefix.joinToString(" ")
    }

    /** Whether [command] is covered by a rule for [prefix]: word by word, and only if it isn't dangerous itself. */
    fun matches(prefix: String, command: String): Boolean {
        if (dangerReason(command) != null) return false
        val want = words(prefix.trim()) ?: return false
        val have = words(command.trim()) ?: return false
        if (want.isEmpty() || have.size < want.size) return false
        return want.indices.all { want[it] == have[it] }
    }

    /** A word the prefix may hold: letters, digits and `._/:+@=,-`, not starting with `-`. */
    private val PLAIN = Regex("[A-Za-z0-9._][A-Za-z0-9._/:+@=,-]*")

    /**
     * The command split into words the way `sh` would without expansion: whitespace separates, and
     * single or double quotes group. Null when a quote isn't closed.
     */
    internal fun words(command: String): List<String>? {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var inWord = false
        for (c in command) {
            when {
                quote != null -> if (c == quote) quote = null else current.append(c)
                c == '\'' || c == '"' -> { quote = c; inWord = true }
                c.isWhitespace() -> if (inWord) { out += current.toString(); current.clear(); inWord = false }
                else -> { current.append(c); inWord = true }
            }
        }
        if (quote != null) return null
        if (inWord) out += current.toString()
        return out
    }
}
