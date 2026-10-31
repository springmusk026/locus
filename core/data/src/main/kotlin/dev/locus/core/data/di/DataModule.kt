package dev.locus.core.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.datastore.SecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
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

@Module
@InstallIn(SingletonComponent::class)
internal object DataModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    @Provides
    @Singleton
    fun agentHome(@ApplicationContext context: Context): AgentHome = AgentHome(File(context.filesDir, "agent-home"))

    @Provides
    fun secretResolver(secrets: SecretStore): SecretResolver = SecretResolver { name -> secrets.get(SecretStore.connectorSecret(name)) }

    @Provides
    @Singleton
    fun workspace(@ApplicationContext context: Context): Workspace =
        Workspace(File(context.filesDir, "workspace"), File(context.filesDir, "workspace-trash"))
}
