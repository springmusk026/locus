package dev.locus.core.agent

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.AgentHome.PersonaFile
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonaTest {
    private val dir: File = Files.createTempDirectory("home").toFile()
    private var now = 0L
    private val home = AgentHome(dir) { ++now }

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `defaults to Locus with no persona files`() {
        assertEquals("Locus", home.agentName())
        val prompt = SystemPrompt.build("", home.snapshot())
        assertTrue(prompt.startsWith("You are Locus,"))
        assertFalse("<soul" in prompt)
    }

    @Test
    fun `persona files reach the prompt and the name is used`() {
        home.writePersona(PersonaFile.IDENTITY, AgentHome.identityMarkdown("Juno", "calm, direct"))
        home.writePersona(PersonaFile.SOUL, "# Soul\nBe brief.")
        home.writePersona(PersonaFile.USER, "Sam, lives in Lisbon.")
        assertEquals("Juno", home.agentName())
        val snap = home.snapshot()
        assertTrue("<soul file=\"SOUL.md\">\n# Soul\nBe brief.\n</soul>" in snap)
        assertTrue("Lisbon" in snap)
        assertFalse("<operating_rules" in snap)
        assertTrue(SystemPrompt.build("", snap).startsWith("You are Juno,"))
    }

    @Test
    fun `name comes only from the identity block, not from a skill called name`() {
        home.draftSkill("name", "A skill whose catalog line looks like a name field.", "x", emptyMap())
        home.approveSkill("name")
        assertEquals("Locus", SystemPrompt.nameFromSnapshot(home.snapshot()))
    }

    @Test
    fun `blank content removes a file and old versions are archived`() {
        home.writePersona(PersonaFile.AGENTS, "Rule one.")
        home.writePersona(PersonaFile.AGENTS, "Rule two.")
        home.writePersona(PersonaFile.AGENTS, "")
        assertEquals("", home.readPersona(PersonaFile.AGENTS))
        assertEquals(2, File(dir, "history/persona").listFiles().orEmpty().size)
    }

    @Test
    fun `persona text is capped in the snapshot`() {
        home.writePersona(PersonaFile.USER, "x".repeat(10_000))
        assertTrue(home.snapshot().length < 6_000)
    }
}
