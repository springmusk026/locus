package dev.locus.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.llm.ModelCatalog
import dev.locus.core.llm.ModelCatalogIndex
import dev.locus.core.llm.RemoteModel
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A provider's model list for the model sheet. */
sealed interface ModelList {
    data object Loading : ModelList
    data class Loaded(val models: List<RemoteModel>) : ModelList {
        val ids: List<String> get() = models.map { it.id }
    }
    data class Failed(val message: String) : ModelList
}

/**
 * What the model sheet lists, shared by home and every chat: each provider's models (fetched when a
 * sheet opens, kept for [LIST_TTL_MS] while the provider's settings stay the same) and, when the user
 * turned it on, the models.dev catalogue for context sizes and capabilities.
 */
@Singleton
class ModelDirectory @Inject constructor(
    private val settings: SettingsRepository,
    http: HttpClient,
    @ApplicationContext context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val source = ModelCatalog(http, File(context.cacheDir, "models-dev-catalog.json"))

    private val _lists = MutableStateFlow<Map<String, ModelList>>(emptyMap())
    val lists: StateFlow<Map<String, ModelList>> = _lists.asStateFlow()

    private val _catalog = MutableStateFlow<ModelCatalogIndex?>(null)

    /** The catalogue while it's switched on and has been fetched at least once; null otherwise. */
    val catalog: StateFlow<ModelCatalogIndex?> = _catalog.asStateFlow()

    /** Per provider: the settings its list was fetched with, and when. */
    private val fetched = mutableMapOf<String, Pair<ProviderConfig, Long>>()
    private val inFlight = mutableSetOf<String>()

    /** A model sheet opened: list every provider's models (where stale) and refresh the catalogue if it's on. */
    fun prepare() {
        scope.launch {
            val s = settings.current()
            s.providers.forEach(::load)
            if (s.modelCatalog) {
                if (_catalog.value == null) _catalog.value = source.cached()
                source.get()?.let { _catalog.value = it }
            } else {
                _catalog.value = null
            }
        }
    }

    /** Turns the catalogue on (from the sheet) and fetches it. */
    fun enableCatalog() {
        scope.launch {
            settings.setModelCatalog(true)
            prepare()
        }
    }

    private fun load(p: ProviderConfig) {
        val now = System.currentTimeMillis()
        val sameProvider = synchronized(fetched) {
            if (p.id in inFlight) return
            val previous = fetched[p.id]
            val fresh = previous != null && previous.first == p && now - previous.second < LIST_TTL_MS
            if (fresh && _lists.value[p.id] is ModelList.Loaded) return
            inFlight += p.id
            previous?.first == p
        }
        // A list already shown stays up while it's refreshed, unless the provider's settings changed.
        _lists.update { m -> if (sameProvider && m[p.id] is ModelList.Loaded) m else m + (p.id to ModelList.Loading) }
        scope.launch {
            val result = if (p.format == ApiFormat.ON_DEVICE) {
                ModelList.Loaded(listOf(RemoteModel(p.model)))
            } else {
                runCatching { settings.listModels(p, null) }.fold({ ModelList.Loaded(it) }, { ModelList.Failed(it.message ?: "Couldn't load models.") })
            }
            synchronized(fetched) {
                inFlight -= p.id
                if (result is ModelList.Loaded) fetched[p.id] = p to System.currentTimeMillis()
            }
            // A failed refresh keeps the list it already had.
            _lists.update { m -> if (result is ModelList.Failed && m[p.id] is ModelList.Loaded) m else m + (p.id to result) }
        }
    }

    private companion object {
        const val LIST_TTL_MS = 30 * 60 * 1000L
    }
}
