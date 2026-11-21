package dev.locus.feature.chat

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.prompt.PromptField
import dev.locus.core.agent.prompt.SlashQuery
import dev.locus.core.data.repository.PromptRepository
import dev.locus.core.designsystem.UiText
import dev.locus.core.model.SavedPrompt
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ViewModelHost
import dev.locus.core.ui.PROMPT_CLIPBOARD_KEY
import dev.locus.core.ui.PROMPT_SELECTION_KEY
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saved prompts in the composer (docs/design/saved-prompts.md): picking, the form, and what lands in the box. */
class ComposerPromptsTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val host = ViewModelHost()

    /** Counts what's read, so a prompt that doesn't use the clipboard is shown not to touch it. */
    private class FakeDevice(var clip: String? = "copied text") : PromptDevice {
        var clipboardReads = 0
        override fun clipboard(): String? = clip.also { clipboardReads++ }
        override fun date() = "Saturday, 21 November 2026"
        override fun time() = "14:05"
    }

    private fun runTest(body: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        try {
            body()
        } finally {
            host.clear()
        }
    }

    private fun TestScope.repository() = PromptRepository(AgentHome(tmp.newFolder()), UnconfinedTestDispatcher(testScheduler))

    private fun TestScope.vm(repo: PromptRepository = repository()) = host.add(ComposerPromptsViewModel(repo))

    private fun prompt(text: String, name: String = "P") = SavedPrompt("id-$name", name, text)

    @Test
    fun `a prompt without blanks goes straight into the box and is never sent`() = runTest {
        val vm = vm()
        val device = FakeDevice()
        vm.pick(prompt("Summarise this thread."), composerText = "/sum", fromSlash = true, device = device)
        assertEquals("Summarise this thread.", vm.insertion.value?.text)
        assertNull(vm.sheet.value)
        assertEquals(0, device.clipboardReads)
        val serial = vm.insertion.value!!.serial
        vm.consumed(serial)
        assertNull(vm.insertion.value)
    }

    @Test
    fun `date, time and clipboard are read only when used, and filled in`() = runTest {
        val vm = vm()
        val device = FakeDevice()
        vm.pick(prompt("Notes for {{date}} at {{time}}:\n{{clipboard}}"), "", fromSlash = false, device = device)
        assertEquals("Notes for Saturday, 21 November 2026 at 14:05:\ncopied text", vm.insertion.value?.text)
        assertEquals(1, device.clipboardReads)
    }

    @Test
    fun `fields open the form, and Insert fills them in`() = runTest {
        val vm = vm()
        val p = prompt("Write to {{field:Name}} in a {{field:Tone|warm}} tone.")
        vm.pick(p, "", fromSlash = true, device = FakeDevice())
        val form = assertIs<PromptSheet.Form>(vm.sheet.value)
        assertEquals(listOf(PromptField("Name", null), PromptField("Tone", "warm")), form.fields)
        assertNull(vm.insertion.value)
        vm.fill(mapOf("name" to "Ana", "tone" to "warm"))
        assertEquals("Write to Ana in a warm tone.", vm.insertion.value?.text)
        assertNull(vm.sheet.value)
    }

    @Test
    fun `an empty clipboard is asked for in the form instead of inserting nothing`() = runTest {
        val vm = vm()
        vm.pick(prompt("Fix: {{clipboard}}"), "", fromSlash = false, device = FakeDevice(clip = null))
        val form = assertIs<PromptSheet.Form>(vm.sheet.value)
        assertTrue(form.askClipboard)
        vm.fill(mapOf(PROMPT_CLIPBOARD_KEY to "pasted by hand"))
        assertEquals("Fix: pasted by hand", vm.insertion.value?.text)
    }

    @Test
    fun `the selection is the shared or typed text, which the prompt wraps`() = runTest {
        val vm = vm()
        vm.pick(prompt("Translate into English:\n\n{{selection}}"), "  Bom dia a todos  ", fromSlash = false, device = FakeDevice())
        assertEquals("Translate into English:\n\nBom dia a todos", vm.insertion.value?.text)
    }

    @Test
    fun `with no shared text the selection is asked for`() = runTest {
        val vm = vm()
        vm.pick(prompt("Explain: {{selection}}"), "/exp", fromSlash = true, device = FakeDevice())
        val form = assertIs<PromptSheet.Form>(vm.sheet.value)
        assertTrue(form.askSelection)
        vm.fill(mapOf(PROMPT_SELECTION_KEY to "this regex"))
        assertEquals("Explain: this regex", vm.insertion.value?.text)
    }

    @Test
    fun `text already typed stays after a prompt that doesn't use it`() = runTest {
        val vm = vm()
        vm.pick(prompt("Answer in Spanish."), "what is a monad?", fromSlash = false, device = FakeDevice())
        assertEquals("Answer in Spanish.\n\nwhat is a monad?", vm.insertion.value?.text)
    }

    @Test
    fun `each insertion lands once, with its own serial`() = runTest {
        val vm = vm()
        vm.pick(prompt("One"), "", fromSlash = true, device = FakeDevice())
        val first = vm.insertion.value!!
        vm.pick(prompt("Two"), "", fromSlash = true, device = FakeDevice())
        val second = vm.insertion.value!!
        assertTrue(second.serial > first.serial)
        vm.consumed(first.serial)
        assertEquals(second, vm.insertion.value)
    }

    @Test
    fun `a prompt made from the picker is saved and the picker comes back with it`() = runTest {
        val repo = repository()
        val vm = vm(repo)
        vm.openPicker("draft")
        vm.newPrompt()
        val editor = assertIs<PromptSheet.Editor>(vm.sheet.value)
        assertEquals(PromptSheet.Picker("draft"), editor.returnTo)
        vm.save(SavedPrompt("", "Standup", "Standup for {{date}}"))
        advanceUntilIdle()
        assertEquals(PromptSheet.Picker("draft"), vm.sheet.value)
        assertEquals(listOf("Standup"), vm.prompts.value.map { it.name })
        // Closing the editor without saving returns to the picker too; closing the picker closes all.
        vm.newPrompt()
        vm.dismiss()
        assertEquals(PromptSheet.Picker("draft"), vm.sheet.value)
        vm.close()
        assertNull(vm.sheet.value)
    }

    @Test
    fun `a save that fails keeps the editor open with the draft and a reason`() = runTest {
        val home = tmp.newFolder()
        val repo = PromptRepository(AgentHome(home), UnconfinedTestDispatcher(testScheduler))
        val vm = vm(repo)
        vm.newPrompt()
        // agent-home's prompts.json is a folder: the write fails.
        File(home, "prompts.json").mkdirs()
        File(home, "prompts.json/x").writeText("x")
        val draft = SavedPrompt("", "Broken", "Hello")
        vm.save(draft)
        advanceUntilIdle()
        val editor = assertIs<PromptSheet.Editor>(vm.sheet.value)
        assertEquals(draft, editor.draft)
        assertEquals(UiText.Res(R.string.chat_prompt_save_failed), editor.error)
    }

    @Test
    fun `the slash list doesn't take over btw or text that starts with a slash`() {
        // `/btw` alone is both a search (the list offers it) and the reopen command; with a question it's a message.
        assertEquals("btw", SlashQuery.of("/btw"))
        assertEquals(SideCommand.Reopen, SideCommand.parse("/btw"))
        assertNull(SlashQuery.of("/btw why npm?"))
        assertEquals(SideCommand.Ask("why npm?"), SideCommand.parse("/btw why npm?"))
        // A path or a sentence is sent as typed: the list never changes the text on its own.
        assertNull(SlashQuery.of("/etc/hosts has a typo"))
        assertNull(sideCommandFor("/etc/hosts has a typo", asideOn = false))
    }

    @Test
    fun `the box after a pick`() {
        val ctx = PromptPickContext("/tr", fromSlash = true)
        assertEquals("X", ComposerPromptsViewModel.composerTextAfter("X", ctx, usesSelection = false))
        assertEquals("X\n\nkept", ComposerPromptsViewModel.composerTextAfter("X", PromptPickContext(" kept ", fromSlash = false), usesSelection = false))
        assertEquals("X", ComposerPromptsViewModel.composerTextAfter("X", PromptPickContext("used", fromSlash = false), usesSelection = true))
        assertEquals("", ctx.selection)
    }
}
