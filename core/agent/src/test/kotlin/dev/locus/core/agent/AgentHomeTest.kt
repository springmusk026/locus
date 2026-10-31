package dev.locus.core.agent

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.home.homeTools
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentHomeTest {
    private val dir: File = Files.createTempDirectory("home").toFile()
    private val home = AgentHome(dir)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `memory index tracks topics and survives updates and forgetting`() {
        home.saveMemory("Coffee preferences", "Likes flat whites, no sugar", "The user drinks flat whites.")
        home.saveMemory("Home server", "Ubuntu box at 192.168.1.20", "SSH as sam.")
        assertTrue("- [coffee-preferences](coffee-preferences.md) — Likes flat whites, no sugar" in home.memoryIndex())
        assertTrue(home.saveMemory("coffee preferences", "Now oat milk", "Oat milk flat white."))
        assertTrue("Now oat milk" in home.memoryIndex())
        assertEquals("home-server", home.searchMemory("ssh server").first().first)
        home.forgetMemory("home server")
        assertTrue("home-server" !in home.memoryIndex())
    }

    @Test
    fun `skills are drafts until approved and follow the Agent Skills format`() {
        home.draftSkill("weekly-report", "Builds the weekly report.", "1. Collect\n2. Write", mapOf("template.md" to "# Week"))
        assertTrue(home.skills().isEmpty())
        assertEquals(1, home.skillDrafts().size)
        home.approveSkill("weekly-report")
        val skill = assertNotNull(home.skill("weekly-report"))
        assertEquals("Builds the weekly report.", skill.description)
        assertEquals(listOf("template.md"), skill.files)
        assertTrue(File(dir, "skills/weekly-report/SKILL.md").readText().startsWith("---\nname: weekly-report\ndescription: "))
        assertNull(home.skillFile("weekly-report", "../../memory/MEMORY.md"))
        assertFailsWith<IllegalArgumentException> { home.draftSkill("Bad Name", "x", "y", emptyMap()) }
    }

    @Test
    fun `connector calls inject secrets, stay on host and confirm writes`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { req -> seen += req; respond("""{"token":"sk-secret-123","ok":true}""") })
        val tools = ToolRegistry(homeTools(home, http, SecretResolver { if (it == "todo-token") "sk-secret-123" else null }))
        home.draftConnector(
            Connector(
                id = "todo", name = "Todo", description = "Tasks", baseUrl = "https://api.todo.test/v1",
                auth = Connector.Auth("bearer", secret = "todo-token"),
                operations = listOf(
                    Connector.Operation("get_task", "Get", "GET", "/tasks/{id}", listOf(Connector.Param("id", location = "path", required = true))),
                    Connector.Operation("delete_task", "Delete", "DELETE", "/tasks/{id}", listOf(Connector.Param("id", location = "path", required = true)), "destructive"),
                ),
            ),
        )
        val call = tools["connector_call"]!!
        val args = { op: String, id: String -> JsonObject(mapOf("connector" to JsonPrimitive("todo"), "operation" to JsonPrimitive(op), "arguments" to JsonObject(mapOf("id" to JsonPrimitive(id))))) }

        assertFailsWith<ToolException> { call.execute(args("get_task", "1")) } // still a draft
        home.approveConnector("todo")

        val result = call.execute(args("get_task", "../../admin"))
        assertEquals("https://api.todo.test/v1/tasks/..%2F..%2Fadmin", seen.last().url.toString())
        assertEquals("Bearer sk-secret-123", seen.last().headers["Authorization"])
        assertTrue("[secret]" in result.content && "sk-secret-123" !in result.content)
        assertNull(call.mustConfirm(args("get_task", "1")))
        assertNotNull(call.mustConfirm(args("delete_task", "1")))
    }

    @Test
    fun `snapshot lists memory, active skills and connectors only`() {
        home.saveMemory("name", "User is Sam", "Sam")
        home.draftSkill("draft-only", "Not yet approved.", "x", emptyMap())
        val snap = home.snapshot()
        assertTrue("User is Sam" in snap)
        assertTrue("draft-only" !in snap)
        assertTrue("(none yet)" in snap)
    }
}

class ConnectorScriptTest {
    @Test
    fun `script reshapes request and response without seeing the secret`() = kotlinx.coroutines.test.runTest {
        val dir = java.nio.file.Files.createTempDirectory("home").toFile()
        val home = AgentHome(dir)
        val seen = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { req -> seen += req; respond("""{"items":[{"t":"a"},{"t":"b"}]}""") })
        val evaluated = mutableListOf<String>()
        // Stand-in for the sandbox: recognise which hook is being called and answer like the script would.
        val engine = dev.locus.core.agent.home.ScriptEngine { code ->
            evaluated += code
            if ("JSON.stringify" in code) """{"path":"/v2/search","query":{"q":"x"}}""" else "2 items: a, b"
        }
        val tools = ToolRegistry(homeTools(home, http, SecretResolver { "tok-123456" }, engine))
        home.addConnector(
            Connector(
                id = "s", name = "S", description = "d", baseUrl = "https://api.s.test",
                auth = Connector.Auth("bearer", secret = "s-token"),
                operations = listOf(Connector.Operation("find", "Find", "GET", "/v1/find")),
                script = "function request(op, a){ return {path:'/v2/search', query:{q:'x'}} } function response(op, s, t){ return '2 items' }",
            ),
        )
        val result = tools["connector_call"]!!.execute(JsonObject(mapOf("connector" to JsonPrimitive("s"), "operation" to JsonPrimitive("find"))))
        assertEquals("https://api.s.test/v2/search?q=x", seen.single().url.toString())
        assertEquals("Bearer tok-123456", seen.single().headers["Authorization"])
        assertTrue("2 items" in result.content)
        assertTrue(evaluated.none { "tok-123456" in it })
        dir.deleteRecursively()
    }
}
