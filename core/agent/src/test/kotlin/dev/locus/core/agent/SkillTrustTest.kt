package dev.locus.core.agent

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.home.SkillBundle
import dev.locus.core.agent.home.SkillSource
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What skill_use and skill_read_file return from a skill someone else wrote is outside content, and taints the run. */
class SkillTrustTest {
    private val dir: File = Files.createTempDirectory("skilltrust").toFile()
    private val home = AgentHome(dir)
    private val tools = ToolRegistry(homeTools(home, HttpClient(MockEngine { respond("") }), SecretResolver { null }, origins = { emptySet() }))

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private suspend fun use(name: String) = tools["skill_use"]!!.execute(JsonObject(mapOf("name" to JsonPrimitive(name)))).content
    private suspend fun read(name: String, path: String) =
        tools["skill_read_file"]!!.execute(JsonObject(mapOf("name" to JsonPrimitive(name), "path" to JsonPrimitive(path)))).content

    private fun skillMd(name: String) = "---\nname: $name\ndescription: Does $name.\n---\nStep one."

    @Test
    fun `an imported skill is outside content`() = runTest {
        val bundle = SkillBundle(
            name = "from-github",
            description = "Does from-github.",
            skillMd = skillMd("from-github"),
            files = mapOf("ref.md" to "Reference".toByteArray()),
            source = SkillSource(kind = "github", url = "https://github.com/someone/skills", repo = "someone/skills"),
        )
        home.draftImportedSkill(bundle)
        home.approveSkill("from-github")
        val used = use("from-github")
        assertTrue(used.startsWith("<skill_content name=\"from-github\">"), used)
        assertTrue(UntrustedContent.isMarked(used) && "someone/skills" in used, used)
        assertEquals("from-github", SkillScope.loadedName(used))
        assertTrue(Taint.isSource("skill_use", used))
        val file = read("from-github", "ref.md")
        assertTrue(UntrustedContent.isMarked(file) && "Reference" in file, file)
        assertTrue(Taint.isSource("skill_read_file", file))
    }

    @Test
    fun `a skill the agent drafted is outside content, even once approved`() = runTest {
        home.draftSkill("drafted", "Drafted by the agent.", "Step one.", mapOf("t.md" to "T"))
        home.approveSkill("drafted")
        val used = use("drafted")
        assertTrue(UntrustedContent.isMarked(used) && "drafted by the agent" in used, used)
        assertTrue(UntrustedContent.isMarked(read("drafted", "t.md")))
    }

    @Test
    fun `a skill the user wrote stays trusted`() = runTest {
        File(dir, "skills/mine").apply { mkdirs() }.let { d ->
            File(d, "SKILL.md").writeText(skillMd("mine"))
            File(d, "notes.md").writeText("My notes")
        }
        val used = use("mine")
        assertFalse(UntrustedContent.isMarked(used), used)
        assertFalse(Taint.isSource("skill_use", used))
        assertEquals("mine", SkillScope.loadedName(used))
        assertEquals("My notes", read("mine", "notes.md"))
    }
}
