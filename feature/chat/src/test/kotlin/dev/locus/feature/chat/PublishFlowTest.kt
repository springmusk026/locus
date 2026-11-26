package dev.locus.feature.chat

import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.integrations.PublishHost
import dev.locus.core.agent.integrations.SitePublisher
import dev.locus.core.data.publish.PublishService
import dev.locus.core.data.publish.PublishedPagesFile
import dev.locus.core.markdown.artifact.Artifact
import dev.locus.core.markdown.artifact.ArtifactKind
import dev.locus.core.markdown.artifact.ArtifactSandbox
import dev.locus.core.model.AppLockSettings
import dev.locus.core.model.AppLockStatus
import dev.locus.core.testing.MainDispatcherRule
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The publish sheet's steps (docs/design/publish.md), without a network: nothing here gets as far as a host. */
class PublishFlowTest {
    @get:Rule val main = MainDispatcherRule()

    private val dir = Files.createTempDirectory("publish-flow").toFile()
    private val keys = MutableStateFlow<Set<String>>(emptySet())
    private var vault: Collection<String> = listOf("vault-secret-123456")

    private fun viewModel() = PublishViewModel(
        PublishService(
            SitePublisher(HttpClient(), SecretResolver { null }),
            PublishedPagesFile(File(dir, "pages.json")),
            File(dir, "work"),
            keys,
            { vault },
        ),
    )

    private val chat = PublishSource.Chat("c1", "Fix tests", "23 November 2026", listOf(ChatItem.User("u", "hello"), ChatItem.Assistant("a", "hi")))

    /**
     * Waits for the sheet to reach [step]: the build finishes on an IO thread, and the state flow says
     * when. The 10 s only bound a run that never gets there; the wait ends as soon as it does.
     */
    private fun PublishViewModel.await(step: PublishStep) {
        runBlocking { withTimeoutOrNull(10_000) { ui.first { it.step == step } } }
        assertEquals(step, ui.value.step)
    }

    @Test
    fun `with no deploy integration the sheet says so and can't go on`() {
        val vm = viewModel()
        vm.open(chat, PageLabels())
        assertEquals(emptyList(), vm.ui.value.hosts)
        assertNull(vm.ui.value.host)
        vm.build()
        assertEquals(PublishStep.Destination, vm.ui.value.step)
    }

    @Test
    fun `only the integrations with a key are offered`() {
        keys.value = setOf("integration-vercel", "integration-cloudflare", "integration-github")
        val vm = viewModel()
        vm.open(chat, PageLabels())
        assertEquals(listOf(PublishHost.CLOUDFLARE, PublishHost.VERCEL), vm.ui.value.hosts)
        assertEquals(PublishHost.CLOUDFLARE, vm.ui.value.host)
    }

    @Test
    fun `a scrubbed chat previews, then confirms`() {
        keys.value = setOf("integration-netlify")
        val vm = viewModel()
        vm.open(chat.copy(items = listOf(ChatItem.User("u", "key vault-secret-123456"))), PageLabels())
        vm.build()
        vm.await(PublishStep.Preview)
        val built = assertNotNull(vm.ui.value.built)
        assertEquals(1, built.removed)
        assertFalse(built.blocked)
        assertFalse("vault-secret-123456" in File(built.folder, "index.html").readText())
        vm.toConfirm()
        assertEquals(PublishStep.Confirm, vm.ui.value.step)
        vm.back()
        assertEquals(PublishStep.Preview, vm.ui.value.step)
    }

    @Test
    fun `a page that would still hold a vault value can't be confirmed or published`() {
        keys.value = setOf("integration-netlify")
        // A value the scrubber doesn't find in the text but that ends up in the page: the guard still catches it.
        vault = listOf("Locus</p>")
        val vm = viewModel()
        vm.open(chat, PageLabels())
        vm.build()
        vm.await(PublishStep.Preview)
        assertTrue(vm.ui.value.built!!.blocked)
        vm.toConfirm()
        vm.publish()
        assertEquals(PublishStep.Preview, vm.ui.value.step)
    }

    @Test
    fun `an artifact publishes as it is, scrubbed`() {
        keys.value = setOf("integration-cloudflare")
        val vm = viewModel()
        val artifact = Artifact(ArtifactKind.Html, "<!doctype html><html><body><script>const k='vault-secret-123456'</script></body></html>", "Chart")
        vm.open(PublishSource.Page("c1", artifact), PageLabels())
        vm.build()
        vm.await(PublishStep.Preview)
        val html = vm.ui.value.built!!.html
        assertTrue(html.startsWith("<!doctype html><html><body><script>const k='"))
        assertFalse("vault-secret-123456" in html)
        assertEquals("artifact:c1:${artifact.key}", vm.ui.value.source!!.source)
    }

    @Test
    fun `publishing from a locked chat asks for authentication first`() {
        val locked = AppLockStatus(settings = AppLockSettings(lockedChats = setOf("c1")), available = true)
        assertTrue(PublishPolicy.needsUnlock(locked, "c1"))
        assertFalse(PublishPolicy.needsUnlock(locked, "c2"))
        // Without a screen lock there is nothing to ask with: the lock is suspended (docs/design/app-lock.md).
        assertFalse(PublishPolicy.needsUnlock(locked.copy(available = false), "c1"))
        // Until the lock's settings are read, err on the side of asking.
        assertTrue(PublishPolicy.needsUnlock(null, "c1"))
    }

    @Test
    fun `the preview runs in the artifact sandbox, which has no network`() {
        val page = ChatPage.build("T", null, listOf(ChatItem.Assistant("a", "![x](https://tracker.example/p.gif)")), PageLabels(), false, dev.locus.core.agent.publish.PublishPage.Scrubber(emptyList()), { null })
        val artifact = publishPreviewArtifact(page.html, "T")
        val served = ArtifactSandbox.contentDocument(artifact)
        assertTrue(ArtifactSandbox.CONTENT_CSP in served)
        assertFalse(Regex("(connect|img|script|default)-src[^;]*https?:").containsMatchIn(ArtifactSandbox.CONTENT_CSP))
        listOf("https://tracker.example/p.gif", "http://192.168.1.2/", "https://appassets.androidplatform.net/artifact/other/content.html").forEach {
            assertEquals(ArtifactSandbox.Decision.Block, ArtifactSandbox.decide(it, "abc"))
        }
    }
}
