package dev.locus.core.agent

/**
 * Which shell commands may be remembered as a prefix rule ("Always allow `./gradlew test`"), and
 * whether a command matches one (docs/design/remembered-approvals.md). Deliberately narrow:
 *
 * - Only a single plain command is ever remembered or matched. Anything the shell would treat as more
 *   than one command or expand at run time (`;`, `&`, `|`, `` ` ``, `$`, redirects, subshells, braces,
 *   backslashes, newlines, a leading `VAR=value`) is opaque, as in Codex's rules and Claude Code's
 *   compound-command handling, so `curl … | sh` and `ok && rm -rf ~` can never ride on a rule.
 * - A denylist of commands is never remembered, whatever their arguments: privilege changes, recursive
 *   deletes, shells, inline code (`sed`, `awk`), wrappers that run another program (`env`, `xargs`,
 *   `timeout`, `nice`, `nohup`), pagers and editors, network clients, process killing.
 * - Options that make an otherwise harmless tool run a program, the ones Codex's safe-command list
 *   and Claude Code's read-only checks refuse (`git grep -O`, `git log --ext-diff`, `tar --to-command`,
 *   `tar -I`, `zip -TT`, `find -exec`, `rg --pre`, `go test -exec`), are never remembered or matched,
 *   in any spelling GNU tools and git accept: `--opt=value`, `--opt value`, an abbreviation
 *   (`--open-files`), or a letter inside a short-option bundle (`-nO`).
 * - No argument may point outside the workspace: no absolute path, `~` or `..`.
 *
 * Matching is strict ([matches]): the words after a rule's own words may only be plain path or word
 * tokens (no quotes, `=`, globs or shell syntax), and options there are accepted only for tools whose
 * program-running options Locus knows ([OPTIONS_VETTED]). A false "dangerous" only means one more card.
 */
object CommandSafety {
    /** At most this many leading words make a prefix: `npm run test`, not the whole command line. */
    const val MAX_PREFIX_WORDS = 3

    private val SHELL_META = charArrayOf(';', '&', '|', '`', '$', '>', '<', '(', ')', '{', '}', '\\', '\n', '\r', '!')

    /** Never remembered, by the command's own name (the last part of its path). */
    private val NEVER = setOf(
        // Privilege and identity.
        "sudo", "su", "doas", "pkexec", "runuser", "chroot", "sg", "newgrp",
        // Deleting, wiping and overwriting.
        "rm", "shred", "dd", "truncate", "wipefs", "fdisk", "sfdisk", "parted", "mkswap", "srm",
        // Permissions and ownership, mounts.
        "chmod", "chown", "chgrp", "chattr", "setfacl", "mount", "umount", "losetup",
        // Shells, inline code and command runners: they run whatever they're given.
        "sh", "bash", "zsh", "dash", "ash", "ksh", "mksh", "fish", "csh", "tcsh", "busybox", "toybox",
        "eval", "exec", "source", ".", "env", "xargs", "nohup", "setsid", "nice", "ionice", "timeout", "time", "watch",
        "stdbuf", "command", "builtin", "script", "expect", "parallel", "noglob", "flock", "chrt", "taskset", "prlimit",
        "unshare", "nsenter", "proot", "fakeroot", "strace", "ltrace", "gdb", "valgrind", "systemd-run", "run-parts",
        "entr", "direnv", "firejail", "torsocks", "proxychains", "proxychains4",
        // Inline-code languages whose program is the first argument: sed's `e`, `w` and `r` commands, awk's system().
        "sed", "gsed", "awk", "gawk", "mawk", "nawk",
        // Pagers and editors: `!cmd`, `-P pager`, `:!cmd`, and they wait for a terminal.
        "less", "more", "most", "man", "info", "vi", "vim", "nvim", "view", "ex", "ed", "emacs", "nano", "pico", "joe",
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
        "npx", "pnpx", "bunx", "uvx", "pipx", "java", "kotlin", "kotlinc", "go", "make", "gmake",
    )

    /** Subcommands never remembered, per command. */
    private val NEVER_SUBCOMMANDS = mapOf(
        "git" to setOf(
            "push", "reset", "clean", "config", "filter-branch", "filter-repo", "update-ref", "reflog", "gc", "prune",
            "rebase", "remote", "submodule", "credential", "daemon", "send-email", "archive", "bundle",
            // These run a configured or named program: `difftool --extcmd`, `bisect run`, `hook run`, `instaweb`.
            "difftool", "mergetool", "bisect", "hook", "instaweb", "web--browse", "upload-pack", "receive-pack",
        ),
        "npm" to setOf("publish", "unpublish", "exec", "x", "login", "adduser", "token", "owner", "access", "deprecate", "config", "explore", "edit"),
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
        "-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprint0", "-fprintf", "-fls",
        "--force", "-f", "--force-with-lease", "--hard", "--no-preserve-root", "--global", "-g", "--system",
        "--upload-pack", "--receive-pack", "--exec",
    )

    /**
     * Options that make a tool run a program (or load code, or pick another config), per tool. [long]
     * options are refused in every spelling getopt and git accept (`--opt`, `--opt=v`, any unambiguous
     * abbreviation); [letters] are short options refused anywhere in a bundle (`-nO`); [exact] are
     * refused as written (single-dash long options, like go's `-exec`, or `-c` for npm).
     */
    private class ExecOptions(val long: Set<String> = emptySet(), val letters: Set<Char> = emptySet(), val exact: Set<String> = emptySet())

    private val EXEC_OPTIONS: Map<String, ExecOptions> = buildMap {
        // git: pagers, external diff and textconv drivers, an output file, other git dirs and config
        // (Codex's git checks refuse -c, --config-env, --output, --ext-diff, --textconv, --exec, --paginate).
        put(
            "git",
            ExecOptions(
                long = setOf(
                    "--open-files-in-pager", "--ext-diff", "--textconv", "--output", "--exec", "--exec-path", "--paginate",
                    "--config-env", "--git-dir", "--work-tree", "--namespace", "--super-prefix", "--template", "--separate-git-dir",
                    "--upload-pack", "--receive-pack", "--extcmd", "--tool", "--run", "--config",
                ),
                letters = setOf('O'),
                // -c sets config (core.pager, core.fsmonitor, diff.external): refused wherever it stands.
                exact = setOf("-c"),
            ),
        )
        // tar (GNU and bsdtar): programs run at checkpoints, per file, to compress, between volumes, remotely.
        val tar = ExecOptions(
            long = setOf(
                "--checkpoint-action", "--to-command", "--use-compress-program", "--info-script", "--new-volume-script",
                "--rsh-command", "--rmt-command", "--index-file", "--files-from", "--options",
            ),
            letters = setOf('I', 'F', 'T'),
        )
        listOf("tar", "gtar", "bsdtar").forEach { put(it, tar) }
        // zip: -T tests with unzip, -TT names the program to test with.
        put("zip", ExecOptions(long = setOf("--unzip-command", "--test"), letters = setOf('T')))
        // ripgrep: a preprocessor per file, a hostname program, decompressors (Codex refuses all three).
        put("rg", ExecOptions(long = setOf("--pre", "--pre-glob", "--hostname-bin", "--search-zip"), letters = setOf('z')))
        put("find", ExecOptions(exact = setOf("-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprint0", "-fprintf", "-fls", "-files0-from")))
        // sort can compress temporary files with any program.
        put("sort", ExecOptions(long = setOf("--compress-program")))
        // Gradle: init scripts and other build or settings files run code the rule never saw.
        val gradle = ExecOptions(
            long = setOf("--init-script", "--build-file", "--settings-file", "--project-dir", "--include-build", "--gradle-user-home", "--project-cache-dir"),
            exact = setOf("-I", "-b", "-c", "-p", "-g"),
        )
        listOf("gradle", "gradlew").forEach { put(it, gradle) }
        // npm, pnpm, yarn: the shell scripts run in, node's own options, another config or prefix, editors.
        val npm = ExecOptions(
            long = setOf(
                "--script-shell", "--shell", "--node-options", "--userconfig", "--globalconfig", "--prefix", "--call",
                "--editor", "--browser", "--git", "--package",
            ),
            exact = setOf("-c"),
        )
        listOf("npm", "pnpm", "yarn").forEach { put(it, npm) }
        put("cargo", ExecOptions(long = setOf("--config", "--manifest-path"), letters = setOf('Z')))
        // go: a program to run test binaries with, a toolchain wrapper, another vet tool.
        put("go", ExecOptions(exact = setOf("-exec", "--exec", "-toolexec", "--toolexec", "-vettool", "--vettool", "-overlay", "--overlay", "-modfile", "--modfile")))
        // make: inline makefile text, other makefiles and include folders.
        val make = ExecOptions(long = setOf("--eval", "--file", "--makefile", "--include-dir"), letters = setOf('E', 'I', 'f'))
        listOf("make", "gmake").forEach { put(it, make) }
        // pytest -p loads any importable module as a plugin.
        put("pytest", ExecOptions(exact = setOf("-p")))
    }

    /**
     * Tools whose options after a rule's words are accepted, because the options that run a program
     * are known ([EXEC_OPTIONS]) or there are none: plain readers and file tools, and interpreters,
     * whose options after the script go to the script. Any other tool takes only plain non-option
     * words after a rule's words, so an unknown tool's `--wrapper prog` can never ride on a rule.
     */
    private val OPTIONS_VETTED: Set<String> = EXEC_OPTIONS.keys + INTERPRETERS + setOf(
        "ls", "cat", "head", "tail", "wc", "grep", "egrep", "fgrep", "diff", "cmp", "du", "df", "stat", "tree", "pwd", "echo",
        "true", "false", "which", "nl", "cut", "uniq", "basename", "dirname", "realpath", "readlink", "touch", "mkdir",
        "cp", "mv", "ln", "unzip", "gzip", "gunzip", "xz", "unxz", "bzip2", "date", "uname", "whoami", "id", "file",
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
        args.firstOrNull { runsProgram(name, it) }?.let { return "“$it” can make $name run another program" }
        if (name in TAR_NAMES && args.firstOrNull()?.let { old -> !old.startsWith("-") && old.all(Char::isLetter) && old.any { it in TAR_OLD_STYLE } } == true) {
            return "“${args.first()}” can make $name run another program"
        }
        val sub = args.firstOrNull { !it.startsWith("-") }
        NEVER_SUBCOMMANDS[name]?.let { banned -> if (sub != null && sub in banned) return "$name $sub is never remembered" }
        if (name in INTERPRETERS && (args.isEmpty() || args.first().startsWith("-"))) return "$name runs inline code or anything it is given"
        return null
    }

    private val TAR_NAMES = setOf("tar", "gtar", "bsdtar")

    /** tar's old-style first argument (`tar cIf …`) is a bundle of option letters without a dash. */
    private val TAR_OLD_STYLE = setOf('I', 'F', 'T')

    /** Whether [arg] is one of [name]'s options that run a program, in any spelling it accepts. */
    private fun runsProgram(name: String, arg: String): Boolean {
        val opts = EXEC_OPTIONS[name] ?: return false
        if (!arg.startsWith("-") || arg == "-" || arg == "--") return false
        if (arg in opts.exact || arg.substringBefore('=') in opts.exact) return true
        if (arg.startsWith("--")) {
            val given = arg.substringBefore('=')
            // The option itself, or an abbreviation getopt_long and git's parse-options would expand to it.
            return opts.long.any { it == given || it.startsWith(given) && given.length > 2 }
        }
        // A short option bundle: `-nO`, `-xIzstd`, `-TT`. Single-dash long options (go, find) are in [exact].
        return arg.drop(1).any { it in opts.letters }
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

    /**
     * Whether [command] is covered by a rule for [prefix]. Strict, not "starts with":
     * - the command itself must pass [dangerReason], and have no quotes anywhere (a quoted word can
     *   hold `sh -c '…'` or an option value with spaces);
     * - its first words must be the rule's words, exactly;
     * - each word after them must be a plain path or word token ([EXTRA]): no `=` option values, no
     *   globs (`*` can expand to a file named `--checkpoint-action=exec=…`), nothing the shell reads;
     * - an option there is accepted only for a tool in [OPTIONS_VETTED];
     * - and no word there may name a never-remembered program (a shell, a wrapper like `env` or `xargs`).
     */
    fun matches(prefix: String, command: String): Boolean {
        if (dangerReason(command) != null) return false
        val trimmed = command.trim()
        if ('\'' in trimmed || '"' in trimmed) return false
        val want = words(prefix.trim()) ?: return false
        val have = words(trimmed) ?: return false
        if (want.isEmpty() || have.size < want.size) return false
        if (!want.indices.all { want[it] == have[it] }) return false
        val name = have.first().substringAfterLast('/')
        return have.drop(want.size).all { extra ->
            EXTRA.matches(extra) &&
                (!extra.startsWith("-") || name in OPTIONS_VETTED) &&
                extra.substringAfterLast('/') !in NEVER
        }
    }

    /** Programs that fetch from the network whatever their arguments (package runners download what they run). */
    private val FETCHING = setOf("gh", "npx", "pnpx", "bunx", "uvx", "pipx", "deno", "brew", "flatpak", "snap", "ollama", "huggingface-cli", "hf")

    /** Subcommands that download or reach a remote, per program. */
    private val FETCHING_SUBCOMMANDS = mapOf(
        "git" to setOf("clone", "fetch", "pull", "ls-remote", "submodule", "remote", "push"),
        "npm" to setOf("install", "i", "ci", "add", "update", "upgrade", "outdated", "view", "info", "show", "audit", "search", "create", "init", "exec", "x"),
        "pnpm" to setOf("install", "i", "add", "update", "up", "outdated", "dlx", "create", "audit"),
        "yarn" to setOf("install", "add", "upgrade", "up", "outdated", "dlx", "create", "info", "npm"),
        "bun" to setOf("install", "i", "add", "update", "create", "x"),
        "pip" to setOf("install", "download", "index", "search"),
        "pip3" to setOf("install", "download", "index", "search"),
        "uv" to setOf("add", "sync", "pip", "tool", "run", "lock", "python"),
        "poetry" to setOf("install", "add", "update", "lock", "search"),
        "go" to setOf("get", "mod", "install"),
        "cargo" to setOf("fetch", "update", "search", "install", "add"),
        "docker" to setOf("pull", "build", "push", "login", "search"),
        "composer" to setOf("install", "require", "update"),
        "bundle" to setOf("install", "update", "add"),
        "gem" to setOf("install", "fetch", "update"),
    )

    /**
     * Whether [command], read as one plain command, may have brought in content from the network: a
     * program or subcommand that downloads or reaches a remote, or a URL among its words. Its output is
     * then outside content (docs/design/taint.md); a plain local command's output (tests, builds,
     * listings) is the workspace's own, like a file read. Errs towards true: anything it can't read as one
     * plain command counts as fetching.
     */
    fun mayFetch(command: String): Boolean {
        if (dangerReason(command) != null) return true
        val words = words(command.trim()) ?: return true
        if (words.any { "://" in it }) return true
        val name = words.first().substringAfterLast('/')
        if (name in FETCHING) return true
        val sub = words.drop(1).firstOrNull { !it.startsWith("-") } ?: return false
        return FETCHING_SUBCOMMANDS[name]?.contains(sub) == true
    }

    /** A word the prefix may hold: letters, digits and `._/:+@=,-`, not starting with `-`. */
    private val PLAIN = Regex("[A-Za-z0-9._][A-Za-z0-9._/:+@=,-]*")

    /** A word that may follow a rule's words: a plain path, word or option, with no `=`, glob or quote. */
    private val EXTRA = Regex("[A-Za-z0-9._/:+@,%-]+")

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
