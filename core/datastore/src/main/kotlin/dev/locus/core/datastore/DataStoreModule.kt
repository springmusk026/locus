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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
    fun settings(@ApplicationContext context: Context): DataStore<AppSettings> = DataStoreFactory.create(
        serializer = JsonSerializer(AppSettings.serializer(), AppSettings()),
        produceFile = { context.dataStoreFile(DataStoreFiles.SETTINGS) },
    )

    /**
     * How the vault file is written: AES-256-GCM through Tink, under a keyset wrapped by the Android
     * Keystore. [SecretStore] uses it too, to stage a restored vault in the same format
     * (docs/design/backup.md).
     */
    @Provides
    @Singleton
    @Secrets
    fun secretsFormat(@ApplicationContext context: Context): Serializer<Map<String, String>> {
        AeadConfig.register()
        val aead = AndroidKeysetManager.Builder()
            .withSharedPref(context, "locus_secrets_keyset", "locus_tink")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://locus_secrets_master")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        // The file name is bound as associated data so ciphertext can't be swapped between stores.
        return AeadSerializer(aead, secretsJson(), DataStoreFiles.SECRETS.encodeToByteArray())
    }

    @Provides
    @Singleton
    @Secrets
    fun secrets(@ApplicationContext context: Context, @Secrets format: Serializer<Map<String, String>>): DataStore<Map<String, String>> =
        DataStoreFactory.create(
            serializer = format,
            produceFile = { context.dataStoreFile(DataStoreFiles.SECRETS) },
        )
}

internal fun secretsJson(): Serializer<Map<String, String>> = JsonSerializer(MapSerializer(String.serializer(), String.serializer()), emptyMap())
