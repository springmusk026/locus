package dev.locus.core.agent

import dev.locus.core.llm.LlmJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Compact MCP schemas keep what a valid call needs; arguments are checked against them before a call goes out. */
class JsonSchemasTest {
    private fun json(text: String): JsonObject = LlmJson.parseToJsonElement(text).jsonObject

    private val schema = json(
        """
        {"type":"object","title":"CreateIssue","${'$'}schema":"http://json-schema.org/draft-07/schema#",
         "properties":{
           "title":{"type":"string","description":"Issue title","title":"Title"},
           "priority":{"type":"integer","enum":[0,1,2,3,4],"description":"0 none … 4 low"},
           "state":{"type":"string","enum":["todo","doing","done"]},
           "labels":{"type":"array","items":{"type":"string"}},
           "assignee":{"type":"object","properties":{"id":{"type":"string","description":"${"x".repeat(500)}"},"email":{"type":"string","format":"email"}},"required":["id"],"additionalProperties":false},
           "due":{"anyOf":[{"type":"string","format":"date"},{"type":"null"}]},
           "parent":{"${'$'}ref":"#/${'$'}defs/ref"}
         },
         "required":["title"],
         "additionalProperties":false,
         "${'$'}defs":{"ref":{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}},
         "examples":[{"title":"a"},{"title":"b"},{"title":"c"}]}
        """.trimIndent(),
    )

    @Test
    fun `compact keeps descriptions, enums, nesting and refs and drops titles`() {
        val c = JsonSchemas.compact(schema)
        val text = c.toString()
        assertFalse("\"title\":\"CreateIssue\"" in text || "\"title\":\"Title\"" in text || "\$schema" in text, text)
        assertTrue("Issue title" in text && "[0,1,2,3,4]" in text && "\"todo\"" in text, text)
        assertTrue("\"format\":\"email\"" in text && "\"anyOf\"" in text && "\"\$ref\":\"#/\$defs/ref\"" in text, text)
        assertTrue("\"additionalProperties\":false" in text)
        // Long argument descriptions are cut; at most two examples remain.
        assertTrue(text.length < schema.toString().length)
        assertTrue("x".repeat(JsonSchemas.ARGUMENT_DESCRIPTION_CHARS) !in text)
        assertEquals(2, (c["examples"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun `valid arguments pass`() {
        val ok = json("""{"title":"Bug","priority":2,"state":"todo","labels":["a"],"assignee":{"id":"u1"},"due":null,"parent":{"id":"p"}}""")
        assertEquals(emptyList(), JsonSchemas.problems(schema, ok))
        assertEquals(emptyList(), JsonSchemas.problems(schema, json("""{"title":"Bug","due":"2026-11-01"}""")))
    }

    @Test
    fun `problems name the path and what's expected`() {
        val bad = json("""{"priority":"high","state":"blocked","labels":[1],"assignee":{"email":"a@b"},"color":"red","due":5,"parent":{}}""")
        val problems = JsonSchemas.problems(schema, bad)
        val all = problems.joinToString("\n")
        assertTrue("arguments.title is required" in all, all)
        assertTrue("arguments.priority must be integer, got a string" in all, all)
        assertTrue("arguments.state must be one of \"todo\", \"doing\", \"done\"" in all, all)
        assertTrue("arguments.labels[0] must be string" in all, all)
        assertTrue("arguments.assignee.id is required" in all, all)
        assertTrue(problems.size <= JsonSchemas.MAX_PROBLEMS)
        val more = JsonSchemas.problems(schema, json("""{"title":"x","color":"red","due":5,"parent":{}}""")).joinToString("\n")
        assertTrue("arguments.color isn't an argument this tool takes" in more, more)
        assertTrue("arguments.due doesn't match any of the allowed forms" in more, more)
        assertTrue("arguments.parent.id is required" in more, more)
    }

    @Test
    fun `what it doesn't understand counts as fitting`() {
        val open = json("""{"type":"object","properties":{"q":{"type":"string"}}}""")
        assertEquals(emptyList(), JsonSchemas.problems(open, json("""{"q":"x","extra":1}""")))
        assertEquals(emptyList(), JsonSchemas.problems(json("""{"type":"object","properties":{"n":{"type":"number"}}}"""), json("""{"n":1.5}""")))
        assertEquals(emptyList(), JsonSchemas.problems(json("""{"type":"object","properties":{"x":{"${'$'}ref":"https://elsewhere"}}}"""), json("""{"x":1}""")))
    }
}
