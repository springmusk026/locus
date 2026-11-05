package dev.locus.feature.chat

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import dev.locus.core.data.agent.SubagentRunner
import dev.locus.core.data.agent.SubagentView
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.HelperStop
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import dev.locus.core.model.SubagentStatus
import dev.locus.core.model.TokenUsage
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.core.screenshot.Step
import dev.locus.core.screenshot.PrivateComposable
import dev.locus.core.screenshot.OnScreen
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Helpers (sub-agents) in a turn's work: finished, failed and partial rows, the transcript sheet a
 * row opens (with the partial-result note), and Steer. The rows reach the helper runner through Hilt,
 * so the app here hands them the test graph's.
 */
@Config(application = HelperScreenshots.App::class)
class HelperScreenshots : ScreenshotTest() {

    /** An application that answers the rows' entry point with the test graph's runner. */
    class App : Application(), GeneratedComponentManager<Any> {
        override fun generatedComponent(): Any = object : GeneratedComponent, SubagentEntryPoint {
            override fun subagents(): SubagentRunner = runner ?: error("No runner set")
        }

        companion object {
            var runner: SubagentRunner? = null
        }
    }

    @get:Rule val main = MainDispatcherRule()

    private val scope = CoroutineScope(SupervisorJob())

    private val researchProvider = ProviderConfig("r", "OpenRouter", ApiFormat.OPENAI_CHAT, "https://openrouter.ai/api/v1", "google/gemini-3-pro")
    private val anthropic = ProviderConfig("a", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-sonnet-5-5")
    private val graph = TestGraph(scope, AppSettings(providers = listOf(anthropic, researchProvider), defaultProviderId = "a"))

    @After
    fun tearDown() {
        App.runner = null
        scope.cancel()
    }

    private var clock = Fx.T0

    private fun m(conversation: String, id: String, role: Role, vararg parts: Part, usage: TokenUsage? = null) =
        Message(id, conversation, role, parts.toList(), clock++, usage = usage)

    /** A saved helper chat: its row, its transcript and its final status. */
    private fun helper(id: String, callId: String, label: String, type: String, provider: String, model: String, status: SubagentStatus, messages: List<Message>) = runBlocking {
        graph.conversations.createHelper(id, "c", callId, type, label, provider, model, "You are a helper.")
        messages.forEach { graph.conversations.append(it) }
        graph.conversations.setHelperStatus(id, status)
    }

    private val docsTask = "Find what changed between Stripe API 2025-09-30 and 2026-03-15 for PaymentIntents. Return the renamed or removed fields with a link to each changelog entry."
    private val pricingTask = "Compare Hono and Fastify for a small JSON API on Node 22: cold start, routing speed and middleware for CORS and rate limits. A table, then a one-line recommendation."
    private val auditTask = "List every place in garden-app/src that reads process.env, with the variable and file:line."

    private fun seed() {
        App.runner = graph.subagents
        helper(
            "h1", "s1", "Researcher #1", "researcher", "r", "google/gemini-3-pro", SubagentStatus.COMPLETED,
            listOf(
                m("h1", "h1u", Role.USER, Part.Text(docsTask)),
                m("h1", "h1a", Role.ASSISTANT, Fx.call("w1", "web_search", "query" to "Stripe API changelog PaymentIntents 2026-03-15"), usage = TokenUsage(6_100, 220)),
                m("h1", "h1t", Role.TOOL, Fx.result("w1", "web_search", "1. Stripe changelog: 2026-03-15.acacia …")),
                m("h1", "h1b", Role.ASSISTANT, Part.Text("`payment_method_types` is replaced by `automatic_payment_methods`; `charges` was removed from the PaymentIntent object."), usage = TokenUsage(8_400, 310)),
            ),
        )
        helper(
            "h2", "s2", "Researcher #2", "researcher", "r", "google/gemini-3-pro", SubagentStatus.PARTIAL,
            listOf(
                m("h2", "h2u", Role.USER, Part.Text(pricingTask)),
                m("h2", "h2a", Role.ASSISTANT, Part.Text("Starting with cold start numbers from both projects' benchmarks."), Fx.call("f1", "fetch_url", "url" to "https://hono.dev/docs/concepts/benchmarks"), usage = TokenUsage(5_800, 180)),
                m("h2", "h2t", Role.TOOL, Fx.result("f1", "fetch_url", "Hono – Benchmarks: routers compared on Node 22 …")),
                m("h2", "h2b", Role.ASSISTANT, Fx.call("f2", "fetch_url", "url" to "https://fastify.dev/benchmarks/"), usage = TokenUsage(9_900, 140)),
                m("h2", "h2s", Role.USER, *dev.locus.core.agent.tools.SubagentPrompts.steer("Only use official sources").toTypedArray()),
                m("h2", "h2t2", Role.TOOL, Fx.result("f2", "fetch_url", "Fastify benchmarks …")),
                m("h2", "h2c", Role.ASSISTANT, Fx.call("f3", "fetch_url", "url" to "https://github.com/honojs/middleware/tree/main/packages"), usage = TokenUsage(14_200, 90)),
            ),
        )
        helper(
            "h3", "s3", "Code reader", "explorer", "a", "claude-sonnet-5-5", SubagentStatus.FAILED,
            listOf(
                m("h3", "h3u", Role.USER, Part.Text(auditTask)),
                m("h3", "h3a", Role.ASSISTANT, Fx.call("g1", "search_files", "query" to "process.env"), usage = TokenUsage(4_200, 60)),
                m("h3", "h3t", Role.TOOL, Fx.result("g1", "search_files", "No such folder: garden-app/src", error = true)),
            ),
        )
    }

    private fun spawn(callId: String, task: String, result: String?) = ChatItem.ToolCall(
        "a1:$callId", "agent_spawn", """{"task":${kotlinx.serialization.json.JsonPrimitive(task)},"agent_type":"researcher"}""",
        result?.let { Part.ToolResult(callId, "agent_spawn", it, isError = false) },
    )

    @Test
    fun rows() {
        seed()
        snapAfter("helper-rows", emptyList(), heightDp = 520) {
            OnScreen {
                LocusCard(contentPadding = PaddingValues(0.dp)) {
                    SubagentRow(spawn("s1", docsTask, "<subagent_report agent_id=\"h1\">…</subagent_report>"))
                    SubagentRow(spawn("s2", pricingTask, "<subagent_report agent_id=\"h2\" stopped=\"timeout\">…</subagent_report>"))
                    SubagentRow(spawn("s3", auditTask, "<subagent_report agent_id=\"h3\">Failed</subagent_report>"))
                    // Refused before it started: a plain step with the reason.
                    SubagentRow(
                        ChatItem.ToolCall(
                            "a1:s4", "agent_spawn", """{"task":"Draft the release notes","agent_type":"writer"}""",
                            Part.ToolResult("s4", "agent_spawn", "Not started: this chat already has 3 helpers working (the limit). Wait for one with agent_wait.", isError = true),
                        ),
                    )
                }
            }
        }
    }

    private fun view(id: String, label: String, status: SubagentStatus, steps: Int, tokens: Long, tool: String? = null, partial: Boolean = false, stop: HelperStop? = null) =
        SubagentView(id, label, "researcher", "google/gemini-3-pro", "OpenRouter", "https://openrouter.ai/api/v1", status, steps, tool, tokens, false, false, stop = stop, partial = partial)

    @Test
    fun transcriptPartial() {
        seed()
        snapAfter("sheet-helper-transcript-partial", emptyList(), heightDp = 1200) {
            PrivateComposable(
                "dev.locus.feature.chat.SubagentRowKt", "SubagentTranscriptSheet",
                view("h2", "Researcher #2", SubagentStatus.PARTIAL, 3, 30_310, partial = true, stop = HelperStop.TIMEOUT),
                pricingTask, graph.subagents, partialNote(HelperStop.TIMEOUT), {}, {},
            )
        }
    }

    @Test
    fun transcriptDone() {
        seed()
        snapAfter("sheet-helper-transcript", emptyList(), heightDp = 1000) {
            PrivateComposable(
                "dev.locus.feature.chat.SubagentRowKt", "SubagentTranscriptSheet",
                view("h1", "Researcher #1", SubagentStatus.COMPLETED, 2, 15_030),
                docsTask, graph.subagents, null, {}, {},
            )
        }
    }

    /** A working helper's transcript: its live step, with Steer and Stop. */
    @Test
    fun transcriptRunning() {
        seed()
        snapAfter("sheet-helper-transcript-running", emptyList(), heightDp = 1000) {
            PrivateComposable(
                "dev.locus.feature.chat.SubagentRowKt", "SubagentTranscriptSheet",
                view("h1", "Researcher #1", SubagentStatus.RUNNING, 2, 15_030, tool = "fetch_url"),
                docsTask, graph.subagents, null, {}, {},
            )
        }
    }

    @Test
    fun steer() {
        App.runner = graph.subagents
        snapAfter("sheet-helper-steer", listOf(Step.Type("Only use official sources, and skip blog posts")), heightDp = 500) {
            PrivateComposable(
                "dev.locus.feature.chat.SubagentRowKt", "SteerDialog",
                view("h2", "Researcher #2", SubagentStatus.RUNNING, 3, 30_310, tool = "fetch_url"), graph.subagents, {},
            )
        }
    }

    @Test
    fun steerEmpty() {
        App.runner = graph.subagents
        snap("sheet-helper-steer-empty", heightDp = 500) {
            PrivateComposable(
                "dev.locus.feature.chat.SubagentRowKt", "SteerDialog",
                view("h2", "Researcher #2", SubagentStatus.RUNNING, 3, 30_310, tool = "fetch_url"), graph.subagents, {},
            )
        }
    }
}
