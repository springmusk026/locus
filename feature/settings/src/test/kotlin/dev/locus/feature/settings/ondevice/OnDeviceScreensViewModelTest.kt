package dev.locus.feature.settings.ondevice

import dev.locus.core.model.ApiFormat
import dev.locus.core.ondevice.OnDeviceCatalog
import dev.locus.core.ondevice.gguf.GgufDownloads
import dev.locus.core.ondevice.gguf.ModelNetwork
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import io.ktor.http.Url
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The library, search and detail view models over the real managers (no network: the Hub answers offline). */
class OnDeviceScreensViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val host = ViewModelHost()

    private fun runTest(body: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        try {
            body()
        } finally {
            host.clear()
        }
    }

    /** Every request fails as if offline: the screens' failure states, with nothing reaching a network. */
    private val offline = object : ModelNetwork {
        override suspend fun <T> get(url: Url, headers: Map<String, String>, block: suspend (ModelNetwork.Response) -> T): T =
            throw java.net.UnknownHostException(url.host)
        override suspend fun refusal(url: Url): String? = null
    }

    private fun TestScope.actions(): Pair<TestGraph, OnDeviceActions> {
        val g = TestGraph(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler))
        val downloads = GgufDownloads(g.context, offline, g.ggufModels)
        return g to OnDeviceActions(g.onDeviceModels, g.onDevice, g.ggufModels, downloads, g.settingsRepository, g.conversations)
    }

    @Test
    fun theLibraryFiltersDiscover() = runTest {
        val (_, a) = actions()
        val vm = host.add(OnDeviceLibraryViewModel(a))
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        val start = vm.uiState.first { !it.loading }
        assertTrue(start.empty)
        assertTrue(start.filters.fitsOnly)
        vm.setFilters(DiscoverFilters(fitsOnly = false, engine = Engine.LiteRt))
        val liteRt = vm.uiState.first { it.filters.engine == Engine.LiteRt }
        assertEquals(OnDeviceCatalog.models.size, liteRt.discover.size)
    }

    @Test
    fun searchOfflineSaysSo() = runTest {
        val (_, a) = actions()
        val vm = host.add(OnDeviceSearchViewModel(a))
        vm.setQuery("qwen")
        vm.search()
        val failed = vm.state.first { it.results is SearchResults.Failed }.results as SearchResults.Failed
        assertEquals(Problem.Kind.Offline, failed.problem.kind)
        vm.setQuery("")
        assertEquals(SearchResults.Idle, vm.state.value.results)
    }

    @Test
    fun aLiteRtDetailNeedsNoNetwork() = runTest {
        val (_, a) = actions()
        val vm = host.add(OnDeviceDetailViewModel(a))
        backgroundScope.launch { vm.uiState.collect {} }
        val m = OnDeviceCatalog.models.first()
        vm.load(ModelKey.LiteRt(m.id))
        runCurrent()
        val s = vm.uiState.first { !it.loading && it.info != null }
        assertEquals(m.name, s.info!!.name)
        assertEquals(Engine.LiteRt, s.info.engine)
        assertEquals(1, s.options.size)
        assertTrue(s.options.single().recommended)
        assertNull(s.installed)
        assertNull(s.problem)
    }

    @Test
    fun aRepoDetailOfflineShowsTheProblem() = runTest {
        val (_, a) = actions()
        val vm = host.add(OnDeviceDetailViewModel(a))
        backgroundScope.launch { vm.uiState.collect {} }
        vm.load(ModelKey.Repo("unsloth/Qwen3.5-2B-GGUF"))
        runCurrent()
        val s = vm.uiState.first { it.problem != null }
        assertEquals(Problem.Kind.Offline, s.problem!!.kind)
    }

    @Test
    fun aCatalogueDetailWorksOfflineWithItsPinnedFile() = runTest {
        val (_, a) = actions()
        val vm = host.add(OnDeviceDetailViewModel(a))
        backgroundScope.launch { vm.uiState.collect {} }
        val entry = dev.locus.core.ondevice.gguf.GgufCatalog.models.first()
        vm.load(ModelKey.Catalog(entry.id))
        runCurrent()
        val s = vm.uiState.first { !it.optionsLoading && it.info != null }
        assertEquals(entry.file, s.selected)
        assertNotNull(s.optionsProblem, "other quantisations need the Hub; the pinned file doesn't")
        assertEquals(entry.sha256, s.selectedOption!!.sha256)
    }

    @Test
    fun useSetsTheOnDeviceProviderAsDefault() = runTest {
        val (g, a) = actions()
        a.setDefault("gguf:qwen")
        val s = g.settingsRepository.current()
        val provider = s.providers.single { it.format == ApiFormat.ON_DEVICE }
        assertEquals("gguf:qwen", provider.model)
        assertEquals(provider.id, s.defaultProviderId)
        assertEquals("gguf:qwen", a.defaultModel())
        val chat = a.newChat("gguf:qwen")
        assertEquals("gguf:qwen", g.conversations.get(chat)?.modelOverride)
    }
}
