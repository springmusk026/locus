package dev.locus.core.data.publish

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.integrations.PublishHost
import dev.locus.core.agent.integrations.PublishedSite
import dev.locus.core.agent.integrations.SitePublisher
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.datastore.SecretStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One page the user published (docs/design/publish.md). [source] says what it was made from
 * (`chat:<id>` or `artifact:<chat id>:<content key>`), so publishing the same thing again offers to
 * update it. [host] is a [PublishHost.id]; [site] what an update or unpublish names.
 */
@Serializable
data class PublishedPage(
    val id: String,
    val host: String,
    val site: String,
    val url: String?,
    val title: String,
    val kind: Kind,
    val source: String,
    val publishedAt: Long,
    val updatedAt: Long = publishedAt,
) {
    @Serializable
    enum class Kind { CHAT, ARTIFACT }

    val publishHost: PublishHost? get() = PublishHost.byId(host)
}

/**
 * The published pages, newest first, in a small JSON file of their own (`files/published-pages.json`)
 * rather than Room or the settings: a list of links, no schema to migrate. A file that can't be read
 * counts as empty; it holds no secrets (addresses and titles only).
 */
class PublishedPagesFile(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val state = MutableStateFlow(read())

    val pages: StateFlow<List<PublishedPage>> = state.asStateFlow()

    /** Adds [site] as a new page, or (with [existing]) records the update of that page. */
    suspend fun record(site: PublishedSite, title: String, kind: PublishedPage.Kind, source: String, existing: PublishedPage? = null): PublishedPage =
        mutex.withLock {
            val now = clock()
            val page = existing?.copy(url = site.url ?: existing.url, title = title, updatedAt = now)
                ?: PublishedPage(UUID.randomUUID().toString(), site.host.id, site.site, site.url, title.take(MAX_TITLE), kind, source, now)
            write(listOf(page) + state.value.filterNot { it.id == page.id })
            page
        }

    suspend fun remove(id: String) = mutex.withLock { write(state.value.filterNot { it.id == id }) }

    private fun read(): List<PublishedPage> =
        runCatching { if (file.exists()) json.decodeFromString(LIST, file.readText()) else emptyList() }.getOrDefault(emptyList())

    private fun write(pages: List<PublishedPage>) {
        val kept = pages.sortedByDescending { it.updatedAt }.take(MAX_PAGES)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(LIST, kept))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
        state.value = kept
    }

    private companion object {
        const val MAX_PAGES = 200
        const val MAX_TITLE = 200
        val LIST = ListSerializer(PublishedPage.serializer())
    }
}

/**
 * Publish to your own host: which deploy integrations are set up, the vault values to keep out of a
 * page, a fresh folder to build a page in, the deploy itself ([SitePublisher], the same code the
 * deploy tools run) and the list of published pages. No Locus server is involved.
 */
@Singleton
class PublishService(
    private val publisher: SitePublisher,
    private val store: PublishedPagesFile,
    private val workRoot: File,
    private val keyNames: Flow<Set<String>>,
    private val vault: suspend () -> Collection<String>,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        http: HttpClient,
        resolver: SecretResolver,
        secrets: SecretStore,
        settings: SettingsRepository,
    ) : this(
        SitePublisher(http, resolver),
        PublishedPagesFile(File(context.filesDir, "published-pages.json")),
        File(context.cacheDir, "publish"),
        settings.integrationKeys,
        { runCatching { secrets.valuesForScrubbing() }.getOrDefault(emptyList()) },
    )

    /** The deploy integrations with a key saved, in a fixed order. */
    val hosts: Flow<List<PublishHost>> = keyNames.map { keys -> PublishHost.entries.filter { it.integration.secret in keys } }

    val pages: StateFlow<List<PublishedPage>> = store.pages

    suspend fun vaultValues(): Collection<String> = vault()

    /** An empty folder for one page's files; earlier ones (over an hour old) are cleared first. */
    suspend fun newSiteFolder(): File = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        workRoot.listFiles().orEmpty().filter { it.lastModified() < cutoff }.forEach { it.deleteRecursively() }
        File(File(workRoot, UUID.randomUUID().toString()), "site").apply { mkdirs() }
    }

    /**
     * Deploys [folder] to [host] (a new site named from [title]) or, with [existing], to that page's site,
     * and records it. Throws [dev.locus.core.agent.ToolException] with a message for the user.
     */
    suspend fun publish(host: PublishHost, folder: File, title: String, kind: PublishedPage.Kind, source: String, existing: PublishedPage?): PublishedPage {
        val target = existing?.takeIf { it.host == host.id }
        val site = withContext(Dispatchers.IO) {
            publisher.publish(host, folder, target?.site ?: SitePublisher.newSiteName(title), update = target != null)
        }
        return store.record(site, title, kind, source, target)
    }

    /** Removes the page from its host, then from the list. */
    suspend fun unpublish(page: PublishedPage) {
        val host = page.publishHost ?: return store.remove(page.id)
        withContext(Dispatchers.IO) { publisher.unpublish(PublishedSite(host, page.site, page.url)) }
        store.remove(page.id)
    }

    /** Forgets a page without touching the host (for one the user removed on the provider's site). */
    suspend fun forget(page: PublishedPage) = store.remove(page.id)
}
