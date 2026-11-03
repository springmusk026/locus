package dev.locus.core.agent

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.home.homeTools
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A skill or helper name is a folder name, never a path: skill_read_file once used the name as its
 * root unchecked, so `name = "../.."` read Locus's other private files without asking.
 */
class SkillNameTraversalTest {
    private val base: File = Files.createTempDirectory("traversal").toFile()
    private val dir = File(base, "home").apply { mkdirs() }
    private val home = AgentHome(dir)
    private val secret = File(base, "secret.txt").apply { writeText("app-private") }

    init {
        File(dir, "memory/MEMORY.md").apply { parentFile.mkdirs(); writeText("- private note") }
        home.draftSkill("weekly-report", "Builds the weekly report.", "1. Collect", mapOf("template.md" to "# Week"))
        home.approveSkill("weekly-report")
    }

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private val badNames = listOf("..", "../..", "../memory", "weekly-report/..", base.absolutePath, File(dir, "skills/weekly-report").absolutePath, "")

    @Test
    fun `skill_read_file refuses names that leave the skills folder`() = runTest {
        val tools = ToolRegistry(homeTools(home, HttpClient(MockEngine { respond("") }), SecretResolver { null }))
        val tool = tools["skill_read_file"]!!
        suspend fun read(name: String, path: String): ToolResult = try {
            tool.execute(JsonObject(mapOf("name" to JsonPrimitive(name), "path" to JsonPrimitive(path))))
        } catch (e: ToolException) {
            ToolResult(e.message.orEmpty(), isError = true)
        }
        for (name in badNames) {
            assertTrue(read(name, "secret.txt").isError, name)
            assertTrue(read(name, "memory/MEMORY.md").isError, name)
            assertTrue(read(name, "home/memory/MEMORY.md").isError, name)
            assertNull(home.skillFile(name, "secret.txt"), name)
            assertNull(home.skillFile(name, "MEMORY.md"), name)
            assertNull(home.skill(name), name)
            assertFalse(home.hasSkill(name), name)
            assertNull(home.skillManifest(name), name)
            assertNull(home.skillContentHash(name, draft = false), name)
            assertNull(home.skillFileBytes(name, "secret.txt", draft = false), name)
            assertNull(home.agent(name), name)
        }
        // A valid read still works.
        val ok = read("weekly-report", "template.md")
        assertFalse(ok.isError)
        // An agent-drafted skill comes back fenced as untrusted (#133); the file itself is unchanged.
        assertTrue(ok.content.contains("\n# Week\n"), ok.content)
        assertEquals("# Week", home.skillFile("weekly-report", "template.md"))
    }

    @Test
    fun `a skill folder that is a symbolic link is not a skill`() {
        val outside = File(base, "outside").apply { mkdirs() }
        File(outside, "SKILL.md").writeText("---\nname: linked\ndescription: x\n---\nbody")
        File(outside, "notes.txt").writeText("outside")
        Files.createSymbolicLink(File(dir, "skills/linked").toPath(), outside.toPath())
        assertNull(home.skill("linked"))
        assertNull(home.skillFile("linked", "notes.txt"))
        assertFalse(home.hasSkill("linked"))
        assertTrue(home.skills().none { it.name == "linked" })
        assertFailsWith<IllegalArgumentException> { home.removeSkill("linked") }
        assertTrue(File(outside, "notes.txt").isFile, "removing must never follow the link")
    }

    @Test
    fun `mutating entry points reject bad names instead of touching other folders`() {
        for (name in listOf("..", "../..", "../memory", base.absolutePath)) {
            assertFailsWith<IllegalArgumentException>(name) { home.rejectSkill(name) }
            assertFailsWith<IllegalArgumentException>(name) { home.removeSkill(name) }
            assertFailsWith<IllegalArgumentException>(name) { home.approveSkill(name) }
            assertFailsWith<IllegalArgumentException>(name) { home.rejectAgent(name) }
            assertFailsWith<IllegalArgumentException>(name) { home.removeAgent(name) }
            assertFailsWith<IllegalArgumentException>(name) { home.approveAgent(name) }
        }
        assertTrue(secret.isFile)
        assertTrue(File(dir, "memory/MEMORY.md").isFile)
        assertTrue(home.hasSkill("weekly-report"))
    }
}
