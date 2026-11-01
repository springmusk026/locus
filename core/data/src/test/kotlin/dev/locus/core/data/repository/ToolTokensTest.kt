package dev.locus.core.data.repository

import android.content.ContextWrapper
import dev.locus.core.agent.SecretScrubber
import dev.locus.core.agent.SentView
import dev.locus.core.agent.ToolDiscovery
import dev.locus.core.agent.ToolRegistry
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.llm.AnthropicChatModel
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.OpenAiChatModel
import dev.locus.core.llm.OpenAiResponsesModel
import dev.locus.core.llm.wireBody
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import dev.locus.core.model.ToolSpec
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Tool-definition tokens per provider, before (every tool declared) and after (tool discovery's v1 core
 * set), counted the way the "What was sent" viewer counts them: the `tools` part of each adapter's own
 * request body, about four characters a token ([SentView.estimateTokens]). The numbers are written to
 * `build/tool-tokens.txt` and quoted in docs/design/tool-discovery.md.
 */
class ToolTokensTest {
    private inline fun <reified T> blank(): T {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, T::class.java) as T
    }

    private fun everyTool(): ToolRegistry {
        val dir: File = Files.createTempDirectory("tokens").toFile()
        return ToolRegistry(
            ToolCatalog.allTools(
                workspace = Workspace(File(dir, "workspace"), File(dir, "trash")), http = HttpClient(OkHttp), browser = blank(), linux = blank(),
                home = AgentHome(File(dir, "home")), secrets = SecretResolver { null }, mcp = blank(), ssh = blank(), settings = blank(),
                context = blank<ContextWrapper>(), scripts = blank(), conversations = blank(), asker = dev.locus.core.agent.tools.QuestionDesk(),
                subagents = { error("not started") },
            ),
        )
    }

    private val http = HttpClient(OkHttp)
    private val adapters: List<Pair<String, ChatModel>> = listOf(
        "Anthropic Messages" to AnthropicChatModel(http, ProviderConfig("a", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5"), "k"),
        "OpenAI Responses" to OpenAiResponsesModel(http, ProviderConfig("o", "OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-5.5"), "k"),
        "OpenAI-compatible chat" to OpenAiChatModel(http, ProviderConfig("c", "OpenRouter", ApiFormat.OPENAI_CHAT, "https://openrouter.ai/api/v1", "some/model"), "k"),
        "Gemini (compatibility layer)" to OpenAiChatModel(http, ProviderConfig("g", "Gemini", ApiFormat.OPENAI_CHAT, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3-pro"), "k"),
    )

    private fun tokens(model: ChatModel, tools: List<ToolSpec>): Int {
        val request = ChatRequest("system", listOf(Message("u", "c", Role.USER, listOf(Part.Text("hi")), 0)), tools)
        val body = model.wireBody(request)!!
        return SentView.fromBody(model.format, "p", "m", "https://x", body, SecretScrubber(emptyList())).estimate.tools
    }

    /** On device: the tools LiteRT-LM is given (OnDeviceText's list), as name, description and schema text. */
    private fun onDevice(tools: List<ToolSpec>): Int {
        val request = ChatRequest("system", emptyList(), tools)
        return SentView.estimateTokens(dev.locus.core.ondevice.OnDeviceText.tools(request).sumOf { it.name.length + it.description.length + it.inputSchema.toString().length })
    }

    @Test
    fun `tool discovery cuts the declared tool definitions on every provider`() {
        val all = everyTool()
        val discovery = all.deferring(ToolDiscovery.CORE_V1, null)
        val report = StringBuilder("tools declared: ${all.specs.size} before, ${discovery.specs.size} after (${discovery.deferredSpecs.size} deferred)\n")
        for ((name, model) in adapters) {
            val before = tokens(model, all.specs)
            val after = tokens(model, discovery.specs)
            report.append("$name: $before -> $after tokens (${100 - after * 100 / before}% less)\n")
            assertTrue(after * 2 < before, "$name: $before -> $after")
        }
        val before = onDevice(all.specs)
        val after = onDevice(discovery.specs)
        report.append("On device (LiteRT-LM): $before -> $after tokens\n")
        File("build/tool-tokens.txt").writeText(report.toString())
        println(report)
    }
}
