package dev.locus.core.data.repository

import android.content.ContextWrapper
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.model.ToolCapability
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every tool the app registers has a capability (Read-only / Workspace / Full depend on it). A new
 * tool fails here until it is added to [ToolCapability.BY_TOOL].
 */
class ToolCatalogCapabilityTest {
    /**
     * The catalogue's Android-backed dependencies are only stored by the tool factories, never called,
     * so instances made without running their constructors are enough to list the tools.
     */
    private inline fun <reified T> blank(): T {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, T::class.java) as T
    }

    @Test
    fun `every registered tool has a capability`() {
        val dir: File = Files.createTempDirectory("catalog").toFile()
        val tools = ToolCatalog.allTools(
            workspace = Workspace(File(dir, "workspace"), File(dir, "trash")),
            http = HttpClient(OkHttp),
            browser = blank(),
            linux = blank(),
            home = AgentHome(File(dir, "home")),
            secrets = SecretResolver { null },
            mcp = blank(),
            ssh = blank(),
            settings = blank(),
            context = blank<ContextWrapper>(),
            scripts = blank(),
            conversations = blank(),
            asker = dev.locus.core.agent.tools.QuestionDesk(),
            subagents = { error("not started in this test") },
        )
        // find_tools and call_tool join the list in chats with tool discovery (ToolRegistry.deferring).
        val names = dev.locus.core.agent.ToolRegistry(tools).deferring(emptySet(), null).all.map { it.spec.name }
        assertTrue(names.size > 90, "expected the full catalogue, got ${names.size}")
        assertEquals(emptyList(), names.filterNot { it in ToolCapability.BY_TOOL }, "tools without a capability")
        // And the map names no tool that doesn't exist (a renamed tool would otherwise fall back to OUTWARD silently).
        assertEquals(emptyList(), ToolCapability.BY_TOOL.keys.filterNot { it in names }, "classified tools that aren't registered")
    }
}
