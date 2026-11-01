package dev.locus.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import androidx.datastore.tink.AeadSerializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.locus.core.model.AppSettings
import dev.locus.core.model.IntegrityKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.security.KeyStore
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class Secrets

@Module
@InstallIn(SingletonComponent::class)
internal object DataStoreModule {
    @Provides
    @Singleton
    fun diagnostics(@ApplicationContext context: Context): DiagnosticsLog = DiagnosticsLog.forApp(context)

    @Provides
    @Singleton
    fun settings(@ApplicationContext context: Context, log: DiagnosticsLog, key: IntegrityKey, alerts: IntegrityAlerts): DataStore<AppSettings> = SealedSettingsStore(
        inner = DataStoreFactory.create(
            serializer = JsonSerializer(AppSettings.serializer(), AppSettings()),
            // Unreadable settings start over from the defaults (a copy is kept); the user is told.
            corruptionHandler = corruptionHandler(
                file = { context.dataStoreFile(DataStoreFiles.SETTINGS) },
                what = "Settings",
                default = AppSettings(),
                log = log,
                notice = SETTINGS_RESET_NOTICE,
            ),
            produceFile = { context.dataStoreFile(DataStoreFiles.SETTINGS) },
        ),
        // Tool permissions are sealed; changes made outside Locus fall back to safe ones (docs/design/linux-isolation.md).
        key = key,
        alerts = alerts,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    /** Seals tool permissions and connector approvals (docs/design/linux-isolation.md). */
    @Provides
    fun integrityKey(key: KeystoreIntegrityKey): IntegrityKey = key

    /**
     * How the vault file is written: AES-256-GCM through Tink, under a keyset wrapped by the Android
     * Keystore. [SecretStore] uses it too, to stage a restored vault in the same format
     * (docs/design/backup.md).
     *
     * If the Keystore key is gone or invalidated (after some OS updates, or a restore onto another
     * phone), the keyset can never be read again: the key, the keyset and the vault file are reset and
     * the user is told to enter their keys again, instead of Locus crashing on every launch.
     */
    @Provides
    @Singleton
    @Secrets
    fun secretsFormat(@ApplicationContext context: Context, log: DiagnosticsLog): Serializer<Map<String, String>> {
        AeadConfig.register()
        val aead = openVaultKey(
            open = {
                AndroidKeysetManager.Builder()
                    .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
                    .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                    .withMasterKeyUri("android-keystore://$MASTER_KEY")
                    .build()
                    .keysetHandle
                    .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            },
            reset = {
                context.getSharedPreferences(KEYSET_PREFS, Context.MODE_PRIVATE).edit().remove(KEYSET_NAME).commit()
                runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MASTER_KEY) }
                // Encrypted under the lost key: it can never be read again.
                context.dataStoreFile(DataStoreFiles.SECRETS).delete()
            },
            onReset = { e ->
                log.record(
                    DiagnosticsLog.Kind.STORAGE,
                    "The key protecting saved keys was invalidated",
                    "Android Keystore: ${e::class.java.simpleName}: ${e.message}${e.cause?.let { " (${it::class.java.simpleName})" }.orEmpty()}\n" +
                        "Saved keys were cleared and a new key was made.",
                )
                log.addNotice(VAULT_RESET_NOTICE)
            },
        )
        // The file name is bound as associated data so ciphertext can't be swapped between stores.
        return AeadSerializer(aead, secretsJson(), DataStoreFiles.SECRETS.encodeToByteArray())
    }

    @Provides
    @Singleton
    @Secrets
    fun secrets(@ApplicationContext context: Context, @Secrets format: Serializer<Map<String, String>>, log: DiagnosticsLog): DataStore<Map<String, String>> =
        DataStoreFactory.create(
            serializer = format,
            // A vault file that doesn't decrypt is kept as a copy and starts over empty; the user is told.
            corruptionHandler = corruptionHandler(
                file = { context.dataStoreFile(DataStoreFiles.SECRETS) },
                what = "Saved keys",
                default = emptyMap(),
                log = log,
                notice = VAULT_RESET_NOTICE,
            ),
            produceFile = { context.dataStoreFile(DataStoreFiles.SECRETS) },
        )

    private const val KEYSET_NAME = "locus_secrets_keyset"
    private const val KEYSET_PREFS = "locus_tink"
    private const val MASTER_KEY = "locus_secrets_master"

    const val VAULT_RESET_NOTICE =
        "Locus couldn't unlock your saved keys (API keys, passwords and sign-ins), so they were cleared. " +
            "Enter them again in Settings. Details are in Settings → About & privacy → Diagnostics."
    const val SETTINGS_RESET_NOTICE =
        "Locus couldn't read its settings file, so settings were reset to the defaults. A copy of the old file was kept. " +
            "Details are in Settings → About & privacy → Diagnostics."
}

internal fun secretsJson(): Serializer<Map<String, String>> = JsonSerializer(MapSerializer(String.serializer(), String.serializer()), emptyMap())
