package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `output_config.task_budget` (https://platform.claude.com/docs/en/build-with-claude/task-budgets). */
class AnthropicTaskBudgetTest {
    private val config = ProviderConfig("p", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5")
    private val messages = listOf(Message("m1", "c", Role.USER, listOf(Part.Text("hi")), 0))
    private val done = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_1","role":"assistant","content":[]}}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
    )

    private suspend fun send(config: ProviderConfig, budget: Long?): Pair<JsonObject, String?> {
        val engine = RecordingEngine(body = done)
        AnthropicChatModel(engine.client, config, "key").stream(ChatRequest("s", messages, emptyList(), taskBudget = budget)).toList()
        return LlmJson.parseToJsonElement(engine.lastBody()).jsonObject to engine.requests.last().headers["anthropic-beta"]
    }

    @Test
    fun `a token budget on a supported model sends task_budget with the beta header, next to effort`() = runTest {
        val (body, betas) = send(config, 200_000)
        val output = body["output_config"]!!.jsonObject
        assertEquals("high", output["effort"]!!.jsonPrimitive.content)
        val budget = output["task_budget"]!!.jsonObject
        assertEquals("tokens", budget["type"]!!.jsonPrimitive.content)
        assertEquals(200_000L, budget["total"]!!.jsonPrimitive.content.toLong())
        // Only total: `remaining` changing per request would miss the prompt cache.
        assertFalse("remaining" in budget)
        assertTrue("task-budgets-2026-03-13" in betas.orEmpty().split(","))
    }

    @Test
    fun `the same budget gives byte-identical requests, so the cached prefix holds`() = runTest {
        assertEquals(send(config, 200_000).first, send(config, 200_000).first)
    }

    @Test
    fun `no budget, a budget under the 20k minimum, another host or an unlisted model sends none`() = runTest {
        for ((c, b) in listOf(
            config to null,
            config to 19_999L,
            config.copy(baseUrl = "https://gateway.example.com") to 200_000L,
            config.copy(model = "claude-sonnet-5-5") to 200_000L,
            config.copy(model = "claude-sonnet-5") to 200_000L,
            config.copy(model = "claude-haiku-4-5") to 200_000L,
        )) {
            val (body, betas) = send(c, b)
            assertNull(body["output_config"]?.jsonObject?.get("task_budget"), "${c.model} @ ${c.baseUrl} with $b")
            assertFalse("task-budgets-2026-03-13" in betas.orEmpty(), "${c.model} @ ${c.baseUrl} with $b")
        }
    }

    @Test
    fun `a model without effort still gets output_config for the budget alone`() = runTest {
        val (body, _) = send(config.copy(effort = ""), 50_000)
        val output = body["output_config"]!!.jsonObject
        assertFalse("effort" in output)
        assertEquals(50_000L, output["task_budget"]!!.jsonObject["total"]!!.jsonPrimitive.content.toLong())
    }
}
