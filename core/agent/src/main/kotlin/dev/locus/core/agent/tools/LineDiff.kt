package dev.locus.core.agent.tools

/**
 * Line diffs for the changes card: Myers' O(ND) algorithm (the one `git diff` uses by default) on
 * the lines between the common prefix and suffix, rendered as a unified diff with three lines of
 * context. Most agent edits touch a few lines of a file, so trimming first keeps the search small.
 *
 * A change too large to diff exactly within [MAX_D] edit steps is shown as the whole middle removed
 * and re-added: still a correct diff, only not the shortest one.
 */
object LineDiff {
    enum class Op { EQUAL, DELETE, INSERT }

    /** One line of a diff: [text] without its line break. */
    data class Line(val op: Op, val text: String)

    /**
     * A diff's summary. [unified] is null when either side is binary or the text is over the cap
     * passed to [diff]; the counts are still right then (except for binary files, where both are 0).
     */
    data class Result(val added: Int, val removed: Int, val unified: String?, val binary: Boolean = false)

    /** Edit steps beyond which the exact search gives up (its memory grows with the square of this). */
    const val MAX_D = 2_000

    /** Splits [text] into lines without their breaks; a final line break doesn't add an empty line. CRLF counts as one break. */
    fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = text.split('\n').map { it.removeSuffix("\r") }
        return if (text.endsWith('\n')) parts.dropLast(1) else parts
    }

    /** Whether [text] looks binary (has a NUL character), so no line diff is shown. */
    fun isBinary(text: String): Boolean = text.indexOf('\u0000') >= 0

    /** The edit script that turns [a] into [b], in order. */
    fun diff(a: List<String>, b: List<String>): List<Line> {
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        val midA = a.subList(prefix, a.size - suffix)
        val midB = b.subList(prefix, b.size - suffix)
        val middle = myers(midA, midB) ?: (midA.map { Line(Op.DELETE, it) } + midB.map { Line(Op.INSERT, it) })
        return a.subList(0, prefix).map { Line(Op.EQUAL, it) } + middle + a.subList(a.size - suffix, a.size).map { Line(Op.EQUAL, it) }
    }

    /**
     * Compares two versions of the file at [path]. [before] or [after] is null when the file didn't
     * exist on that side. [maxChars] caps the unified text kept; past it only the counts are returned.
     */
    fun diff(path: String, before: String?, after: String?, maxChars: Int = 200_000, context: Int = 3): Result {
        if ((before != null && isBinary(before)) || (after != null && isBinary(after))) return Result(0, 0, null, binary = true)
        val lines = diff(lines(before.orEmpty()), lines(after.orEmpty()))
        val added = lines.count { it.op == Op.INSERT }
        val removed = lines.count { it.op == Op.DELETE }
        if (added == 0 && removed == 0) return Result(0, 0, "")
        val text = unified(path, lines, newFile = before == null, deleted = after == null, context = context, maxChars = maxChars)
        return Result(added, removed, text)
    }

    /** The unified diff of [lines] (from [diff]), or null when it would be over [maxChars]. */
    fun unified(path: String, lines: List<Line>, newFile: Boolean = false, deleted: Boolean = false, context: Int = 3, maxChars: Int = Int.MAX_VALUE): String? {
        val out = StringBuilder()
        out.append("--- ").append(if (newFile) "/dev/null" else "a/$path").append('\n')
        out.append("+++ ").append(if (deleted) "/dev/null" else "b/$path").append('\n')
        val changed = lines.indices.filter { lines[it].op != Op.EQUAL }
        if (changed.isEmpty()) return out.toString()
        // Each hunk spans its changes plus [context] lines either side; nearby hunks merge.
        var i = 0
        while (i < changed.size) {
            val start = (changed[i] - context).coerceAtLeast(0)
            var end = (changed[i] + context).coerceAtMost(lines.size - 1)
            var j = i + 1
            while (j < changed.size && changed[j] - context <= end + 1) {
                end = (changed[j] + context).coerceAtMost(lines.size - 1)
                j++
            }
            // Line numbers where the hunk starts on each side: count what came before it.
            var oldLine = 1
            var newLine = 1
            for (k in 0 until start) {
                if (lines[k].op != Op.INSERT) oldLine++
                if (lines[k].op != Op.DELETE) newLine++
            }
            val hunk = lines.subList(start, end + 1)
            val oldCount = hunk.count { it.op != Op.INSERT }
            val newCount = hunk.count { it.op != Op.DELETE }
            out.append("@@ -").append(range(oldLine, oldCount)).append(" +").append(range(newLine, newCount)).append(" @@\n")
            hunk.forEach { line ->
                out.append(
                    when (line.op) {
                        Op.EQUAL -> ' '
                        Op.DELETE -> '-'
                        Op.INSERT -> '+'
                    },
                ).append(line.text).append('\n')
                if (out.length > maxChars) return null
            }
            i = j
        }
        return out.toString()
    }

    /** `start,count` as unified diffs write it: an empty side starts one line earlier, and a count of 1 is left out. */
    private fun range(start: Int, count: Int): String = when (count) {
        0 -> "${start - 1},0"
        1 -> "$start"
        else -> "$start,$count"
    }

    /** Myers' shortest edit script, or null when it needs more than [MAX_D] steps. */
    private fun myers(a: List<String>, b: List<String>): List<Line>? {
        val n = a.size
        val m = b.size
        if (n == 0) return b.map { Line(Op.INSERT, it) }
        if (m == 0) return a.map { Line(Op.DELETE, it) }
        val max = n + m
        val offset = max + 1
        val v = IntArray(2 * max + 3)
        // trace[d] is v for k in -d..d at the start of round d (that is, after round d - 1).
        val trace = ArrayList<IntArray>()
        var found = -1
        search@ for (d in 0..minOf(max, MAX_D)) {
            trace += v.copyOfRange(offset - d, offset + d + 1)
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[offset + k] = x
                if (x >= n && y >= m) {
                    found = d
                    break@search
                }
                k += 2
            }
        }
        if (found < 0) return null
        val out = ArrayList<Line>(n + m)
        var x = n
        var y = m
        for (d in found downTo 0) {
            val snapshot = trace[d]
            fun at(k: Int) = snapshot[k + d]
            val k = x - y
            val (prevX, prevY) = if (d == 0) {
                0 to 0
            } else {
                val prevK = if (k == -d || (k != d && at(k - 1) < at(k + 1))) k + 1 else k - 1
                val px = at(prevK)
                px to px - prevK
            }
            while (x > prevX && y > prevY) {
                out += Line(Op.EQUAL, a[x - 1])
                x--
                y--
            }
            if (d > 0) {
                if (x == prevX) out += Line(Op.INSERT, b[y - 1]) else out += Line(Op.DELETE, a[x - 1])
            }
            x = prevX
            y = prevY
        }
        out.reverse()
        return out
    }
}
