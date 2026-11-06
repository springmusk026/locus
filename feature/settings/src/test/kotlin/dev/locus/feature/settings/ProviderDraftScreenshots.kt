package dev.locus.feature.settings

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ProviderDraft
import dev.locus.core.model.ProviderDraftApi
import dev.locus.core.model.SettingsDestination
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.core.screenshot.Variant
import org.junit.Test

/**
 * Provider drafts (docs/design/provider-drafts.md): the review sheet for a known host, an unfamiliar one, a
 * draft made after reading a web page, and a local server that needs no key; and the providers list with
 * a draft waiting. At 360 light, 411 dark and 360 at 200% font.
 */
class ProviderDraftScreenshots : ScreenshotTest() {
    private val variants = listOf(Variant.Light360, Variant.Dark411, Variant.Font200)

    private val groq = ProviderDraft(
        "draft-groq", "Groq", ProviderDraftApi.OPENAI_CHAT, "https://api.groq.com/openai/v1",
        models = listOf("llama-3.3-70b-versatile", "qwen/qwen3-32b"), conversationId = "c1", chatTitle = "Faster model for coding", userNamedHost = true,
    )
    private val unfamiliar = ProviderDraft(
        "draft-relay", "Fast relay", ProviderDraftApi.OPENAI_CHAT, "https://api.llm-relay.example/v1",
        models = listOf("gpt-5.2"), headers = mapOf("X-Client" to "locus"), conversationId = "c2", chatTitle = "Cheaper GPT access",
    )
    private val tainted = ProviderDraft(
        "draft-evil", "Default", ProviderDraftApi.OPENAI_CHAT, "https://evil.example/v1",
        conversationId = "c3", chatTitle = "Summarise this blog post", taintSources = listOf("web_read"),
    )
    private val ollama = ProviderDraft(
        "draft-ollama", "Ollama on the desktop", ProviderDraftApi.OPENAI_CHAT, "http://192.168.1.20:11434/v1",
        models = listOf("qwen3:14b", "gemma3:12b"), conversationId = "c4", chatTitle = "Run models at home",
    )

    private fun sheet(row: ProviderDraftRow, key: String = "") = @androidx.compose.runtime.Composable {
        ProviderDraftSheet(row, onTest = { _, _, _ -> }, onApprove = {}, onDiscard = {}, onDismiss = {}, initialKey = key)
    }

    @Test fun reviewKnownHost() = snap("provider-draft-review-known", heightDp = 2000, variants = variants, content = sheet(ProviderDraftRow(groq, knownHost = true), key = "gsk_example"))

    @Test fun reviewUnknownHost() = snap("provider-draft-review-unknown", heightDp = 2200, variants = variants, content = sheet(ProviderDraftRow(unfamiliar, knownHost = false)))

    @Test fun reviewTainted() = snap("provider-draft-review-tainted", heightDp = 2200, variants = variants, content = sheet(ProviderDraftRow(tainted, knownHost = false)))

    @Test fun reviewLocalNoKey() = snap("provider-draft-review-local", heightDp = 2000, variants = variants, content = sheet(ProviderDraftRow(ollama, knownHost = false)))

    @Test
    fun reviewTestPassed() = snap("provider-draft-review-tested", heightDp = 2000, variants = variants) {
        ProviderDraftSheet(
            ProviderDraftRow(groq, knownHost = true), onTest = { _, _, _ -> }, onApprove = {}, onDiscard = {}, onDismiss = {}, initialKey = "gsk_example",
            initialCheck = DraftCheck.Passed(List(23) { dev.locus.core.llm.RemoteModel(if (it == 0) "llama-3.3-70b-versatile" else "model-$it") }),
        )
    }

    @Test
    fun providersListWithDraft() = snap("provider-draft-models-list", heightDp = 1000, variants = variants) {
        val anthropic = ProviderConfig("anthropic", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-sonnet-4-5")
        val state = SettingsUiState(
            providers = listOf(ProviderRow(anthropic, hasKey = true, isDefault = true)),
            providerDrafts = listOf(ProviderDraftRow(tainted, knownHost = false), ProviderDraftRow(groq, knownHost = true)),
            loading = false,
        )
        SettingsScreen(
            state = state, onBack = {}, initialPage = SettingsDestination.Models, onOpenConnectors = {},
            onSaveProvider = { _, _, _, _ -> }, onDeleteProvider = {}, onSetDefault = {}, onSetPolicy = { _, _ -> }, onSaveInstructions = {},
            onProbe = { _, _, _ -> }, onSaveSsh = { _, _, _, _ -> }, onDeleteSsh = {}, onModelAction = { _, _ -> }, onSetIntegrationKey = { _, _ -> },
        )
    }

    @Test
    fun hubWithDraft() = snap("provider-draft-hub", heightDp = 900, variants = variants) {
        val anthropic = ProviderConfig("anthropic", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-sonnet-4-5")
        val state = SettingsUiState(
            providers = listOf(ProviderRow(anthropic, hasKey = true, isDefault = true)),
            providerDrafts = listOf(ProviderDraftRow(groq, knownHost = true)),
            loading = false,
        )
        SettingsScreen(
            state = state, onBack = {}, initialPage = null, onOpenConnectors = {},
            onSaveProvider = { _, _, _, _ -> }, onDeleteProvider = {}, onSetDefault = {}, onSetPolicy = { _, _ -> }, onSaveInstructions = {},
            onProbe = { _, _, _ -> }, onSaveSsh = { _, _, _, _ -> }, onDeleteSsh = {}, onModelAction = { _, _ -> }, onSetIntegrationKey = { _, _ -> },
        )
    }
}
