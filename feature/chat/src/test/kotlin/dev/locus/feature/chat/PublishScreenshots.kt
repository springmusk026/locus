package dev.locus.feature.chat

import androidx.compose.runtime.CompositionLocalProvider
import dev.locus.core.agent.integrations.PublishHost
import dev.locus.core.data.publish.PublishedPage
import dev.locus.core.screenshot.PrivateComposable
import dev.locus.core.screenshot.ScreenshotTest
import org.junit.Test
import java.io.File

/** The publish sheet's steps (docs/design/publish.md), called with fake state. */
class PublishScreenshots : ScreenshotTest() {

    private val chat = PublishSource.Chat("c1", "Fix checkout tests", "23 November 2026", emptyList())
    private val hosts = listOf(PublishHost.NETLIFY, PublishHost.CLOUDFLARE, PublishHost.VERCEL)
    private val earlier = PublishedPage(
        id = "p1", host = "netlify", site = "11111111-2222-3333-4444-555555555555", url = "https://locus-fix-checkout-tests-k3m9qa.netlify.app",
        title = "Fix checkout tests", kind = PublishedPage.Kind.CHAT, source = "chat:c1", publishedAt = 1_795_400_000_000,
    )
    private val chart = PublishedPage(
        id = "p2", host = "cloudflare", site = "locus-sales-chart-x7p2rd", url = "https://locus-sales-chart-x7p2rd.me.workers.dev",
        title = "Sales by month", kind = PublishedPage.Kind.ARTIFACT, source = "artifact:c9:k", publishedAt = 1_795_300_000_000,
    )
    private val html = PublishPageFixture.html
    private val built = BuiltSite(File("/tmp/site"), html, 18_432, removed = 0, images = 1, blocked = false)

    @Test
    fun destination() = snap("sheet-publish-destination", heightDp = 1300) {
        PublishSheet(PublishUi(source = chat, hosts = hosts, host = PublishHost.NETLIFY), listOf(chart), PublishActions())
    }

    @Test
    fun destinationUpdate() = snap("sheet-publish-destination-update", heightDp = 1400) {
        PublishSheet(PublishUi(source = chat, hosts = hosts, host = PublishHost.NETLIFY, update = earlier, includeToolResults = true), listOf(earlier, chart), PublishActions())
    }

    @Test
    fun noIntegration() = snap("sheet-publish-no-integration", heightDp = 900) {
        PublishSheet(PublishUi(source = chat, hosts = emptyList()), emptyList(), PublishActions())
    }

    @Test
    fun preview() = snap("sheet-publish-preview", heightDp = 900) {
        PublishSheet(PublishUi(source = chat, step = PublishStep.Preview, hosts = hosts, host = PublishHost.NETLIFY, built = built.copy(removed = 2)), emptyList(), PublishActions())
    }

    @Test
    fun previewBlocked() = snap("sheet-publish-preview-blocked", heightDp = 900) {
        PublishSheet(PublishUi(source = chat, step = PublishStep.Preview, hosts = hosts, host = PublishHost.NETLIFY, built = built.copy(blocked = true)), emptyList(), PublishActions())
    }

    @Test
    fun confirm() = snap("sheet-publish-confirm", heightDp = 900) {
        PublishSheet(PublishUi(source = chat, step = PublishStep.Confirm, hosts = hosts, host = PublishHost.CLOUDFLARE, built = built), emptyList(), PublishActions())
    }

    @Test
    fun publishing() = snap("sheet-publish-publishing", heightDp = 500) {
        PublishSheet(PublishUi(source = chat, step = PublishStep.Publishing, hosts = hosts, host = PublishHost.VERCEL, built = built), emptyList(), PublishActions())
    }

    @Test
    fun done() = snap("sheet-publish-done", heightDp = 700) {
        PublishSheet(PublishUi(source = chat, step = PublishStep.Done, hosts = hosts, host = PublishHost.NETLIFY, built = built, result = earlier), emptyList(), PublishActions())
    }

    @Test
    fun error() = snap("sheet-publish-error", heightDp = 600) {
        val message = "Netlify rejected the token. Ask the user to check it in Settings → Integrations → Netlify (resetting the Netlify password revokes tokens)."
        PublishSheet(
            PublishUi(source = chat, step = PublishStep.Failed, hosts = hosts, host = PublishHost.NETLIFY, built = built, error = message, failedAt = PublishStep.Confirm),
            emptyList(), PublishActions(),
        )
    }

    @Test
    fun errorGeneric() = snap("sheet-publish-error-network", heightDp = 500) {
        PublishSheet(PublishUi(source = chat, step = PublishStep.Failed, hosts = hosts, host = PublishHost.VERCEL, built = built, failedAt = PublishStep.Confirm), emptyList(), PublishActions())
    }

    /** Share chat with Publish as a web page under the file formats. */
    @Test
    fun shareChatPublish() = snap("sheet-share-chat-publish", heightDp = 800) {
        val save: suspend (String, File) -> String = { name, _ -> "exports/$name" }
        CompositionLocalProvider(LocalChatPublish provides {}) {
            PrivateComposable("dev.locus.feature.chat.ShareChatKt", "ShareChatSheet", "Fix checkout tests", Fx.T0, Fx.finished, save, {})
        }
    }
}

/** A small chat page as the builder makes it, for the preview's source view. */
private object PublishPageFixture {
    val html: String = ChatPage.build(
        "Fix checkout tests", "23 November 2026",
        listOf(ChatItem.User("u", "The checkout tests fail on CI. Can you fix them?"), ChatItem.Assistant("a", "Fixed: the **tax** rounding test now uses `BigDecimal`.")),
        PageLabels(), false, dev.locus.core.agent.publish.PublishPage.Scrubber(emptyList()), { null },
    ).html
}
