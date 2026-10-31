package dev.locus.feature.chat

/**
 * One model row under a provider in the model sheet. [inUseOnly] marks the chat's model when the
 * provider's list doesn't have it (not loaded yet, failed, or the provider stopped listing it).
 */
internal data class PickRow(val providerId: String, val id: String, val isDefault: Boolean, val selected: Boolean, val inUseOnly: Boolean = false)

/**
 * The rows the sheet shows for [p]: its default, the chat's model if the list lacks it, then the
 * rest of [listed]. Exactly the row whose id is the chat's model ([ChatControls.model], resolved by
 * [dev.locus.core.model.effectiveModel] like the runner's) is selected, so the tick always marks
 * what the requests use, whether or not the model is overridden.
 */
internal fun providerRows(p: ChatProvider, listed: List<String>?, currentProviderId: String?, controls: ChatControls): List<PickRow> {
    val inUse = controls.model.takeIf { p.id == currentProviderId && it.isNotBlank() }
    val default = PickRow(p.id, p.model, isDefault = true, selected = inUse == p.model)
    val others = listed.orEmpty().filter { it != p.model }.distinct()
    val missing = inUse?.takeIf { it != p.model && it !in others }?.let { PickRow(p.id, it, isDefault = false, selected = true, inUseOnly = true) }
    return listOf(default) + listOfNotNull(missing) + others.map { PickRow(p.id, it, isDefault = false, selected = it == inUse) }
}
