package dev.locus.feature.onboarding

import dev.locus.core.agent.home.AgentHome
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonaTemplatesTest {
    private val full = PersonaAnswers(
        agentName = "  Nova ",
        style = TalkStyle.Concise,
        autonomy = Autonomy.CheckIn,
        areas = setOf(HelpArea.Research, HelpArea.Coding),
        userName = "Basanta Sharma",
        callMe = "Basanta",
        timeZone = "Asia/Kathmandu",
        location = "Nepal",
        language = "English",
        always = "- Use metric units\n\n2. Cite sources",
        never = "Email anyone without showing me the draft",
    )

    @Test fun `identity keeps a name line AgentHome can read`() {
        val files = PersonaTemplates.generate(full)
        assertEquals("Nova", AgentHome.nameIn(files.identity))
        assertTrue("Vibe: ${TalkStyle.Concise.vibe}" in files.identity)
        assertTrue("Basanta's personal AI agent" in files.identity)
    }

    @Test fun `blank name falls back to Locus`() {
        assertEquals("Locus", AgentHome.nameIn(PersonaTemplates.identity(PersonaAnswers(agentName = "  "))))
    }

    @Test fun `user file lists only answered fields, areas in a stable order`() {
        val user = PersonaTemplates.user(full)
        assertEquals(
            "# User\n\n- Name: Basanta Sharma\n- Call them: Basanta\n- Time zone: Asia/Kathmandu\n- Location: Nepal\n- Language: English\n- Wants help with: coding, research",
            user,
        )
        assertEquals("", PersonaTemplates.user(PersonaAnswers()))
        assertFalse("Call them" in PersonaTemplates.user(PersonaAnswers(userName = "Ann", callMe = "ann")))
    }

    @Test fun `agents file has autonomy, focus and cleaned always and never rules`() {
        val agents = PersonaTemplates.agents(full)
        Autonomy.CheckIn.rules.forEach { assertTrue(it in agents) }
        assertTrue(HelpArea.Coding.rule in agents && HelpArea.Research.rule in agents)
        assertTrue(agents.indexOf("Coding:") < agents.indexOf("Research:"))
        assertTrue("## Always\n- Use metric units\n- Cite sources" in agents)
        assertTrue("## Never\n- Email anyone without showing me the draft" in agents)
        val bare = PersonaTemplates.agents(PersonaAnswers())
        assertFalse("## Focus" in bare || "## Always" in bare || "## Never" in bare)
    }

    @Test fun `soul uses the language and stays short`() {
        val soul = PersonaTemplates.soul(full)
        assertTrue("Reply in English" in soul)
        assertTrue("Basanta's time" in soul)
        PersonaTemplates.generate(full).let { f -> listOf(f.identity, f.soul, f.user, f.agents).forEach { assertTrue(it.length < 1_200, it) } }
    }

    @Test fun `answers round-trip through the saved file with the fingerprint`() {
        val files = PersonaTemplates.generate(full)
        val decoded = assertNotNull(PersonaTemplates.decode(PersonaTemplates.encode(full, files)))
        assertEquals(full, decoded.first)
        assertEquals(files.fingerprint(), decoded.second)
    }

    @Test fun `answers are recovered from hand-written files`() {
        val files = PersonaFiles(
            identity = "# Identity\n\n- **Name:** Juno\n- Vibe: ${TalkStyle.Formal.vibe}",
            soul = "",
            user = "# About me\n- Name: Ann\n- Timezone: Europe/Oslo\n- Language: Norwegian",
            agents = "",
        )
        val a = PersonaTemplates.fromFiles(files, PersonaAnswers(timeZone = "UTC", location = "Norway"))
        assertEquals("Juno", a.agentName)
        assertEquals(TalkStyle.Formal, a.style)
        assertEquals("Ann", a.userName)
        assertEquals("Europe/Oslo", a.timeZone)
        assertEquals("Norway", a.location)
        assertEquals("Norwegian", a.language)
    }

    @Test fun `polished reply is split into files and keeps the name`() {
        val reply = """
            Sure! Here they are.
            === IDENTITY.md ===
            # Identity
            I am a helpful agent.
            === SOUL.md ===
            ```markdown
            # Soul
            Be brief.
            ```
            === USER.md ===
            # User
            - Name: Basanta
            === AGENTS.md ===
            (empty)
        """.trimIndent()
        val files = assertNotNull(PersonaTemplates.parsePolished(reply, full))
        assertEquals("Nova", AgentHome.nameIn(files.identity))
        assertEquals("# Soul\nBe brief.", files.soul)
        assertEquals("# User\n- Name: Basanta", files.user)
        assertEquals("", files.agents)
    }

    @Test fun `polished reply missing a file is rejected`() {
        assertNull(PersonaTemplates.parsePolished("=== IDENTITY.md ===\n- Name: X\n=== SOUL.md ===\nhi", full))
    }

    @Test fun `polish prompt carries the drafts and markers`() {
        val drafts = PersonaTemplates.generate(full)
        val prompt = PersonaTemplates.polishPrompt(full, drafts)
        assertTrue("=== AGENTS.md ===" in prompt && drafts.agents in prompt)
    }
}
