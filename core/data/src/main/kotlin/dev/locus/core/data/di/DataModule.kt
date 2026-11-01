package dev.locus.core.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.ConnectorSeal
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
    fun agentHome(@ApplicationContext context: Context, key: IntegrityKey, alerts: IntegrityAlerts): AgentHome =
        AgentHome(
            File(context.filesDir, "agent-home"),
            // Connectors added or changed outside Locus go back to drafts, and the user is told.
            connectorSeal = ConnectorSeal(key) { ids -> alerts.report(IntegrityAlert.Connectors(ids)) },
        )

    @Provides
    fun secretResolver(secrets: SecretStore): SecretResolver = SecretResolver { name -> secrets.get(SecretStore.connectorSecret(name)) }

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
        // Long gaps are normal while a model thinks before streaming the next token.
        .readTimeout(10, TimeUnit.MINUTES)
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

