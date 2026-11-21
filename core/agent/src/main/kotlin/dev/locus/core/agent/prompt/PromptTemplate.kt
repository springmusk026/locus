package dev.locus.core.agent.prompt

import java.util.Locale

/** The built-in variables a saved prompt can use. */
enum class PromptVariable(val key: String) {
    CLIPBOARD("clipboard"),
    DATE("date"),
    TIME("time"),
    SELECTION("selection"),
    ;

    companion object {
        fun of(name: String): PromptVariable? = entries.firstOrNull { it.key == name.lowercase(Locale.ROOT) }
    }
}

/** A `{{field:Label|default}}`: one box in the form. [default] null means the box must be filled. */
data class PromptField(val label: String, val default: String?) {
    /** Fields with the same label, ignoring case and outer spaces, are one box. */
    val key: String get() = keyOf(label)
    val required: Boolean get() = default == null

    companion object {
        fun keyOf(label: String): String = label.trim().lowercase(Locale.ROOT)
    }
}

/** Why a prompt's text can't be used, at [start] until [end] (exclusive) in the text. [token] is the text in the braces. */
data class PromptIssue(val kind: Kind, val start: Int, val end: Int, val token: String) {
    enum class Kind {
        /** `{{name}}` with a name that isn't one of [PromptVariable] or `field:`. */
        UNKNOWN_VARIABLE,

        /** `{{` with no `}}` after it on the same line. */
        UNCLOSED,

        /** `{{}}`. */
        EMPTY,

        /** `{{field}}`, `{{field:}}`, or a label with a brace in it. */
        BAD_FIELD,

        /** A label over [PromptTemplate.MAX_LABEL] characters or a default over [PromptTemplate.MAX_DEFAULT]. */
        TOO_LONG,

        /** The same field twice with two different defaults. */
        CONFLICTING_DEFAULTS,

        /** More than [PromptTemplate.MAX_FIELDS] different fields. */
        TOO_MANY_FIELDS,
    }
}

/** What fills a prompt's variables. [fields] are keyed by [PromptField.key]; a missing one takes its default. */
data class PromptValues(
    val clipboard: String = "",
    val selection: String = "",
    val date: String = "",
    val time: String = "",
    val fields: Map<String, String> = emptyMap(),
)

/**
 * A saved prompt's text, parsed (docs/design/saved-prompts.md, "Syntax"):
 *
 * - `{{clipboard}}`, `{{date}}`, `{{time}}`, `{{selection}}`: names ignore case and spaces inside the braces.
 * - `{{field:Label}}` asks for a value; `{{field:Label|default}}` offers one, and may be left as it is.
 *   The label ends at the first `|`; spaces around the label and the default are dropped.
 * - `\{{` is a literal `{{`; `\\{{` is a backslash followed by a variable. A backslash anywhere else is itself.
 * - A `}}` with no `{{` before it is plain text.
 *
 * Values are inserted as they are and never read as variables, so a clipboard holding `{{date}}` stays that text.
 */
class PromptTemplate private constructor(
    val text: String,
    private val parts: List<Part>,
    val issues: List<PromptIssue>,
    occurrences: List<PromptField>,
) {
    private sealed interface Part {
        data class Literal(val text: String) : Part
        data class Variable(val variable: PromptVariable) : Part
        data class Field(val key: String) : Part
    }

    /** The form's boxes, in the order they first appear; a box takes the default any of its occurrences gives. */
    val fields: List<PromptField> = LinkedHashMap<String, PromptField>().apply {
        occurrences.forEach { f ->
            val first = getOrPut(f.key) { f }
            if (first.default == null && f.default != null) put(f.key, first.copy(default = f.default))
        }
    }.values.toList()

    /** The built-in variables used, in no particular order. */
    val variables: Set<PromptVariable> = parts.filterIsInstance<Part.Variable>().map { it.variable }.toSet()

    val isValid: Boolean get() = issues.isEmpty()
    val usesClipboard: Boolean get() = PromptVariable.CLIPBOARD in variables
    val usesSelection: Boolean get() = PromptVariable.SELECTION in variables

    /** True when picking it inserts the text at once, with no form and nothing to read. */
    val isPlain: Boolean get() = variables.isEmpty() && fields.isEmpty()

    /** The text with every variable filled in from [values]. */
    fun expand(values: PromptValues): String = buildString {
        parts.forEach { p ->
            when (p) {
                is Part.Literal -> append(p.text)
                is Part.Variable -> append(
                    when (p.variable) {
                        PromptVariable.CLIPBOARD -> values.clipboard
                        PromptVariable.DATE -> values.date
                        PromptVariable.TIME -> values.time
                        PromptVariable.SELECTION -> values.selection
                    },
                )
                is Part.Field -> append(values.fields[p.key] ?: fields.firstOrNull { it.key == p.key }?.default.orEmpty())
            }
        }
    }

    companion object {
        const val MAX_LABEL = 40
        const val MAX_DEFAULT = 500
        const val MAX_FIELDS = 12
        private const val FIELD_PREFIX = "field"

        fun parse(text: String): PromptTemplate {
            val parts = mutableListOf<Part>()
            val issues = mutableListOf<PromptIssue>()
            val fields = mutableListOf<PromptField>()
            val literal = StringBuilder()
            fun flush() {
                if (literal.isNotEmpty()) {
                    parts += Part.Literal(literal.toString())
                    literal.clear()
                }
            }
            var i = 0
            while (i < text.length) {
                when {
                    text.startsWith("\\\\{{", i) -> {
                        literal.append('\\')
                        i += 2
                    }
                    text.startsWith("\\{{", i) -> {
                        literal.append("{{")
                        i += 3
                    }
                    text.startsWith("{{", i) -> {
                        val close = text.indexOf("}}", i + 2)
                        val lineEnd = text.indexOf('\n', i + 2).let { if (it < 0) text.length else it }
                        if (close < 0 || close > lineEnd) {
                            issues += PromptIssue(PromptIssue.Kind.UNCLOSED, i, i + 2, text.substring(i + 2, lineEnd).take(MAX_LABEL))
                            literal.append("{{")
                            i += 2
                            continue
                        }
                        val inner = text.substring(i + 2, close)
                        val end = close + 2
                        val name = inner.trim()
                        val variable = PromptVariable.of(name)
                        when {
                            name.isEmpty() -> issues += PromptIssue(PromptIssue.Kind.EMPTY, i, end, inner)
                            variable != null -> {
                                flush()
                                parts += Part.Variable(variable)
                            }
                            name.lowercase(Locale.ROOT) == FIELD_PREFIX -> issues += PromptIssue(PromptIssue.Kind.BAD_FIELD, i, end, name)
                            name.length > FIELD_PREFIX.length && name.substring(0, FIELD_PREFIX.length).lowercase(Locale.ROOT) == FIELD_PREFIX &&
                                name.substring(FIELD_PREFIX.length).trimStart().startsWith(":") -> {
                                val spec = name.substring(FIELD_PREFIX.length).trimStart().removePrefix(":")
                                val bar = spec.indexOf('|')
                                val label = (if (bar < 0) spec else spec.substring(0, bar)).trim()
                                val default = if (bar < 0) null else spec.substring(bar + 1).trim()
                                val issue = when {
                                    label.isEmpty() || label.any { it == '{' || it == '}' } -> PromptIssue.Kind.BAD_FIELD
                                    label.length > MAX_LABEL || (default?.length ?: 0) > MAX_DEFAULT -> PromptIssue.Kind.TOO_LONG
                                    else -> null
                                }
                                if (issue != null) {
                                    issues += PromptIssue(issue, i, end, name)
                                } else {
                                    val field = PromptField(label, default)
                                    val earlier = fields.firstOrNull { it.key == field.key }
                                    when {
                                        earlier != null && earlier.default != null && default != null && earlier.default != default ->
                                            issues += PromptIssue(PromptIssue.Kind.CONFLICTING_DEFAULTS, i, end, label)
                                        earlier == null && fields.map { it.key }.distinct().size >= MAX_FIELDS ->
                                            issues += PromptIssue(PromptIssue.Kind.TOO_MANY_FIELDS, i, end, label)
                                        else -> {
                                            fields += field
                                            flush()
                                            parts += Part.Field(field.key)
                                        }
                                    }
                                }
                            }
                            else -> issues += PromptIssue(PromptIssue.Kind.UNKNOWN_VARIABLE, i, end, name)
                        }
                        // A broken variable stays in the text as written, so the editor's preview shows where it is.
                        if (issues.lastOrNull()?.start == i) literal.append(text, i, end)
                        i = end
                    }
                    else -> {
                        literal.append(text[i])
                        i++
                    }
                }
            }
            flush()
            return PromptTemplate(text, parts, issues, fields)
        }
    }
}
