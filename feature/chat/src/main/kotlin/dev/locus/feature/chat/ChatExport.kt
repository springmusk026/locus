package dev.locus.feature.chat

import dev.locus.core.model.toolLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A chat as Markdown, for sharing: the title and date, then "You:" and "Locus:" turns. It is built
 * from the transcript rows the chat shows ([ChatItem]), so it holds the active branch only and
 * nothing the user can't see: no reasoning, no tool arguments or results, no helper transcripts.
 * Tool steps become one-line labels ("Ran `python3 fib.py`"), attachments are listed by name, and
 * anything shaped like a key or token is replaced with [REDACTED].
 */
internal object ChatExport {

    const val REDACTED = "[redacted]"

    fun markdown(title: String, startedAt: Long, items: List<ChatItem>, zone: ZoneId = ZoneId.systemDefault()): String {
        val out = StringBuilder()
        out.append("# ").append(title.ifBlank { "Chat" }.lineSequence().first()).append("\n\n")
        if (startedAt > 0) out.append("_").append(DATE.format(Instant.ofEpochMilli(startedAt).atZone(zone))).append(" · exported from Locus_\n")
        else out.append("_Exported from Locus_\n")

        var speaker: String? = null
        var inSteps = false
        fun paragraph(text: String) {
            inSteps = false
            out.append("\n").append(text.trimEnd()).append("\n")
        }
        fun speak(who: String) {
            if (speaker == who) return
            speaker = who
            inSteps = false
            out.append("\n**").append(who).append(":**\n")
        }
        fun step(line: String) {
            if (!inSteps) out.append("\n")
            inSteps = true
            out.append("- ").append(line).append("\n")
        }

        items.forEach { item ->
            when (item) {
                is ChatItem.User -> {
                    speaker = null
                    speak("You")
                    if (item.text.isNotBlank()) paragraph(item.text)
                    if (item.attachments.isNotEmpty()) paragraph("Attached: " + item.attachments.joinToString(", ") { code(it.name) })
                }
                is ChatItem.Checkpoint -> {
                    speaker = null
                    paragraph("_Earlier messages were summarised here to save space._")
                }
                is ChatItem.Assistant -> if (item.text.isNotBlank()) {
                    speak("Locus")
                    paragraph(item.text)
                }
                is ChatItem.ToolCall -> {
                    speak("Locus")
                    step(stepLabel(item))
                }
                is ChatItem.Note -> {
                    speak("Locus")
                    step(oneLine(item.text))
                }
                is ChatItem.Plan -> {
                    speak("Locus")
                    paragraph("Proposed a plan: **${item.plan.title}**\n\n" + item.plan.steps.mapIndexed { i, s -> "${i + 1}. ${oneLine(s)}" }.joinToString("\n"))
                }
                is ChatItem.SentFile -> {
                    speak("Locus")
                    step("Sent you " + code(item.path.substringAfterLast('/')))
                }
                is ChatItem.Verification -> {
                    speak("Locus")
                    step(stepLabel(item.call) + ": " + dev.locus.core.model.Verification.title(item.outcome).lowercase())
                }
                is ChatItem.Changes -> {
                    speak("Locus")
                    item.files.forEach { f -> step("Changed " + code(f.path) + " (" + changeSummary(f) + if (f.undone) ", undone)" else ")") }
                }
                is ChatItem.Receipt -> {
                    speaker = null
                    paragraph(dev.locus.core.agent.TaskReceipts.exportText(item.receipt))
                }
                // Reasoning stays private; turn ends carry only branch controls.
                is ChatItem.Thought, is ChatItem.TurnEnd -> Unit
            }
        }
        return redactTokens(out.toString())
    }

    /**
     * A tool step as one line, from the same wording the chat uses, minus anything that could be a
     * secret: what was typed into a page is never repeated, and key=value secrets are blanked.
     */
    fun stepLabel(item: ChatItem.ToolCall): String {
        val done = item.result != null
        val label = when (item.name) {
            "browser_type" -> if (done) "Typed into the page" else "Type into the page"
            else -> toolLabel(item.name, item.argumentsJson, done = done)
        }
        val failed = item.result?.isError == true
        return redactAssignments(oneLine(label)) + if (failed) " (failed)" else if (!done) " (not run)" else ""
    }

    /** A file name for the export: the title made safe, and the chat's date. */
    fun fileName(title: String, startedAt: Long, extension: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val base = title.lineSequence().firstOrNull().orEmpty().replace(UNSAFE_NAME, " ").replace(SPACES, " ").trim().take(60).trim()
            .ifBlank { "Chat" }
        val date = if (startedAt > 0) " " + FILE_DATE.format(Instant.ofEpochMilli(startedAt).atZone(zone)) else ""
        return "$base$date.$extension"
    }

    /** Replaces well-known key and token shapes (API keys, bearer tokens, JWTs, private keys). */
    fun redactTokens(text: String): String = TOKENS.fold(text) { acc, regex -> acc.replace(regex) { m -> (m.groups[1]?.value ?: "") + REDACTED } }

    /** Blanks the value of `password=…`, `token: …` and the like (used for tool labels, which echo arguments). */
    fun redactAssignments(text: String): String = ASSIGNMENT.replace(text) { m -> m.groupValues[1] + REDACTED }

    private fun oneLine(text: String): String = text.trim().replace(NEWLINES, " ").let { if (it.length > 200) it.take(199).trimEnd() + "…" else it }

    private fun code(name: String) = "`" + name.replace("`", "'") + "`"

    private val DATE = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH)
    private val FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)
    private val UNSAFE_NAME = Regex("[\\\\/:*?\"<>|\\p{Cntrl}`]")
    private val SPACES = Regex("\\s+")
    private val NEWLINES = Regex("\\s*\\n\\s*")
    private val ASSIGNMENT = Regex(
        "(?i)((?:api[_-]?key|apikey|access[_-]?token|auth[_-]?token|token|secret|client[_-]?secret|password|passwd|pwd|authorization)\\s*[=:]\\s*[\"']?)(?:(?:bearer|basic|token)\\s+)?[^\\s\"'&,;]+",
    )
    private val TOKENS = listOf(
        Regex("(?s)(-----BEGIN [A-Z ]*PRIVATE KEY-----).*?-----END [A-Z ]*PRIVATE KEY-----"),
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{12,}"),
        Regex("()\\b(?:sk|pk|rk)-(?:ant-|proj-|live-|test-)?[A-Za-z0-9_-]{16,}"),
        Regex("()\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}"),
        Regex("()\\bgithub_pat_[A-Za-z0-9_]{20,}"),
        Regex("()\\bxox[abposr]-[A-Za-z0-9-]{10,}"),
        Regex("()\\bAKIA[0-9A-Z]{16}\\b"),
        Regex("()\\bAIza[0-9A-Za-z_-]{35}"),
        Regex("()\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
    )
}
