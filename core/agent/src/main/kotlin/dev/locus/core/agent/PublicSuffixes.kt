package dev.locus.core.agent

import java.net.IDN

/**
 * The Public Suffix List (https://publicsuffix.org): names under which anyone can register or host a
 * site, such as `com`, `co.uk`, `github.io`, `vercel.app` and `pages.dev`. [Taint.trusted] uses it so that
 * a user naming one of these doesn't make every stranger's site under it a host they named
 * (docs/design/taint.md, "Shared hosting domains").
 *
 * The list is a snapshot bundled as `public_suffix_list.dat` beside this class (rules only, with the
 * list's version; how to refresh it is in the file's header). It is read once, on first use.
 */
internal object PublicSuffixes {
    private class Rules(val exact: Set<String>, val wildcard: Set<String>, val exception: Set<String>, val loaded: Boolean)

    private val rules: Rules by lazy(::load)

    /**
     * Whether [host] is a public suffix by the list's algorithm: a rule matches it exactly, or a wildcard
     * rule (`*.ck`) matches it and no exception rule (`!www.ck`) does. Any single label is one (the list's
     * implied `*` rule). If the bundled list couldn't be read, every name counts as one, so nothing is
     * trusted by being under a name the user typed.
     */
    fun isPublicSuffix(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        if ('.' !in h) return true
        val r = rules
        if (!r.loaded) return true
        if (h in r.exception) return false
        return h in r.exact || h.substringAfter('.') in r.wildcard
    }

    private fun load(): Rules {
        val exact = HashSet<String>(16_384)
        val wildcard = HashSet<String>()
        val exception = HashSet<String>()
        val text = runCatching {
            PublicSuffixes::class.java.getResourceAsStream("public_suffix_list.dat")?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return Rules(exact, wildcard, exception, loaded = false)
        text.lineSequence().forEach { line ->
            val rule = line.trim().substringBefore(' ').lowercase()
            if (rule.isEmpty() || rule.startsWith("//")) return@forEach
            // Rules are written in Unicode; hosts arrive as typed or as punycode, so keep both forms.
            val forms = setOf(rule, runCatching { IDN.toASCII(rule, IDN.ALLOW_UNASSIGNED).lowercase() }.getOrDefault(rule))
            forms.forEach { r ->
                when {
                    r.startsWith("!") -> exception += r.substring(1)
                    r.startsWith("*.") -> wildcard += r.substring(2)
                    else -> exact += r
                }
            }
        }
        return Rules(exact, wildcard, exception, loaded = exact.isNotEmpty())
    }
}
