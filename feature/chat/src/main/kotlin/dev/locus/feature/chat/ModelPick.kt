package dev.locus.feature.chat

import dev.locus.core.data.repository.ModelList
import dev.locus.core.llm.CatalogModel
import dev.locus.core.llm.RemoteModel
import dev.locus.core.model.ModelRef

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

/** What's known about a model's abilities: from its provider, else the models.dev catalogue. Null / false is "not known". */
internal data class ModelCaps(val context: Int? = null, val vision: Boolean = false, val tools: Boolean = false, val reasoning: Boolean = false)

/** A row as the sheet draws it. [title] is the display name where one is known, else the id. */
internal data class PickerRow(
    val ref: ModelRef,
    val title: String,
    val providerName: String,
    val isDefault: Boolean,
    val selected: Boolean,
    val favorite: Boolean,
    val inUseOnly: Boolean = false,
    val caps: ModelCaps = ModelCaps(),
) {
    val id: String get() = ref.model
    val key: String get() = "${ref.providerId}\u0000${ref.model}"
}

/**
 * A block of the sheet: favourites, recents, or one provider ([provider] set, with its list's
 * state in [list] and how many matches [more] were left out).
 */
internal data class PickerSection(
    val key: String,
    val title: String,
    val rows: List<PickerRow>,
    val provider: ChatProvider? = null,
    val list: ModelList? = null,
    val more: Int = 0,
)

internal const val FAVORITES_KEY = "favorites"
internal const val RECENT_KEY = "recent"

/**
 * The model sheet's contents.
 *
 * Without a [query]: favourites (in the order starred), then recent picks not already starred,
 * then each provider's models (default first, as [providerRows] orders them).
 *
 * With a query, every provider is searched at once (id, display name and provider name; every
 * word must match): matching favourites first, then each provider's matches, its recent picks
 * first, then names that start with the query. Providers still loading or failed stay listed so
 * it's clear their models weren't searched.
 */
internal fun buildModelPicker(
    providers: List<ChatProvider>,
    lists: Map<String, ModelList>,
    currentProviderId: String?,
    controls: ChatControls,
    favorites: List<ModelRef>,
    recents: List<ModelRef>,
    query: String = "",
    catalog: (ChatProvider, String) -> CatalogModel? = { _, _ -> null },
    perProvider: Int = MAX_MODELS_PER_PROVIDER,
): List<PickerSection> {
    val byId = providers.associateBy { it.id }
    val remote: Map<String, Map<String, RemoteModel>> = lists.mapValues { (_, l) -> (l as? ModelList.Loaded)?.models?.associateBy { it.id }.orEmpty() }
    val favoriteSet = favorites.toSet()

    fun row(p: ChatProvider, id: String, isDefault: Boolean, inUseOnly: Boolean = false): PickerRow {
        val listed = remote[p.id]?.get(id)
        val known = catalog(p, id)
        val name = listed?.displayName?.takeIf { it.isNotBlank() && it != id } ?: known?.name
        val ref = ModelRef(p.id, id)
        return PickerRow(
            ref = ref,
            title = name ?: id,
            providerName = p.name,
            isDefault = isDefault,
            selected = p.id == currentProviderId && id == controls.model,
            favorite = ref in favoriteSet,
            inUseOnly = inUseOnly,
            caps = ModelCaps(listed?.contextWindow ?: known?.context, known?.vision == true, known?.tools == true, known?.reasoning == true),
        )
    }

    fun refRow(ref: ModelRef): PickerRow? = byId[ref.providerId]?.let { p -> row(p, ref.model, isDefault = ref.model == p.model) }

    fun providerSection(p: ChatProvider, rows: List<PickerRow>): PickerSection {
        val shown = rows.take(perProvider).let { top -> top + rows.drop(perProvider).filter { it.selected } }
        return PickerSection(p.id, p.name, shown, provider = p, list = lists[p.id], more = rows.size - shown.size)
    }

    fun allRows(p: ChatProvider): List<PickerRow> =
        providerRows(p, (lists[p.id] as? ModelList.Loaded)?.ids, currentProviderId, controls)
            .filter { it.id.isNotBlank() }
            .map { row(p, it.id, it.isDefault, it.inUseOnly) }

    val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (terms.isEmpty()) {
        val favoriteRows = favorites.mapNotNull(::refRow)
        val recentRows = recents.filterNot { it in favoriteSet }.mapNotNull(::refRow)
        return listOfNotNull(
            PickerSection(FAVORITES_KEY, "Favourites", favoriteRows).takeIf { favoriteRows.isNotEmpty() },
            PickerSection(RECENT_KEY, "Recent", recentRows).takeIf { recentRows.isNotEmpty() },
        ) + providers.map { p -> providerSection(p, allRows(p)) }
    }

    fun PickerRow.matches(): Boolean {
        val haystack = "$title $id $providerName".lowercase()
        return terms.all { it in haystack }
    }
    val whole = terms.joinToString(" ")
    val recentRank = recents.withIndex().associate { (i, r) -> r to i }
    val favoriteRows = favorites.mapNotNull(::refRow).filter { it.matches() }
    val sections = providers.mapNotNull { p ->
        val matches = allRows(p)
            .filter { it.matches() && !it.favorite }
            .sortedWith(
                compareBy<PickerRow>(
                    { recentRank[it.ref] ?: Int.MAX_VALUE },
                    { if (it.title.lowercase().startsWith(whole) || it.id.lowercase().startsWith(whole)) 0 else 1 },
                ),
            )
        val list = lists[p.id]
        if (matches.isEmpty() && list is ModelList.Loaded) null else providerSection(p, matches)
    }
    return listOfNotNull(PickerSection(FAVORITES_KEY, "Favourites", favoriteRows).takeIf { favoriteRows.isNotEmpty() }) + sections
}

internal const val MAX_MODELS_PER_PROVIDER = 40

/** "200K", "1M": a context window in the sheet's capability chips. */
internal fun contextLabel(tokens: Int): String = when {
    tokens >= 1_000_000 -> {
        val m = tokens / 1_000_000.0
        if (m % 1.0 < 0.05) "${m.toInt()}M" else "%.1fM".format(java.util.Locale.ROOT, m)
    }
    tokens >= 1_000 -> "${(tokens + 500) / 1_000}K"
    else -> tokens.toString()
}
