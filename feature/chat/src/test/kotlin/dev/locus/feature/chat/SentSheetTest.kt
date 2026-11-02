package dev.locus.feature.chat

import dev.locus.core.agent.SentView
import dev.locus.core.model.ApiFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentSheetTest {
    private fun view(host: String?, format: ApiFormat, params: List<Pair<String, String>>, reported: Int?) = SentView(
        format, "Anthropic", "claude-opus-5-5", host, params, "sys", emptyList(), emptyList(),
        SentView.Estimate(1, 0, 0), reported, "{}",
    )

    @Test
    fun `request rows name the endpoint and list settings once`() {
        val rows = requestRows(view("api.anthropic.com", ApiFormat.ANTHROPIC_MESSAGES, listOf("model" to "claude-opus-5-5", "max_tokens" to "64000"), 12_345))
        assertEquals(listOf("Provider", "Model", "Endpoint", "Format", "max_tokens", "Prompt tokens"), rows.map { it.first })
        assertEquals("api.anthropic.com", rows[2].second)
        assertTrue(rows.last().second.startsWith("12"))
    }

    @Test
    fun `on device has no host`() {
        val rows = requestRows(view(null, ApiFormat.ON_DEVICE, emptyList(), null))
        assertEquals("On this phone", rows.first { it.first == "Endpoint" }.second)
        assertEquals("LiteRT-LM", rows.first { it.first == "Format" }.second)
    }
}
