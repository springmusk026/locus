package dev.locus.feature.onboarding

import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolResult
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import kotlin.test.assertEquals

class PermissionProfileTest {
    private fun tool(name: String, risk: ToolRisk, default: ToolPolicy? = null) = object : Tool {
        override val spec = ToolSpec(name, "", JsonObject(emptyMap()))
        override val risk = risk
        override val defaultPolicy = default ?: super.defaultPolicy
        override fun describe(args: JsonObject) = name
        override suspend fun execute(args: JsonObject) = ToolResult("")
    }

    private val tools = listOf(
        tool("read_file", ToolRisk.READ),
        tool("write_file", ToolRisk.WRITE),
        tool("browser_open", ToolRisk.NETWORK, ToolPolicy.ALLOW),
        tool("run_command", ToolRisk.EXECUTE),
        tool("ssh_exec", ToolRisk.EXECUTE),
        tool("blocked", ToolRisk.WRITE, ToolPolicy.DENY),
    )

    @Test
    fun `balanced clears every override`() {
        assertEquals(emptyMap(), PermissionProfile.Balanced.overrides(tools))
    }

    @Test
    fun `careful asks for anything that is not a plain read`() {
        assertEquals(mapOf("browser_open" to ToolPolicy.ASK), PermissionProfile.Careful.overrides(tools))
    }

    @Test
    fun `autonomous allows most but keeps remote machines and denials`() {
        assertEquals(
            mapOf("write_file" to ToolPolicy.ALLOW, "run_command" to ToolPolicy.ALLOW),
            PermissionProfile.Autonomous.overrides(tools),
        )
    }
}
