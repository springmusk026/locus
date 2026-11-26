package dev.locus.feature.chat

import dev.locus.core.data.repository.TrackedChange
import dev.locus.core.model.TaskReceipt
import dev.locus.core.model.Verification
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
 * anything shaped like a key or token is replaced with [REDACTED]. Its own words ([ExportWords]) are
 * in the app's language when the share sheet makes it ([exportWords]); English by default.
 */
internal object ChatExport {

    const val REDACTED = "[redacted]"

    fun markdown(title: String, startedAt: Long, items: List<ChatItem>, zone: ZoneId = ZoneId.systemDefault(), words: ExportWords = ExportWords()): String {
        val out = StringBuilder()
        out.append("# ").append(title.ifBlank { words.untitled }.lineSequence().first()).append("\n\n")
        if (startedAt > 0) out.append("_").append(words.dated.format(words.date(startedAt, zone))).append("_\n")
        else out.append("_").append(words.undated).append("_\n")

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
            out.append("\n**").append(words.speaker.format(who)).append("**\n")
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
                    speak(words.you)
                    if (item.text.isNotBlank()) paragraph(item.text)
                    if (item.attachments.isNotEmpty()) paragraph(words.attached.format(item.attachments.joinToString(", ") { code(it.name) }))
                }
                is ChatItem.Checkpoint -> {
                    speaker = null
                    paragraph("_" + words.summarised + "_")
                }
                is ChatItem.Assistant -> if (item.text.isNotBlank()) {
                    speak(words.locus)
                    paragraph(item.text)
                }
                is ChatItem.ToolCall -> {
                    speak(words.locus)
                    step(stepLabel(item, words))
                }
                is ChatItem.Note -> {
                    speak(words.locus)
                    step(
                        oneLine(
                            when {
                                item.ranAnyway -> words.ranAnyway.format(item.text)
                                item.approvedProposal -> words.approved.format(item.text)
                                else -> item.text
                            },
                        ),
                    )
                }
                is ChatItem.Plan -> {
                    speak(words.locus)
                    paragraph(words.plan.format("**${item.plan.title}**") + "\n\n" + item.plan.steps.mapIndexed { i, s -> "${i + 1}. ${oneLine(s)}" }.joinToString("\n"))
                }
                is ChatItem.SentFile -> {
                    speak(words.locus)
                    step(words.sent.format(code(item.path.substringAfterLast('/'))))
                }
                is ChatItem.ProviderDraft -> {
                    speak(words.locus)
                    step(words.providerDraft.format(code(item.name), item.host, words.modelsPage))
                }
                is ChatItem.Verification -> {
                    speak(words.locus)
                    step(words.field.format(stepLabel(item.call, words), words.check(item.outcome)))
                }
                // A card holding only notes about untracked commands has no files to list.
                is ChatItem.Changes -> if (item.files.isNotEmpty()) {
                    speak(words.locus)
                    item.files.forEach { f -> step((if (f.undone) words.changedUndone else words.changed).format(code(f.path), words.change(f))) }
                }
                is ChatItem.Receipt -> {
                    speaker = null
                    paragraph(words.receipt(item.receipt))
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
    fun stepLabel(item: ChatItem.ToolCall, words: ExportWords = ExportWords()): String {
        val done = item.result != null
        val label = when (item.name) {
            "browser_type" -> if (done) words.typed else words.type
            else -> words.tool(item.name, item.argumentsJson, done)
        }
        val failed = item.result?.isError == true
        val line = redactAssignments(oneLine(label))
        return if (failed) words.failed.format(line) else if (!done) words.notRun.format(line) else line
    }

    /** A file name for the export: the title made safe, and the chat's date. */
    fun fileName(title: String, startedAt: Long, extension: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val base = title.lineSequence().firstOrNull().orEmpty().replace(UNSAFE_NAME, " ").replace(SPACES, " ").trim().take(60).trim()
            .ifBlank { "Chat" }
        val date = if (startedAt > 0) " " + FILE_DATE.format(Instant.ofEpochMilli(startedAt).atZone(zone)) else ""
        return "$base$date.$extension"
    }

    /** Replaces well-known key and token shapes (API keys, bearer tokens, JWTs, private keys). */
    fun redactTokens(text: String): String =
        TOKENS.fold(text) { acc, regex -> acc.replace(regex) { m -> (m.groups[1]?.value ?: "") + REDACTED } }
            // The shapes every other scrubber uses (Telegram, Groq, Hugging Face, GitLab, Slack, Google, …).
            .let { shaped -> dev.locus.core.agent.SecretScrubber.TOKEN_SHAPES.fold(shaped) { acc, regex -> acc.replace(regex, REDACTED) } }

    /** Blanks the value of `password=…`, `token: …` and the like (used for tool labels, which echo arguments). */
    fun redactAssignments(text: String): String = ASSIGNMENT.replace(text) { m -> m.groupValues[1] + REDACTED }

    private fun oneLine(text: String): String = text.trim().replace(NEWLINES, " ").let { if (it.length > 200) it.take(199).trimEnd() + "…" else it }

    private fun code(name: String) = "`" + name.replace("`", "'") + "`"

    /**
     * A changed file as the English export ([ExportWords]) says it; a shared export in another language
     * uses the screen's [changeSummary]. "New · 121 lines", "Deleted", "+12 −3".
     */
    internal fun changeSummaryEnglish(change: TrackedChange): String {
        fun lines(n: Int) = if (n == 1) "1 line" else "${formatCount(n)} lines"
        return when {
            change.isDirectory -> when (change.kind) {
                TrackedChange.Kind.CREATED -> "New folder"
                TrackedChange.Kind.DELETED -> "Folder deleted"
                TrackedChange.Kind.MODIFIED -> "Folder"
            }
            change.binary -> when (change.kind) {
                TrackedChange.Kind.CREATED -> "New binary file"
                TrackedChange.Kind.DELETED -> "Binary file deleted"
                TrackedChange.Kind.MODIFIED -> "Binary file changed"
            }
            change.kind == TrackedChange.Kind.CREATED -> if (change.added > 0) "New · ${lines(change.added)}" else "New · empty"
            change.kind == TrackedChange.Kind.DELETED -> if (change.removed > 0) "Deleted · ${lines(change.removed)}" else "Deleted"
            else -> lineCounts(change.added, change.removed) ?: "No line changes"
        }
    }

    /** The English export's date, "31 October 2026, 09:00"; a shared export uses the app's language ([exportWords]). */
    internal val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH) // design-exception: the English export's fixed wording
    // A file name, not text to read: the same in every language.
    private val FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH) // design-exception: machine format for file names
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

/**
 * The words a Markdown export is written in: English by default (the strings the core writes, which
 * tests compare); [exportWords] gives the same set in the app's language. Each format string takes
 * the values named in its English.
 */
internal class ExportWords(
    val untitled: String = "Chat",
    /** "%1$s · exported from Locus", with the chat's date. */
    val dated: String = "%1\$s · exported from Locus",
    val undated: String = "Exported from Locus",
    /** "%1$s:", a speaker's name before their turn. */
    val speaker: String = "%1\$s:",
    val you: String = "You",
    val locus: String = "Locus",
    val attached: String = "Attached: %1\$s",
    val summarised: String = "Earlier messages were summarised here to save space.",
    val ranAnyway: String = "You ran the blocked call anyway: %1\$s",
    val approved: String = "You approved it in Needs you: %1\$s",
    val plan: String = "Proposed a plan: %1\$s",
    val sent: String = "Sent you %1\$s",
    /** The provider's name, its host and [modelsPage]. */
    val providerDraft: String = "Drafted provider %1\$s (%2\$s) for review in %3\$s",
    val modelsPage: String = "Settings → Models",
    /** The file and its change ("+12 −3"). */
    val changed: String = "Changed %1\$s (%2\$s)",
    val changedUndone: String = "Changed %1\$s (%2\$s, undone)",
    val failed: String = "%1\$s (failed)",
    val notRun: String = "%1\$s (not run)",
    val typed: String = "Typed into the page",
    val type: String = "Type into the page",
    /** "%1$s: %2$s", a label and its value. */
    val field: String = "%1\$s: %2\$s",
    val date: (Long, ZoneId) -> String = { millis, zone -> ChatExport.DATE.format(Instant.ofEpochMilli(millis).atZone(zone)) },
    val tool: (name: String, argumentsJson: String, done: Boolean) -> String = { name, args, done -> toolLabel(name, args, done = done) },
    val check: (Verification.Outcome) -> String = { Verification.title(it).lowercase() },
    val change: (TrackedChange) -> String = ChatExport::changeSummaryEnglish,
    val receipt: (TaskReceipt) -> String = dev.locus.core.agent.TaskReceipts::exportText,
)
