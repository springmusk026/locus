package dev.locus.feature.chat

import dev.locus.core.screenshot.OnScreen
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.core.screenshot.Variant
import org.junit.Test

/** The chat card under a turn that drafted a provider: waiting for review, approved, discarded (docs/design/provider-drafts.md). */
class ProviderDraftCardScreenshots : ScreenshotTest() {
    @Test
    fun cards() = snap("provider-draft-chat-card", heightDp = 1200, variants = listOf(Variant.Light360, Variant.Dark411, Variant.Font200)) {
        OnScreen {
            ProviderDraftCard(ChatItem.ProviderDraft("d1", "Groq", "api.groq.com"), status = ProviderDraftStatus.State.Waiting("draft-1"))
            ProviderDraftCard(ChatItem.ProviderDraft("d2", "Ollama on the desktop", "192.168.1.20"), status = ProviderDraftStatus.State.Added)
            ProviderDraftCard(ChatItem.ProviderDraft("d3", "Default", "evil.example"), status = ProviderDraftStatus.State.Gone)
        }
    }
}
