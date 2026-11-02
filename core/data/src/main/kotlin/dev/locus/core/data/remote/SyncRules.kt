package dev.locus.core.data.remote

/**
 * What a project sync leaves out (docs/design/remote-execution.md, "What is never copied"). Paths are
 * relative to the project folder, with `/` separators.
 *
 * - **Secrets** never leave the phone: key and credential files by name, folders that hold them, and
 *   any file containing a value from Locus's vault ([containsSecret]).
 * - **Folders that stay where they are**: git's own folder (git stays on the phone, as Mutagen
 *   recommends for synced trees), dependency and build folders each side makes for itself, Locus's own
 *   `.locus/`, and whatever the project's `.locusignore` names.
 */
class SyncRules(ignoreFile: String? = null) {
    enum class Skip {
        /** A secret: never copied either way. */
        SECRET,

        /** Stays on whichever side made it. */
        STAYS,
    }

    private val ignores: List<String> = ignoreFile.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }

    /** Why [path] (a folder when [isDir]) isn't synced, or null when it is. */
    fun skip(path: String, isDir: Boolean): Skip? {
        val name = path.substringAfterLast('/')
        if (isDir && name in SECRET_DIRS) return Skip.SECRET
        if (!isDir && isSecretName(name)) return Skip.SECRET
        if (isDir && name in STAY_DIRS) return Skip.STAYS
        if (ignores.any { matches(it, path, name, isDir) }) return Skip.STAYS
        return null
    }

    private fun matches(pattern: String, path: String, name: String, isDir: Boolean): Boolean {
        val dirOnly = pattern.endsWith("/")
        if (dirOnly && !isDir) return false
        val p = pattern.trimEnd('/')
        // With a slash inside, the pattern is anchored at the project folder; otherwise it matches any name.
        return if ('/' in p) glob(p.trimStart('/')).matches(path) else glob(p).matches(name)
    }

    companion object {
        const val IGNORE_FILE = ".locusignore"

        val STAY_DIRS = setOf(
            ".git", ".hg", ".svn", ".locus",
            "node_modules", ".gradle", ".kotlin", "build", "target", ".venv", "venv", "__pycache__",
            ".pytest_cache", ".mypy_cache", ".ruff_cache", ".next", ".nuxt", ".svelte-kit", ".turbo", ".cache", ".idea", ".terraform",
        )

        val SECRET_DIRS = setOf(".ssh", ".gnupg", ".aws", ".azure", ".kube", ".docker", ".password-store")

        private val SECRET_NAMES = setOf(
            ".netrc", "_netrc", ".npmrc", ".pypirc", ".git-credentials", ".pgpass", ".htpasswd",
            "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "id_ecdsa_sk", "id_ed25519_sk",
            "credentials", "credentials.json", "secrets.json", "secrets.yaml", "secrets.yml", "secrets.toml",
            "local.properties", "keystore.properties", "service-account.json",
        )
        private val SECRET_EXTENSIONS = setOf("pem", "key", "p12", "pfx", "jks", "keystore", "kdbx", "tfvars", "ovpn", "asc")
        private val ENV_TEMPLATES = setOf("example", "sample", "template", "dist", "defaults")

        /** A key, credential or environment file, by its name. `.env.example` and the like are templates, not secrets. */
        fun isSecretName(name: String): Boolean {
            val lower = name.lowercase()
            if (lower in SECRET_NAMES) return true
            if (lower == ".env" || lower.endsWith(".env")) return true
            if (lower.startsWith(".env.")) return lower.substringAfterLast('.') !in ENV_TEMPLATES
            val ext = lower.substringAfterLast('.', "")
            return ext in SECRET_EXTENSIONS
        }

        /**
         * Whether [bytes] hold one of the vault's [secrets] (API keys, passwords, tokens Locus keeps). A
         * value the agent wrote into a config file must not leave the phone either. Values shorter than
         * 8 characters are ignored: they'd match ordinary text.
         */
        fun containsSecret(bytes: ByteArray, secrets: Collection<String>): Boolean {
            if (secrets.isEmpty()) return false
            val text = String(bytes, Charsets.ISO_8859_1)
            return secrets.any { s -> s.length >= 8 && text.contains(String(s.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)) }
        }

        /** A `.gitignore`-style glob: `*` within a name, `**` across folders, `?` one character. */
        internal fun glob(pattern: String): Regex {
            val out = StringBuilder()
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    c == '*' && pattern.getOrNull(i + 1) == '*' -> { out.append(".*"); i++ }
                    c == '*' -> out.append("[^/]*")
                    c == '?' -> out.append("[^/]")
                    else -> out.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex(out.toString())
        }
    }
}
