package dev.locus.core.datastore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.security.KeyStore
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.concurrent.thread

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
    fun settings(@ApplicationContext context: Context, log: DiagnosticsLog): DataStore<AppSettings> = DataStoreFactory.create(
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
    )

    /**
     * The vault's key: a Tink keyset in SharedPreferences ([VaultKeys.KEYSET_PREFS]), wrapped by an
     * Android Keystore key. Opened lazily, never thrown out on its own: if the Keystore key fails, the
     * vault stays closed until it works again or the user agrees to a reset, which keeps copies
     * (docs/design/diagnostics.md).
     */
    @Provides
    @Singleton
    fun vaultKeys(@ApplicationContext context: Context, log: DiagnosticsLog): VaultKeys {
        AeadConfig.register()
        val prefs = context.getSharedPreferences(VaultKeys.KEYSET_PREFS, Context.MODE_PRIVATE)
        return VaultKeys(
            prefs = object : KeysetPrefs {
                override fun get(name: String): String? = prefs.getString(name, null)
                override fun edit(changes: Map<String, String?>): Boolean = prefs.edit().apply {
                    changes.forEach { (name, value) -> if (value == null) remove(name) else putString(name, value) }
                }.commit()
            },
            masterKeys = object : MasterKeys {
                override fun exists(alias: String): Boolean? =
                    runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias) }.getOrNull()
                override fun delete(alias: String) = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            },
            openKeyset = { keysetName, alias ->
                AndroidKeysetManager.Builder()
                    .withSharedPref(context, keysetName, VaultKeys.KEYSET_PREFS)
                    .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                    .withMasterKeyUri("android-keystore://$alias")
                    .build()
                    .keysetHandle
                    .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            },
            vaultFile = context.dataStoreFile(DataStoreFiles.SECRETS),
            userUnlocked = { context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true },
            log = log,
            onLocked = { retry ->
                // ACTION_USER_UNLOCKED: credential-encrypted storage, and the Keystore, are usable from then on.
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        runCatching { context.unregisterReceiver(this) }
                        thread(name = "vault-unlock") { retry() }
                    }
                }
                val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
                runCatching {
                    if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, filter)
                }
            },
        )
    }

    @Provides
    fun vaultAccess(keys: VaultKeys): VaultAccess = keys

    /**
     * How the vault file is written: AES-256-GCM through Tink, under the keyset [VaultKeys] opens.
     * [SecretStore] uses it too, to stage a restored vault in the same format (docs/design/backup.md).
     * The file name is bound as associated data so ciphertext can't be swapped between stores.
     */
    @Provides
    @Singleton
    @Secrets
    fun secretsFormat(keys: VaultKeys): Serializer<Map<String, String>> = VaultFormat(keys::aead)

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

    const val VAULT_RESET_NOTICE =
        "Locus couldn't unlock your saved keys (API keys, passwords and sign-ins), so they were cleared. " +
            "Enter them again in Settings. Details are in Settings → About & privacy → Diagnostics."
    const val SETTINGS_RESET_NOTICE =
        "Locus couldn't read its settings file, so settings were reset to the defaults. A copy of the old file was kept. " +
            "Details are in Settings → About & privacy → Diagnostics."
}

internal fun secretsJson(): Serializer<Map<String, String>> = JsonSerializer(MapSerializer(String.serializer(), String.serializer()), emptyMap())
