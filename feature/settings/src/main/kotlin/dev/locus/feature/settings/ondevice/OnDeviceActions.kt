package dev.locus.feature.settings.ondevice

import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import dev.locus.core.ondevice.OnDeviceEngines
import dev.locus.core.ondevice.OnDeviceModels
import dev.locus.core.ondevice.gguf.GgufCatalog
import dev.locus.core.ondevice.gguf.GgufDownloads
import dev.locus.core.ondevice.gguf.GgufModels
import dev.locus.core.ondevice.gguf.GgufOffer
import dev.locus.core.model.isGgufModel
import java.util.UUID
import javax.inject.Inject

/**
 * What the on-device screens do, shared by the library, search and detail view models: start, pause,
 * resume and cancel downloads of either engine, and use, unload or delete an installed model. The
 * download, verification and network rules are in [GgufDownloads] and [OnDeviceModels].
 */
class OnDeviceActions @Inject constructor(
    val liteRt: OnDeviceModels,
    val engines: OnDeviceEngines,
    val gguf: GgufModels,
    val downloads: GgufDownloads,
    private val settings: SettingsRepository,
    private val conversations: ConversationRepository,
) {
    private suspend fun unmeteredOnly() = settings.current().power.downloadsUnmeteredOnly

    /** Starts a download of [key] (a catalogue model of either engine). */
    suspend fun get(key: ModelKey) {
        when (key) {
            is ModelKey.LiteRt -> liteRt.download(key.id, unmeteredOnly())
            is ModelKey.Catalog -> GgufCatalog.models.firstOrNull { it.id == key.id }?.let { downloads.start(downloads.sources.offer(it, liteRt.deviceMemory()), unmeteredOnly()) }
            else -> Unit
        }
    }

    suspend fun get(offer: GgufOffer) = downloads.start(offer, unmeteredOnly())

    fun pause(d: ActiveDownload) = when (d.engine) {
        Engine.LiteRt -> liteRt.cancel(d.id) // keeps the .part file: Resume carries on
        Engine.LlamaCpp -> downloads.pause(d.id)
    }

    suspend fun resume(d: ActiveDownload) = when (d.engine) {
        Engine.LiteRt -> liteRt.download(d.id, unmeteredOnly())
        Engine.LlamaCpp -> downloads.resume(d.id, unmeteredOnly())
    }

    fun useMobileData(d: ActiveDownload) = when (d.engine) {
        Engine.LiteRt -> liteRt.downloadOverAnyNetwork(d.id)
        Engine.LlamaCpp -> downloads.useAnyNetwork(d.id)
    }

    /** Stops and deletes what's downloaded. */
    fun cancel(d: ActiveDownload) = when (d.engine) {
        Engine.LiteRt -> liteRt.delete(d.id)
        Engine.LlamaCpp -> downloads.cancel(d.id)
    }

    fun dismiss(d: ActiveDownload) {
        if (d.engine == Engine.LlamaCpp) downloads.dismiss(d.id)
    }

    /** The "On this phone" provider, made when there isn't one, with [modelId] as its model. */
    private suspend fun onDeviceProvider(modelId: String): ProviderConfig {
        val current = settings.current().providers.firstOrNull { it.format == ApiFormat.ON_DEVICE }
        val config = current?.copy(model = modelId) ?: ProviderConfig(UUID.randomUUID().toString(), "On this phone", ApiFormat.ON_DEVICE, "", modelId, requiresKey = false)
        settings.saveProvider(config, apiKey = null)
        return config
    }

    /** Makes [modelId] the on-device provider's model and that provider the default for new chats. */
    suspend fun setDefault(modelId: String) {
        val provider = onDeviceProvider(modelId)
        settings.setDefaultProvider(provider.id)
    }

    /** A new chat on [modelId]; returns its id. Other chats and the default are left as they are. */
    suspend fun newChat(modelId: String): String {
        val provider = settings.current().providers.firstOrNull { it.format == ApiFormat.ON_DEVICE } ?: onDeviceProvider(modelId)
        val chat = conversations.create(provider.id, explicitModel = true)
        conversations.setModel(chat.id, provider.id, modelId)
        return chat.id
    }

    /** The model the on-device provider uses by default, when it's the default provider. */
    suspend fun defaultModel(): String? {
        val s = settings.current()
        return s.providers.firstOrNull { it.format == ApiFormat.ON_DEVICE && it.id == s.defaultProviderId }?.model
    }

    /** Frees the engine; false while it's replying. */
    suspend fun unload(): Boolean = engines.unload()

    /** Deletes a model's file, unloading it first; false when it's replying and can't be unloaded. */
    suspend fun delete(modelId: String): Boolean {
        if (engines.status.value.modelId == modelId && !engines.unload()) return false
        if (isGgufModel(modelId)) gguf.delete(modelId) else liteRt.delete(modelId)
        return true
    }
}
