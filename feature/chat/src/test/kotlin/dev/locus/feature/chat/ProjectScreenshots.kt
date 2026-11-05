package dev.locus.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.locus.core.database.ConversationEntity
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.MemoryScope
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Project
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.core.screenshot.Step
import dev.locus.core.screenshot.HiltHostActivity
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.reflect.KClass

/** Projects: the list, one project, and the editor (new and existing), backed by the real view models. */
class ProjectScreenshots : ScreenshotTest() {

    @get:Rule val main = MainDispatcherRule()

    private val scope = CoroutineScope(SupervisorJob())
    private val anthropic = ProviderConfig("a", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5")
    private val router = ProviderConfig("r", "OpenRouter", ApiFormat.OPENAI_CHAT, "https://openrouter.ai/api/v1", "google/gemini-3-pro")
    private val graph = TestGraph(scope, AppSettings(providers = listOf(anthropic, router), defaultProviderId = "a"))

    @After
    fun tearDown() = scope.cancel()

    /** Relative times count back from the real clock; every one stays under a week, so none turns into a date. */
    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun chat(id: String, title: String, project: String?, ago: Long) = runBlocking {
        graph.database.conversationDao.upsert(ConversationEntity(id, title, "a", "", now - ago - hour, now - ago, projectId = project))
        graph.conversations.append(Message("$id-u", id, Role.USER, listOf(Part.Text(title)), now - ago))
    }

    private fun seed(): Triple<Project, Project, Project> = runBlocking {
        val garden = graph.projects.create("garden-app", instructions = "TypeScript, Node 22. Run `npm test` before saying a change works. Never push to main.")!!
        // A few ms apart: projects are listed by updatedAt, and a tie leaves the order to chance.
        Thread.sleep(3)
        val portfolio = graph.projects.create("Portfolio site", folder = "projects/portfolio", memoryScope = MemoryScope.PROJECT_ONLY)!!
        Thread.sleep(3)
        val taxes = graph.projects.create("Taxes 2026", folder = "projects/taxes-2026")!!
        Thread.sleep(3)
        graph.projects.update(portfolio.copy(providerId = "r", model = "google/gemini-3-pro"))
        chat("c1", "Fix checkout tests", garden.id, 2 * hour)
        chat("c2", "Move the API from Express to Hono", garden.id, 5 * hour)
        chat("c3", "Why is the plants search slow?", garden.id, day + 3 * hour)
        chat("c4", "Bump Stripe to v18", garden.id, 4 * day)
        chat("c5", "Rewrite the About page", portfolio.id, 3 * day)
        Triple(garden, portfolio, taxes)
    }

    private fun projects(): ProjectsViewModel {
        val vm = ProjectsViewModel(graph.projects, graph.conversations, graph.settingsRepository)
        runBlocking { withTimeout(10_000) { vm.rows.first { it != null } } }
        return vm
    }

    @Test
    fun list() {
        seed()
        val vm = projects()
        runBlocking { withTimeout(10_000) { vm.rows.first { it?.size == 3 } } }
        snap("projects") { ProjectsRoute(onBack = {}, onOpenProject = {}, onNewProject = {}, viewModel = vm) }
    }

    @Test
    fun listEmpty() {
        val vm = projects()
        snap("projects-empty") { ProjectsRoute(onBack = {}, onOpenProject = {}, onNewProject = {}, viewModel = vm) }
    }

    @Test
    fun project() {
        val (garden, _, _) = seed()
        val vm = projects()
        runBlocking { withTimeout(10_000) { vm.chats(garden.id).first { it.size == 4 } } }
        snapAfter("project", emptyList()) { ProjectRoute(garden.id, onBack = {}, onOpenChat = {}, onEdit = {}, viewModel = vm) }
    }

    @Test
    fun projectNoChats() {
        val (_, _, taxes) = seed()
        val vm = projects()
        snapAfter("project-no-chats", emptyList()) { ProjectRoute(taxes.id, onBack = {}, onOpenChat = {}, onEdit = {}, viewModel = vm) }
    }

    @Test
    fun projectMissing() {
        seed()
        val vm = projects()
        snapAfter("project-not-found", emptyList()) { ProjectRoute("deleted-project", onBack = {}, onOpenChat = {}, onEdit = {}, viewModel = vm) }
    }

    // ---- The editor: its route asks Hilt for the view model by key, so the store already holds it.

    private fun editor(id: String?): ViewModelStoreOwner {
        val vm = ProjectEditorViewModel(id, graph.projects, graph.settingsRepository, graph.tools, graph.library, graph.remote)
        runBlocking { withTimeout(10_000) { vm.state.first { !it.loading && it.tools.isNotEmpty() } } }
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = vm as T
        }
        ViewModelProvider.create(owner.viewModelStore, factory)["project-editor-${id ?: "new"}", ProjectEditorViewModel::class]
        return owner
    }

    @Composable
    private fun Editor(owner: ViewModelStoreOwner, id: String?) = CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        ProjectEditorRoute(id, onBack = {}, onSaved = {}, onDeleted = {})
    }

    @Test
    fun editorNew() {
        val owner = editor(null)
        snapAfter("project-editor-new", emptyList(), heightDp = 1400, activity = HiltHostActivity::class.java) { Editor(owner, null) }
    }

    @Test
    fun editorNewTyped() {
        val owner = editor(null)
        snapAfter("project-editor-new-named", listOf(Step.Type("Home automation")), heightDp = 1400, activity = HiltHostActivity::class.java) { Editor(owner, null) }
    }

    @Test
    fun editorExisting() {
        val (garden, _, _) = seed()
        val owner = editor(garden.id)
        snapAfter("project-editor-edit", emptyList(), heightDp = 2200, activity = HiltHostActivity::class.java) { Editor(owner, garden.id) }
    }
}
