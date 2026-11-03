package dev.locus.core.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.ConnectorSeal
import dev.locus.core.agent.home.InstructionSeal
import dev.locus.core.agent.home.InstructionSet
import dev.locus.core.datastore.IntegrityAlert
import dev.locus.core.datastore.IntegrityAlerts
import dev.locus.core.model.IntegrityKey
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.data.mcp.LinuxStdioLauncher
import dev.locus.core.data.mcp.LocalMcpServers
import dev.locus.core.datastore.SecretStore
import dev.locus.core.linux.LinuxEnvironment
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Where blocking work (files, the agent home) runs: Dispatchers.IO in the app. Injected so tests can
 * pass their test dispatcher and nothing they wait on runs on a real thread.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Where CPU-bound work (deriving a transcript) runs: Dispatchers.Default in the app. Injected for tests, as [IoDispatcher]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
internal object DataModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(diagnostics: dagger.Lazy<dev.locus.core.data.diagnostics.DiagnosticsRepository>): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + appScopeErrors { diagnostics.get().recordError(it) })

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    fun agentHome(
        @ApplicationContext context: Context,
        key: IntegrityKey,
        instructionKey: dev.locus.core.datastore.InstructionIntegrityKey,
        alerts: IntegrityAlerts,
    ): AgentHome =
        AgentHome(
            File(context.filesDir, "agent-home"),
            // Connectors added or changed outside Locus go back to drafts, and the user is told.
            connectorSeal = ConnectorSeal(key) { ids -> alerts.report(IntegrityAlert.Connectors(ids)) },
            // Skills, helpers and persona files changed outside Locus are held for review, the approved
            // version is put back, and the user is told (docs/design/linux-isolation.md, "Sealed instructions").
            instructionSeal = InstructionSeal(instructionKey) { set, names ->
                alerts.report(
                    when (set) {
                        InstructionSet.SKILLS -> IntegrityAlert.Skills(names)
                        InstructionSet.HELPERS -> IntegrityAlert.Helpers(names)
                        InstructionSet.PERSONA -> IntegrityAlert.Persona(names)
                    },
                )
            },
            // Memory search (docs/design/memory-search.md): the bundled SQLite Room uses, so FTS5 and its
            // trigram tokenizer are there on every Android version. The index is rebuilt from the notes,
            // so it lives where backups don't go.
            sqlite = androidx.sqlite.driver.bundled.BundledSQLiteDriver(),
            indexDir = File(context.noBackupFilesDir, "memory-index"),
        )

    /**
     * Switches of the default network (docs/design/stream-timeouts.md): model streams in flight end at
     * once so their retry goes out on the new network, and pooled connections of the old one are dropped
     * so the retry doesn't reuse a dead socket. Providers with their own client drop theirs too.
     */
    @Provides
    @Singleton
    fun networkSwitches(
        @ApplicationContext context: Context,
        okHttp: OkHttpClient,
        providerClients: dev.locus.core.data.network.ProviderHttpClients,
    ): dev.locus.core.llm.NetworkSwitches = dev.locus.core.llm.NetworkSwitches().also { switches ->
        switches.onSwitch {
            okHttp.connectionPool.evictAll()
            providerClients.evictIdleConnections()
        }
        dev.locus.core.data.network.DefaultNetworkWatcher.register(context, switches)
    }

    @Provides
    fun secretResolver(secrets: SecretStore): SecretResolver = SecretResolver { name -> secrets.get(SecretStore.connectorSecret(name)) }

    /** Which address each connector key belongs to (docs/design/mcp-connectors.md, "Keys belong to an address"). */
    @Provides
    fun keyOrigins(origins: dev.locus.core.data.mcp.ConnectorKeyOrigins): dev.locus.core.agent.home.KeyOrigins = origins

    @Provides
    @Singleton
    fun localMcpServers(linux: LinuxEnvironment, secrets: SecretResolver, @ApplicationScope scope: CoroutineScope): LocalMcpServers =
        LocalMcpServers(LinuxStdioLauncher(linux), secrets, scope, redactor = linux.redactor)

    /** New chats ask it which built-in integrations to reach through their MCP servers. */
    @Provides
    fun mcpFirstChooser(preference: dev.locus.core.data.mcp.McpPreference): dev.locus.core.data.mcp.McpFirstChooser = preference

    /** What each chat has read, shared by the file tools and undo (which makes chats read an undone file again). */
    @Provides
    @Singleton
    fun fileReads(): dev.locus.core.agent.tools.FileReads = dev.locus.core.agent.tools.FileReads()

    @Provides
    @Singleton
    fun workspace(@ApplicationContext context: Context): Workspace =
        Workspace(File(context.filesDir, "workspace"), File(context.filesDir, "workspace-trash"))
}

/**
 * The app's HTTP clients, in a module of their own so instrumentation tests can replace it
 * (`@TestInstallIn` in app/src/androidTest) with a scripted model provider that never reaches the
 * network. Public for that reason; nothing else refers to it.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    /**
     * The app's one OkHttpClient. Providers with their own network settings (proxy, extra CAs) derive
     * theirs from it with newBuilder(), so they keep the same timeouts.
     */
    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // A backstop for every read, including the wait for a model's response headers. Model streams
        // have their own, clearer idle timeout (dev.locus.core.llm.StreamIdle, at most 5 minutes), which
        // fires first; see docs/design/stream-timeouts.md.
        .readTimeout(6, TimeUnit.MINUTES)
        .build()

    @Provides
    @Singleton
    fun httpClient(okHttp: OkHttpClient): HttpClient = HttpClient(OkHttp) {
        engine { preconfigured = okHttp }
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
        expectSuccess = false
    }
}

/**
 * The app scope's last resort: an exception no coroutine caught (in recovery, a helper's report, a
 * resume) is logged and recorded under Diagnostics instead of ending the process. The work that threw
 * is over either way; everything else keeps running (the scope has a SupervisorJob).
 */
internal fun appScopeErrors(record: (Throwable) -> Unit) = kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
    runCatching { android.util.Log.e("Locus", "Uncaught error in the app scope", error) }
    runCatching { record(error) }
}

