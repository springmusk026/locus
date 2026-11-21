package dev.locus.feature.library

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.data.repository.PromptRepository
import dev.locus.core.designsystem.UiText
import dev.locus.core.model.SavedPrompt
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ViewModelHost
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

/** Library → Skills → Prompts (docs/design/saved-prompts.md): make, edit, reorder and remove. */
class LibraryPromptsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val host = ViewModelHost()

    private fun runTest(body: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        try {
            body()
        } finally {
            host.clear()
        }
    }

    private fun TestScope.vm(home: File = tmp.newFolder()) =
        host.add(LibraryPromptsViewModel(PromptRepository(AgentHome(home), UnconfinedTestDispatcher(testScheduler))))

    @Test
    fun `make, edit, reorder and remove prompts`() = runTest {
        val vm = vm()
        assertEquals(emptyList(), vm.all.value)
        vm.open(LibraryPromptSheet.Edit(null))
        vm.save(SavedPrompt("", "Translate", "Translate: {{selection}}"))
        vm.save(SavedPrompt("", "Standup", "Standup for {{date}}"))
        advanceUntilIdle()
        assertNull(vm.sheet.value)
        assertEquals(listOf("Translate", "Standup"), vm.all.value.map { it.name })

        val standup = vm.all.value[1]
        vm.move(standup, -1)
        advanceUntilIdle()
        assertEquals(listOf("Standup", "Translate"), vm.all.value.map { it.name })

        vm.save(standup.copy(name = "Daily standup"))
        advanceUntilIdle()
        assertEquals(listOf("Daily standup", "Translate"), vm.all.value.map { it.name })

        vm.open(LibraryPromptSheet.Remove(vm.all.value[0]))
        vm.remove(vm.all.value[0])
        advanceUntilIdle()
        assertNull(vm.sheet.value)
        assertEquals(listOf("Translate"), vm.all.value.map { it.name })
    }

    @Test
    fun `a failed save keeps the editor open with what was typed`() = runTest {
        val home = tmp.newFolder()
        val vm = vm(home)
        File(home, "prompts.json/x").apply { parentFile.mkdirs() }.writeText("x")
        val draft = SavedPrompt("", "Broken", "Hello")
        vm.save(draft)
        advanceUntilIdle()
        val sheet = assertIs<LibraryPromptSheet.Edit>(vm.sheet.value)
        assertEquals(draft, sheet.prompt)
        assertEquals(UiText.Res(R.string.library_prompts_save_failed), sheet.error)
    }
}
