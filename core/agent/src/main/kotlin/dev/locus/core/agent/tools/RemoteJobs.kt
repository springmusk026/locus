package dev.locus.core.agent.tools

import java.util.Base64

/**
 * Shell snippets for long jobs on the user's own machines. A job runs detached under nohup in
 * `~/.locus-jobs/<id>.*` (script, log, pid, exit code), so it keeps going when the SSH session ends
 * or Android kills Locus; later calls read its state and log tail.
 *
 * sshd runs commands through the user's login shell, which may be fish or csh, so every snippet is
 * sent as `echo <base64> | base64 -d | sh`: only that pipeline must parse there, and the real
 * script always runs under POSIX sh.
 */
object RemoteJobs {
    private const val DIR = "\"\$HOME/.locus-jobs\""
    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,47}$")

    fun isValidId(id: String) = ID.matches(id)

    /** A readable, unique id: the name's slug plus a short random suffix. */
    fun newId(name: String?, random: () -> String): String {
        val slug = name.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32)
        return (if (slug.isEmpty()) "job" else slug) + "-" + random().lowercase().filter { it.isLetterOrDigit() }.take(6)
    }

    /** Single-quotes [s] for sh, so the command is stored byte for byte. */
    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Wraps a POSIX sh script so any login shell can run it. */
    fun portable(script: String): String =
        "echo ${Base64.getEncoder().encodeToString(script.toByteArray())} | base64 -d | sh"

    fun start(id: String, command: String): String {
        require(isValidId(id)) { "bad job id" }
        // The exit code is written to a temp file and moved into place, so a status check never sees it half-written.
        val runner = "sh \"\$1.sh\" > \"\$1.log\" 2>&1; echo \$? > \"\$1.exit.tmp\"; mv \"\$1.exit.tmp\" \"\$1.exit\""
        return portable(
            """
            d=$DIR
            mkdir -p "${'$'}d" && printf '%s\n' ${quote(command)} > "${'$'}d/$id.sh" || exit 1
            nohup sh -c ${quote(runner)} _ "${'$'}d/$id" > /dev/null 2>&1 &
            echo ${'$'}! > "${'$'}d/$id.pid"
            echo "started $id"
            """.trimIndent(),
        )
    }

    fun status(id: String, lines: Int): String {
        require(isValidId(id)) { "bad job id" }
        val n = lines.coerceIn(1, 500)
        return portable(
            """
            f=$DIR/$id
            if [ -f "${'$'}f.exit" ]; then echo "state: finished, exit code ${'$'}(cat "${'$'}f.exit")"
            elif [ -f "${'$'}f.pid" ] && kill -0 "${'$'}(cat "${'$'}f.pid")" 2>/dev/null; then echo "state: running"
            elif [ -f "${'$'}f.sh" ]; then echo "state: stopped without finishing (killed or the machine restarted)"
            else echo "state: no such job"; exit 0; fi
            echo "--- last $n lines of output ---"
            tail -n $n "${'$'}f.log" 2>/dev/null
            """.trimIndent(),
        )
    }

    /** Every job with its state. */
    fun list(): String = portable(
        """
        d=$DIR
        [ -d "${'$'}d" ] || { echo 'no jobs'; exit 0; }
        found=
        for s in "${'$'}d"/*.sh; do
          [ -f "${'$'}s" ] || continue
          found=1; f="${'$'}{s%.sh}"; id="${'$'}{f##*/}"
          if [ -f "${'$'}f.exit" ]; then st="finished (exit ${'$'}(cat "${'$'}f.exit"))"
          elif [ -f "${'$'}f.pid" ] && kill -0 "${'$'}(cat "${'$'}f.pid")" 2>/dev/null; then st=running; else st=stopped; fi
          echo "${'$'}id: ${'$'}st"
        done
        [ -n "${'$'}found" ] || echo 'no jobs'
        """.trimIndent(),
    )

    /** Stops the job and everything it started (the pid is only the nohup wrapper). */
    fun stop(id: String): String {
        require(isValidId(id)) { "bad job id" }
        return portable(
            """
            f=$DIR/$id
            tree() { echo "${'$'}1"; for c in ${'$'}(pgrep -P "${'$'}1" 2>/dev/null); do tree "${'$'}c"; done; }
            if [ -f "${'$'}f.exit" ]; then echo 'already finished'
            elif [ -f "${'$'}f.pid" ] && p="${'$'}(cat "${'$'}f.pid")" && kill -0 "${'$'}p" 2>/dev/null; then
              # Freeze the whole tree first so nothing starts a new child mid-way, then terminate it.
              all=${'$'}(tree "${'$'}p")
              kill -STOP ${'$'}all 2>/dev/null; kill -TERM ${'$'}all 2>/dev/null; kill -CONT ${'$'}all 2>/dev/null
              [ -f "${'$'}f.exit" ] || echo 143 > "${'$'}f.exit"
              echo stopped
            else echo 'not running'; fi
            """.trimIndent(),
        )
    }
}
