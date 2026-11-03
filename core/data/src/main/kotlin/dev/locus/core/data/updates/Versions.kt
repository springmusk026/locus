package dev.locus.core.data.updates

/**
 * Locus's version names compared as semantic versions (https://semver.org/#spec-item-11): numbers
 * part by part, a pre-release ("0.2.0-beta.1") below its release, build metadata ("+abc") ignored.
 * Release tags are `v<versionName>` (docs/RELEASING.md), so the two compare directly.
 */
object Versions {
    /** Whether [candidate] is a newer version than [installed]. Unreadable names are never newer. */
    fun isNewer(candidate: String, installed: String): Boolean = (compare(candidate, installed) ?: 0) > 0

    /** Negative, zero or positive as [a] is older than, the same as or newer than [b]; null if either can't be read. */
    fun compare(a: String, b: String): Int? {
        val x = parse(a) ?: return null
        val y = parse(b) ?: return null
        return compare(x, y)
    }

    private class Parsed(val numbers: List<Int>, val pre: List<String>)

    private fun parse(name: String): Parsed? {
        val core = name.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        if (core.isEmpty()) return null
        val main = core.substringBefore('-')
        val pre = if ('-' in core) core.substringAfter('-').split('.').filter { it.isNotEmpty() } else emptyList()
        val numbers = main.split('.').map { it.toIntOrNull() ?: return null }
        if (numbers.isEmpty() || numbers.any { it < 0 }) return null
        return Parsed(numbers, pre)
    }

    private fun compare(a: Parsed, b: Parsed): Int {
        for (i in 0 until maxOf(a.numbers.size, b.numbers.size)) {
            val d = a.numbers.getOrElse(i) { 0 }.compareTo(b.numbers.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        // A version without a pre-release outranks one with.
        if (a.pre.isEmpty() || b.pre.isEmpty()) return b.pre.size.coerceAtMost(1) - a.pre.size.coerceAtMost(1)
        for (i in 0 until minOf(a.pre.size, b.pre.size)) {
            val x = a.pre[i]
            val y = b.pre[i]
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            val d = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null -> -1 // numeric identifiers sort below alphanumeric ones
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (d != 0) return d
        }
        return a.pre.size.compareTo(b.pre.size)
    }
}
