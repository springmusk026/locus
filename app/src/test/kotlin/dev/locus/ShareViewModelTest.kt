package dev.locus

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.data.lock.AppLock
import dev.locus.core.data.shortcuts.ChatShortcutPlan
import dev.locus.core.data.shortcuts.ChatShortcuts
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Something shared to Locus becomes a chat's draft, never a sent message: text and links from the share
 * sheet, a selection from another app's "Ask Locus", and a Direct Share into one chat. A file that
 * can't be imported is named, not dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ShareViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val host = ViewModelHost()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Fixture(val graph: TestGraph, val vm: ShareViewModel)

    private fun TestScope.fixture(): Fixture {
        val g = TestGraph(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler))
        val lock = AppLock.forTest(g.settingsRepository, device = { false }, scope = backgroundScope)
        val shortcuts = ChatShortcuts(context, g.conversations, backgroundScope, lock)
        return Fixture(g, host.add(ShareViewModel(context, g.conversations, g.settingsRepository, g.workspaceRepository, shortcuts)))
    }

    /** Hands [intent] to the view model and waits for the chat it opens. */
    private suspend fun ShareViewModel.share(intent: Intent): Pair<String, String> {
        val opened = CompletableDeferred<Pair<String, String>>()
        handle(intent) { id, draft -> opened.complete(id to draft) }
        return opened.await()
    }

    private fun send(text: String? = null, subject: String? = null) = Intent(Intent.ACTION_SEND).setType("text/plain").apply {
        text?.let { putExtra(Intent.EXTRA_TEXT, it) }
        subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
    }

    @Test
    fun `shared text opens a new chat with it as the draft`() = runTest {
        try {
            val f = fixture()
            val (id, draft) = f.vm.share(send(text = "https://example.org/post", subject = "A post"))
            assertEquals("A post\nhttps://example.org/post", draft)
            val chat = f.graph.conversations.all.first().single()
            assertEquals(id, chat.id)
            // A draft only: nothing was sent.
            assertTrue(f.graph.database.messageDao.tree(id).isEmpty())
        } finally {
            host.clear()
        }
    }

    @Test
    fun `selected text is trimmed and capped`() = runTest {
        try {
            val f = fixture()
            val long = "  " + "a".repeat(25_000) + "  "
            val (_, draft) = f.vm.share(Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, long))
            assertEquals(20_000, draft.length)
            assertTrue(draft.all { it == 'a' })
        } finally {
            host.clear()
        }
    }

    @Test
    fun `other intents and empty shares open nothing`() = runTest {
        try {
            val f = fixture()
            var opened = 0
            f.vm.handle(Intent(Intent.ACTION_MAIN)) { _, _ -> opened++ }
            f.vm.handle(Intent(LaunchActions.NEW_CHAT)) { _, _ -> opened++ }
            f.vm.handle(send(text = "   ")) { _, _ -> opened++ }
            f.vm.handle(Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, " ")) { _, _ -> opened++ }
            runCurrent()
            assertEquals(0, opened)
            assertTrue(f.graph.conversations.all.first().isEmpty())
        } finally {
            host.clear()
        }
    }

    @Test
    fun `a Direct Share lands in that chat, and only while it's a share target`() = runTest {
        try {
            val f = fixture()
            val target = f.graph.conversations.create(null).id
            val intoChat = send(text = "for this chat").putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, ChatShortcutPlan.idOf(target))
            assertEquals(target to "for this chat", f.vm.share(intoChat))

            // Any app can name a shortcut: one that isn't a chat's opens a new chat instead.
            val (other, _) = f.vm.share(send(text = "elsewhere").putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, "workspace"))
            assertNotEquals(target, other)

            // A deleted chat is no target any more.
            f.graph.conversations.delete(target)
            val (fresh, draft) = f.vm.share(intoChat)
            assertNotEquals(target, fresh)
            assertEquals("for this chat", draft)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `a file that can't be imported is named and the text still opens`() = runTest {
        try {
            val f = fixture()
            // Only content:// URIs from another app are imported; a file:// path is refused.
            val intent = send(text = "see attached").putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///sdcard/Download/notes.txt"))
            val (_, draft) = f.vm.share(intent)
            assertEquals("see attached", draft)
            val failures = f.vm.failures.value
            assertEquals(1, failures.size)
            assertTrue(failures.single().startsWith("notes.txt: "), failures.single())
            f.vm.dismissFailures()
            assertTrue(f.vm.failures.value.isEmpty())
        } finally {
            host.clear()
        }
    }

    @Test
    fun `the new chat shortcut opens an empty chat`() = runTest {
        try {
            val f = fixture()
            val opened = CompletableDeferred<String>()
            f.vm.newChat { opened.complete(it) }
            val id = opened.await()
            assertEquals(listOf(id), f.graph.conversations.all.first().map { it.id })
        } finally {
            host.clear()
        }
    }
}
