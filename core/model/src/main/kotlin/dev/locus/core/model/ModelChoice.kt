package dev.locus.core.model

import kotlinx.serialization.Serializable

/** One model of one provider, as favourites and recents remember it. */
@Serializable
data class ModelRef(val providerId: String, val model: String)

/**
 * The model a chat's requests use: the chat's own choice, else the provider's default. The runner,
 * the chat header and the model sheet all resolve it here, so they can't disagree (issue #1).
 */
fun ProviderConfig.effectiveModel(override: String?): String = override?.trim()?.takeIf { it.isNotEmpty() } ?: model

/**
 * What a chat stores when the user picks [picked] of this provider: null (follow the provider's
 * default) for the default itself or nothing, else the trimmed id.
 */
fun ProviderConfig.overrideFor(picked: String?): String? = picked?.trim()?.takeIf { it.isNotEmpty() && it != model }

/** Favourite and recent model lists. */
object ModelRefs {
    const val MAX_RECENT = 6

    /** Stars [ref], or unstars it if it was starred. New favourites go to the end. */
    fun toggle(list: List<ModelRef>, ref: ModelRef): List<ModelRef> = if (ref in list) list - ref else list + ref

    /** Puts [ref] first, without duplicates, keeping at most [max]. */
    fun pushRecent(list: List<ModelRef>, ref: ModelRef, max: Int = MAX_RECENT): List<ModelRef> = (listOf(ref) + (list - ref)).take(max)
}

/** The backup models a chat falls back to ([AppSettings.fallbackModels]). */
object FallbackChain {
    /** How many backup models the list keeps; each one gets its own retries, so a long chain only delays failing. */
    const val MAX = 4

    /**
     * The backups for a chat answered by [primary] on [primaryModel]: the configured list in order,
     * without the chat's own model, providers that are gone or run on the phone, or repeats.
     */
    fun resolve(settings: AppSettings, primary: ProviderConfig, primaryModel: String): List<Pair<ProviderConfig, String>> =
        settings.fallbackModels.mapNotNull { ref ->
            val provider = settings.providers.firstOrNull { it.id == ref.providerId } ?: return@mapNotNull null
            if (provider.format == ApiFormat.ON_DEVICE) return@mapNotNull null
            val model = provider.effectiveModel(ref.model)
            if (model.isBlank() || (provider.id == primary.id && model == primaryModel)) null else provider to model
        }.distinctBy { (p, m) -> p.id to m }.take(MAX)

    /** Adds [ref] at the end, unless it's there already or the list is full. */
    fun add(list: List<ModelRef>, ref: ModelRef): List<ModelRef> = if (ref in list || list.size >= MAX) list else list + ref

    /** Moves the entry at [index] one place up (earlier in the chain). */
    fun moveUp(list: List<ModelRef>, index: Int): List<ModelRef> {
        if (index !in 1..list.lastIndex) return list
        return list.toMutableList().apply { add(index - 1, removeAt(index)) }
    }

    /** "gpt-5.2 (OpenAI)": how a note in the chat names a model. */
    fun label(provider: ProviderConfig, model: String): String = "$model (${provider.name})"
}
