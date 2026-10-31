package dev.locus.feature.onboarding

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.AgentHome.PersonaFile
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/*
 * The "Your agent" questionnaire and the persona files Locus writes from it. The user answers a few
 * questions; these templates turn the answers into short IDENTITY.md, SOUL.md, USER.md and
 * AGENTS.md files (OpenClaw's workspace convention, see AgentHome.PersonaFile). Everything here is
 * pure so it can be unit-tested.
 */

/** How the agent talks: the vibe line in IDENTITY.md and the voice section of SOUL.md. */
enum class TalkStyle(val label: String, val vibe: String, val guidance: String) {
    Concise("Concise", "calm, direct, brief", "Lead with the answer. Short sentences, no filler, no flattery; add detail only when it matters."),
    Friendly("Friendly", "warm, encouraging, plain-spoken", "Warm and plain-spoken, like a capable friend. Short paragraphs and a little personality, without padding."),
    Formal("Formal", "polite, precise, professional", "Polite, precise and professional. Complete sentences; no slang or emoji."),
    Playful("Playful", "upbeat, witty, light", "Upbeat, with a light and witty touch, but never at the cost of being clear or correct."),
    Thorough("Thorough", "careful, explanatory, precise", "Explain your reasoning and the trade-offs, cite sources, and say what you're unsure of."),
}

/** How much the agent checks in before acting. Tool approvals still apply on top of this. */
enum class Autonomy(val label: String, val summary: String, val rules: List<String>) {
    CheckIn(
        "Check in",
        "Asks before changing anything and proposes a plan first.",
        listOf(
            "For anything with more than one step, propose a short plan and wait for a yes.",
            "Ask before changing files, installing software, spending money or contacting anyone.",
        ),
    ),
    Balanced(
        "Balanced",
        "Gets on with routine steps; asks when it matters.",
        listOf(
            "Go ahead with routine, reversible steps without asking.",
            "Ask before anything costly, public, destructive or hard to undo.",
        ),
    ),
    Independent(
        "Independent",
        "Works through tasks end to end and reports back.",
        listOf(
            "Work through tasks end to end; make sensible choices and report what you did.",
            "Still ask before deleting data, publishing anything or spending money.",
        ),
    ),
}

/** What the user mostly wants help with; each adds one focused rule to AGENTS.md. */
enum class HelpArea(val label: String, val rule: String) {
    Coding("Coding", "Coding: keep each project in its own folder under projects/ in the workspace, and run or test code in Linux before calling it done."),
    Research("Research", "Research: check several sources, link them, and separate facts from opinion."),
    Writing("Writing", "Writing: match the user's voice; give a draft first, then offer changes."),
    Servers("Servers & devops", "Servers: start with read-only checks and show commands before running anything that changes a machine."),
    Phone("Phone tasks", "Phone tasks: use the device tools; the user confirms in the other app."),
    Learning("Learning", "Learning: explain step by step with small examples, and check understanding."),
    Planning("Planning", "Planning: turn goals into short checklists with dates in the user's time zone."),
    Data("Data", "Data: show the numbers and how they were worked out; never change source files."),
}

/** The questionnaire. Blank text answers are simply left out of the files. */
data class PersonaAnswers(
    val agentName: String = AgentHome.DEFAULT_NAME,
    val style: TalkStyle = TalkStyle.Friendly,
    val autonomy: Autonomy = Autonomy.Balanced,
    val areas: Set<HelpArea> = emptySet(),
    val userName: String = "",
    val callMe: String = "",
    val timeZone: String = "",
    val location: String = "",
    val language: String = "",
    val always: String = "",
    val never: String = "",
) {
    /** How the files refer to the user. */
    val addressee: String get() = callMe.oneLine().ifBlank { userName.oneLine() }.ifBlank { "the user" }
}

/** The four persona files' text. Blank means the file is removed. */
data class PersonaFiles(val identity: String, val soul: String, val user: String, val agents: String) {
    operator fun get(file: PersonaFile): String = when (file) {
        PersonaFile.IDENTITY -> identity
        PersonaFile.SOUL -> soul
        PersonaFile.USER -> user
        PersonaFile.AGENTS -> agents
    }

    fun with(file: PersonaFile, text: String): PersonaFiles = when (file) {
        PersonaFile.IDENTITY -> copy(identity = text)
        PersonaFile.SOUL -> copy(soul = text)
        PersonaFile.USER -> copy(user = text)
        PersonaFile.AGENTS -> copy(agents = text)
    }

    /** A stable fingerprint, to tell whether files were edited by hand since Locus wrote them. */
    fun fingerprint(): String = PersonaFile.entries.joinToString("\u0000") { this[it].trim() }.hashCode().toString(16)

    companion object {
        val Empty = PersonaFiles("", "", "", "")
    }
}

object PersonaTemplates {

    fun generate(a: PersonaAnswers) = PersonaFiles(identity(a), soul(a), user(a), agents(a))

    fun identity(a: PersonaAnswers) = buildString {
        appendLine("# Identity")
        appendLine()
        appendLine("- Name: ${a.agentName.oneLine().take(40).ifBlank { AgentHome.DEFAULT_NAME }}")
        appendLine("- Vibe: ${a.style.vibe}")
        append("- Role: ${possessive(a.addressee)} personal AI agent, running on their phone in the Locus app")
    }

    fun soul(a: PersonaAnswers) = buildString {
        appendLine("# Soul")
        appendLine()
        appendLine("## Voice")
        appendLine(a.style.guidance)
        a.language.oneLine().takeIf { it.isNotBlank() }?.let { appendLine("Reply in $it unless asked to use another language.") }
        appendLine()
        appendLine("## Values")
        appendLine("- Be honest about what you did, what failed and what you're unsure of. Never claim something worked without checking.")
        appendLine("- Prefer doing the work over describing it.")
        append("- Respect ${possessive(a.addressee)} time, money and privacy.")
    }

    fun user(a: PersonaAnswers): String {
        val lines = listOfNotNull(
            a.userName.line("Name"),
            a.callMe.oneLine().takeIf { it.isNotBlank() && !it.equals(a.userName.oneLine(), ignoreCase = true) }?.let { "- Call them: $it" },
            a.timeZone.line("Time zone"),
            a.location.line("Location"),
            a.language.line("Language"),
            a.areas.takeIf { it.isNotEmpty() }?.let { areas -> "- Wants help with: " + HelpArea.entries.filter { it in areas }.joinToString(", ") { it.label.lowercase() } },
        )
        if (lines.isEmpty()) return ""
        return "# User\n\n" + lines.joinToString("\n")
    }

    fun agents(a: PersonaAnswers) = buildString {
        appendLine("# Operating rules")
        appendLine()
        appendLine("## Before acting")
        a.autonomy.rules.forEach { appendLine("- $it") }
        val focus = HelpArea.entries.filter { it in a.areas }
        if (focus.isNotEmpty()) {
            appendLine()
            appendLine("## Focus")
            focus.forEach { appendLine("- ${it.rule}") }
        }
        bullets(a.always).takeIf { it.isNotEmpty() }?.let { items ->
            appendLine()
            appendLine("## Always")
            items.forEach { appendLine("- $it") }
        }
        bullets(a.never).takeIf { it.isNotEmpty() }?.let { items ->
            appendLine()
            appendLine("## Never")
            items.forEach { appendLine("- $it") }
        }
    }.trimEnd()

    // ---- Saved answers ----------------------------------------------------------------------------

    /** The answers plus the fingerprint of the files written from them, as a small properties file. */
    fun encode(a: PersonaAnswers, written: PersonaFiles): String {
        val p = Properties()
        p["agentName"] = a.agentName
        p["style"] = a.style.name
        p["autonomy"] = a.autonomy.name
        p["areas"] = a.areas.joinToString(",") { it.name }
        p["userName"] = a.userName
        p["callMe"] = a.callMe
        p["timeZone"] = a.timeZone
        p["location"] = a.location
        p["language"] = a.language
        p["always"] = a.always
        p["never"] = a.never
        p["fingerprint"] = written.fingerprint()
        return StringWriter().also { p.store(it, "Locus persona answers") }.toString()
    }

    /** Reads [encode]'s output: the answers and the fingerprint, or null if unreadable. */
    fun decode(text: String): Pair<PersonaAnswers, String?>? = runCatching {
        val p = Properties().apply { load(StringReader(text)) }
        fun s(key: String) = p.getProperty(key).orEmpty()
        val answers = PersonaAnswers(
            agentName = s("agentName").ifBlank { AgentHome.DEFAULT_NAME },
            style = TalkStyle.entries.firstOrNull { it.name == s("style") } ?: TalkStyle.Friendly,
            autonomy = Autonomy.entries.firstOrNull { it.name == s("autonomy") } ?: Autonomy.Balanced,
            areas = s("areas").split(',').mapNotNull { n -> HelpArea.entries.firstOrNull { it.name == n.trim() } }.toSet(),
            userName = s("userName"),
            callMe = s("callMe"),
            timeZone = s("timeZone"),
            location = s("location"),
            language = s("language"),
            always = s("always"),
            never = s("never"),
        )
        answers to p.getProperty("fingerprint")
    }.getOrNull()

    /**
     * Best-effort answers from files written before the questionnaire existed (or by hand): the
     * agent's name and the `- Key: value` lines of USER.md. Everything else keeps [defaults].
     */
    fun fromFiles(files: PersonaFiles, defaults: PersonaAnswers): PersonaAnswers {
        fun field(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            Regex("""(?im)^\s*[-*]?\s*\**${Regex.escape(key)}\**\s*:\s*(.+?)\s*$""").find(files.user)?.groupValues?.get(1)?.trim('*', ' ')
        }?.takeIf { it.isNotBlank() }
        return defaults.copy(
            agentName = AgentHome.nameIn(files.identity) ?: defaults.agentName,
            style = TalkStyle.entries.firstOrNull { files.identity.contains("Vibe: ${it.vibe}") } ?: defaults.style,
            userName = field("Name") ?: defaults.userName,
            callMe = field("Call them", "Call me", "Preferred name", "What to call them") ?: defaults.callMe,
            timeZone = field("Time zone", "Timezone") ?: defaults.timeZone,
            location = field("Location") ?: defaults.location,
            language = field("Language") ?: defaults.language,
        )
    }

    // ---- "Write it with my model" -----------------------------------------------------------------

    /** Asks the user's model to polish the template drafts, keeping the same facts and format. */
    fun polishPrompt(a: PersonaAnswers, drafts: PersonaFiles) = buildString {
        appendLine("You are helping set up the persona files of a personal AI agent called ${a.agentName.oneLine().ifBlank { AgentHome.DEFAULT_NAME }}, which runs on the user's phone.")
        appendLine("The agent reads these four Markdown files at the start of every chat. Rewrite the drafts below so they read naturally and fit together.")
        appendLine("Rules: keep every fact and rule; add no new facts about the user; keep the \"- Name: …\" line in IDENTITY.md exactly; keep each file under 120 words; write in English unless the user's language says otherwise; no preamble or closing remarks.")
        appendLine("Reply with exactly the four files, each starting with its marker line, in this order:")
        PersonaFile.entries.forEach { appendLine(marker(it)) }
        appendLine()
        PersonaFile.entries.forEach { f ->
            appendLine(marker(f))
            appendLine(drafts[f].ifBlank { "(empty)" })
            appendLine()
        }
    }

    /**
     * Splits the model's reply back into files. Null when any marker is missing, so the caller can
     * keep the templates; the agent's name line is restored if the model dropped it.
     */
    fun parsePolished(reply: String, a: PersonaAnswers): PersonaFiles? {
        val text = reply.replace("\r\n", "\n")
        val starts = PersonaFile.entries.map { f -> f to text.indexOf(marker(f)) }
        if (starts.any { it.second < 0 }) return null
        val ordered = starts.sortedBy { it.second }
        val parts = ordered.mapIndexed { i, (f, start) ->
            val end = ordered.getOrNull(i + 1)?.second ?: text.length
            f to text.substring(start + marker(f).length, end).trim().removeFence().trim().takeIf { it != "(empty)" }.orEmpty().take(AgentHome.PERSONA_BUDGET)
        }.toMap()
        var identity = parts.getValue(PersonaFile.IDENTITY)
        if (identity.isBlank() || parts.getValue(PersonaFile.SOUL).isBlank()) return null
        if (AgentHome.nameIn(identity) == null) identity = "- Name: ${a.agentName.oneLine().ifBlank { AgentHome.DEFAULT_NAME }}\n$identity"
        return PersonaFiles(identity, parts.getValue(PersonaFile.SOUL), parts.getValue(PersonaFile.USER), parts.getValue(PersonaFile.AGENTS))
    }

    private fun marker(f: PersonaFile) = "=== ${f.fileName} ==="

    private fun String.removeFence(): String {
        val t = trim()
        if (!t.startsWith("```")) return t
        return t.substringAfter('\n', "").removeSuffix("```")
    }

    private fun String.line(label: String) = oneLine().takeIf { it.isNotBlank() }?.let { "- $label: $it" }

    /** One rule per line or sentence-ish item; bullets and numbering stripped. */
    internal fun bullets(text: String): List<String> = text.lines()
        .map { it.trim().trimStart('-', '*', '•').replace(Regex("""^\d+[.)]\s*"""), "").trim() }
        .filter { it.isNotBlank() }
        .take(12)

    private fun possessive(name: String) = if (name == "the user") "the user's" else if (name.endsWith("s")) "$name'" else "$name's"
}

private fun String.oneLine() = trim().replace(Regex("""\s+"""), " ")
