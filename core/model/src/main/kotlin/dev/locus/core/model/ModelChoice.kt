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
