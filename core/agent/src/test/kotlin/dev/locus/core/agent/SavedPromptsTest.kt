package dev.locus.core.agent

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.prompt.PromptField
import dev.locus.core.agent.prompt.PromptIssue
import dev.locus.core.agent.prompt.PromptIssue.Kind
import dev.locus.core.agent.prompt.PromptSearch
import dev.locus.core.agent.prompt.PromptStore
import dev.locus.core.agent.prompt.PromptTemplate
import dev.locus.core.agent.prompt.PromptValues
import dev.locus.core.agent.prompt.PromptVariable
import dev.locus.core.agent.prompt.SlashQuery
import dev.locus.core.model.SavedPrompt
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saved prompts (docs/design/saved-prompts.md): the variable syntax, filling it in, and the file they live in. */
class SavedPromptsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val values = PromptValues(clipboard = "CLIP", selection = "SEL", date = "Saturday, November 21, 2026", time = "14:05")

    private fun expand(text: String, v: PromptValues = values) = PromptTemplate.parse(text).also { assertEquals(emptyList(), it.issues) }.expand(v)

    private fun kinds(text: String) = PromptTemplate.parse(text).issues.map { it.kind }

    // ---- Built-in variables

    @Test
    fun `every built-in variable is filled in`() {
        assertEquals("CLIP SEL Saturday, November 21, 2026 14:05", expand("{{clipboard}} {{selection}} {{date}} {{time}}"))
    }

    @Test
    fun `names ignore case and spaces inside the braces`() {
        assertEquals("CLIP|14:05", expand("{{ Clipboard }}|{{TIME}}"))
        assertEquals(setOf(PromptVariable.CLIPBOARD, PromptVariable.TIME), PromptTemplate.parse("{{ Clipboard }}|{{TIME}}").variables)
    }

    @Test
    fun `text without variables is unchanged and plain`() {
        val t = PromptTemplate.parse("Summarise this in three bullet points.\n- short\n- clear")
        assertTrue(t.isPlain)
        assertEquals(t.text, t.expand(PromptValues()))
    }

    @Test
    fun `a value is inserted as it is and never read as a variable`() {
        assertEquals("Fix: {{date}} \\{{x}}", expand("Fix: {{clipboard}}", values.copy(clipboard = "{{date}} \\{{x}}")))
    }

    // ---- Fields

    @Test
    fun `fields ask once per label, in order, with defaults`() {
        val t = PromptTemplate.parse("Write to {{field:Name}} in a {{field: Tone | friendly }} tone. Sign it for {{field:name}}.")
        assertEquals(emptyList(), t.issues)
        assertEquals(listOf(PromptField("Name", null), PromptField("Tone", "friendly")), t.fields)
        assertTrue(t.fields[0].required)
        assertFalse(t.fields[1].required)
        assertEquals("Write to Ana in a friendly tone. Sign it for Ana.", t.expand(PromptValues(fields = mapOf("name" to "Ana"))))
        assertEquals("Write to Ana in a dry tone. Sign it for Ana.", t.expand(PromptValues(fields = mapOf("name" to "Ana", "tone" to "dry"))))
    }

    @Test
    fun `an empty default makes a field optional`() {
        val t = PromptTemplate.parse("Notes: {{field:Extra|}}.")
        assertEquals(listOf(PromptField("Extra", "")), t.fields)
        assertFalse(t.fields.single().required)
        assertEquals("Notes: .", t.expand(PromptValues()))
    }

    @Test
    fun `a default can contain a bar, and a later occurrence may repeat the default`() {
        val t = PromptTemplate.parse("{{field:Sep|a|b}} {{field:Sep}} {{field:Sep|a|b}}")
        assertEquals(emptyList(), t.issues)
        assertEquals("a|b a|b a|b", t.expand(PromptValues()))
    }

    @Test
    fun `a default given only on a later occurrence applies to the box`() {
        val t = PromptTemplate.parse("{{field:Lang}} then {{field:Lang|English}}")
        assertEquals(listOf(PromptField("Lang", "English")), t.fields)
    }

    @Test
    fun `field keyword ignores case`() {
        assertEquals(listOf(PromptField("City", null)), PromptTemplate.parse("{{FIELD:City}}").fields)
        assertEquals(listOf(PromptField("City", null)), PromptTemplate.parse("{{ field :City}}").fields)
    }

    // ---- Escaping

    @Test
    fun `a backslash makes braces literal`() {
        assertEquals("Use {{name}} in Jinja", expand("Use \\{{name}} in Jinja"))
        assertEquals("\\14:05", expand("\\\\{{time}}"))
        assertEquals("a \\ b }} c", expand("a \\ b }} c"))
    }

    // ---- Malformed and unknown

    @Test
    fun `unknown variables are errors with their name and place`() {
        val t = PromptTemplate.parse("Hi {{user}}, today is {{date}}")
        assertEquals(listOf(PromptIssue(Kind.UNKNOWN_VARIABLE, 3, 11, "user")), t.issues)
        assertFalse(t.isValid)
        // Left as written, so a preview shows where it is.
        assertEquals("Hi {{user}}, today is D", t.expand(PromptValues(date = "D")))
    }

    @Test
    fun `malformed variables are errors`() {
        assertEquals(listOf(Kind.UNCLOSED), kinds("Hello {{date"))
        assertEquals(listOf(Kind.UNCLOSED), kinds("Hello {{date\n}}"))
        assertEquals(listOf(Kind.EMPTY), kinds("{{}}"))
        assertEquals(listOf(Kind.EMPTY), kinds("{{   }}"))
        assertEquals(listOf(Kind.BAD_FIELD), kinds("{{field}}"))
        assertEquals(listOf(Kind.BAD_FIELD), kinds("{{field:}}"))
        assertEquals(listOf(Kind.BAD_FIELD), kinds("{{field: |x}}"))
        assertEquals(listOf(Kind.BAD_FIELD), kinds("{{field:a{b}}"))
        assertEquals(listOf(Kind.UNKNOWN_VARIABLE), kinds("{{fields:a}}"))
        assertEquals(listOf(Kind.UNKNOWN_VARIABLE), kinds("{{{date}}}"))
        assertEquals(listOf(Kind.TOO_LONG), kinds("{{field:${"x".repeat(PromptTemplate.MAX_LABEL + 1)}}}"))
        assertEquals(listOf(Kind.TOO_LONG), kinds("{{field:x|${"y".repeat(PromptTemplate.MAX_DEFAULT + 1)}}}"))
        assertEquals(listOf(Kind.CONFLICTING_DEFAULTS), kinds("{{field:A|1}} {{field:a|2}}"))
    }

    @Test
    fun `too many fields is an error past the limit`() {
        val text = (1..PromptTemplate.MAX_FIELDS + 1).joinToString(" ") { "{{field:F$it}}" }
        val t = PromptTemplate.parse(text)
        assertEquals(listOf(Kind.TOO_MANY_FIELDS), t.issues.map { it.kind })
        assertEquals(PromptTemplate.MAX_FIELDS, t.fields.size)
    }

    @Test
    fun `a lone closing pair and single braces are text`() {
        assertEquals("}} {x} {", expand("}} {x} {"))
    }

    // ---- Store

    private fun store() = PromptStore(AgentHome(tmp.newFolder("home")))

    @Test
    fun `prompts round-trip in the user's order`() {
        val store = store()
        val a = store.save(SavedPrompt("", "Translate", "Translate to {{field:Language|Spanish}}: {{selection}}"), now = 10)
        val b = store.save(SavedPrompt("", "Standup", "Standup for {{date}}"), now = 20)
        assertTrue(a.id.isNotBlank())
        assertNotEquals(a.id, b.id)
        assertEquals(listOf(a, b), store.all())
        assertEquals(10, a.createdAt)

        val edited = store.save(a.copy(name = " Translate it ", text = "Translate: {{clipboard}}"), now = 30)
        assertEquals("Translate it", edited.name)
        assertEquals(10, edited.createdAt)
        assertEquals(30, edited.updatedAt)
        assertEquals(listOf(edited, b), store.all())

        assertEquals(listOf(b, edited), store.move(edited.id, 1))
        assertEquals(listOf(b, edited), store.move(edited.id, 5))
        assertEquals(listOf(edited, b), store.move(edited.id, -1))
        assertEquals(listOf(b, edited), store.reorder(listOf(b.id)))

        assertTrue(store.delete(b.id))
        assertFalse(store.delete(b.id))
        assertEquals(listOf(edited), store.all())
    }

    @Test
    fun `a prompt that can't be filled in isn't saved`() {
        val store = store()
        assertFailsWith<IllegalArgumentException> { store.save(SavedPrompt("", "Bad", "Hello {{name}}")) }
        assertFailsWith<IllegalArgumentException> { store.save(SavedPrompt("", " ", "Hello")) }
        assertFailsWith<IllegalArgumentException> { store.save(SavedPrompt("", "Empty", "  ")) }
        assertFailsWith<IllegalArgumentException> { store.save(SavedPrompt("", "x".repeat(SavedPrompt.MAX_NAME + 1), "Hello")) }
        assertEquals(emptyList(), store.all())
    }

    @Test
    fun `an unreadable file is set aside, not overwritten`() {
        val dir = tmp.newFolder("broken")
        File(dir, PromptStore.FILE).writeText("{not json")
        val store = PromptStore(AgentHome(dir))
        assertEquals(emptyList(), store.all())
        store.save(SavedPrompt("", "New", "Hi"))
        assertEquals("{not json", File(dir, "${PromptStore.FILE}.unreadable").readText())
        assertEquals(listOf("New"), store.all().map { it.name })
    }

    @Test
    fun `a file from a newer version still loads`() {
        val dir = tmp.newFolder("newer")
        File(dir, PromptStore.FILE).writeText("""{"schema":2,"color":"red","prompts":[{"id":"p1","name":"Hi","text":"Hello","icon":"wave"}]}""")
        assertEquals(listOf(SavedPrompt("p1", "Hi", "Hello")), PromptStore(AgentHome(dir)).all())
    }

    // ---- Finding one

    private val translate = SavedPrompt("1", "Translate", "Translate into {{field:Language}}")
    private val standup = SavedPrompt("2", "Daily standup", "What I did yesterday")
    private val review = SavedPrompt("3", "Code review", "Review this diff for bugs; translate comments too")
    private val resumen = SavedPrompt("4", "Résumé check", "Check my CV")

    @Test
    fun `search ranks name prefixes, then words, then text, ignoring case and accents`() {
        val all = listOf(translate, standup, review, resumen)
        assertEquals(all, PromptSearch.filter(all, ""))
        assertEquals(all, PromptSearch.filter(all, "  "))
        assertEquals(listOf(translate, review), PromptSearch.filter(all, "TRANS"))
        assertEquals(listOf(standup), PromptSearch.filter(all, "stand"))
        assertEquals(listOf(standup), PromptSearch.filter(all, "daily"))
        assertEquals(listOf(review), PromptSearch.filter(all, "bugs"))
        assertEquals(listOf(resumen), PromptSearch.filter(all, "resume"))
        assertEquals(emptyList(), PromptSearch.filter(all, "zzz"))
    }

    @Test
    fun `the preview is the first line with words`() {
        assertEquals("Second", PromptSearch.preview("\n  \n  Second  \nThird"))
        assertEquals("", PromptSearch.preview(""))
    }

    @Test
    fun `slash search is a slash and one word, nothing else`() {
        assertEquals("", SlashQuery.of("/"))
        assertEquals("tra", SlashQuery.of("/tra"))
        assertEquals("btw", SlashQuery.of("/btw"))
        // A command with its question, a path, a sentence or a second line is a message.
        assertNull(SlashQuery.of("/btw what is this?"))
        assertNull(SlashQuery.of("/usr/bin is missing"))
        assertNull(SlashQuery.of("/tra\nmore"))
        assertNull(SlashQuery.of(" /tra"))
        assertNull(SlashQuery.of("hello /tra"))
        assertNull(SlashQuery.of(""))
        assertNull(SlashQuery.of("/" + "x".repeat(41)))
    }
}
