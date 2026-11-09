package dev.locus.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.locus.core.data.repository.ModelList
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.ModelLogo
import dev.locus.core.designsystem.ProviderBadge
import dev.locus.core.designsystem.SearchField
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusDot
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.UiText
import dev.locus.core.designsystem.asString
import dev.locus.core.model.ModelRef

/**
 * Pick the model for this chat: one search over every provider, favourites and recent picks on
 * top, then each provider's models. Rows show the model's logo, its display name where the
 * provider (or the catalogue) has one, and what's known of its context window and abilities.
 */
@Composable
internal fun ModelSheet(
    state: ComposerSettings,
    onPick: (providerId: String, model: String?) -> Unit,
    onOpen: () -> Unit,
    onToggleFavorite: (ModelRef) -> Unit,
    onEnableCatalog: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { onOpen() }
    val current = state.provider
    val picker = state.picker
    var query by rememberSaveable { mutableStateOf("") }
    // Only the chat's provider starts open; the rest open on tap (or all while searching).
    var expanded by rememberSaveable { mutableStateOf(listOfNotNull(current?.id)) }
    val searching = query.isNotBlank()
    val sections = remember(state.providers, picker, current?.id, state.controls, query) {
        val catalog = picker.catalog
        buildModelPicker(
            providers = state.providers,
            lists = picker.lists,
            currentProviderId = current?.id,
            controls = state.controls,
            favorites = picker.favorites,
            recents = picker.recents,
            query = query,
            catalog = { p, id -> catalog?.lookup(p.baseUrl, p.format, id) },
            needs = picker.needs,
        )
    }
    // A pick of the provider's default stores no override, so the chat follows the default.
    val pick = { row: PickerRow -> onPick(row.ref.providerId, row.id) }

    LocusSheet(title = stringResource(R.string.chat_modelsheet_title), subtitle = stringResource(R.string.chat_modelsheet_subtitle), onDismiss = onDismiss) {
        SearchField(query, onQueryChange = { query = it }, placeholder = stringResource(R.string.chat_modelsheet_search), modifier = Modifier.padding(vertical = Spacing.xs))
        val anyChatOnly = sections.any { s -> s.rows.any { it.caps.tools == dev.locus.core.llm.Support.NO } }
        chatOnlyNote(state.controls, anyChatOnly)?.let { SectionNote(it.asString(), Modifier.padding(bottom = Spacing.sm)) }
        val firstProvider = sections.indexOfFirst { it.provider != null }
        sections.forEachIndexed { index, section ->
            val provider = section.provider
            if (provider == null) {
                SectionLabel(
                    when (section.key) {
                        FAVORITES_KEY -> stringResource(R.string.chat_modelsheet_favourites)
                        RECENT_KEY -> stringResource(R.string.chat_modelsheet_recent)
                        else -> section.title
                    },
                )
                LocusCard(contentPadding = PaddingValues(0.dp)) {
                    section.rows.forEachIndexed { i, row ->
                        if (i > 0) GroupDivider(inset = Spacing.lg)
                        PickerModelRow(row, showProvider = true, onPick = { pick(row) }, onToggleFavorite = { onToggleFavorite(row.ref) })
                    }
                }
            } else {
                val open = searching || provider.id in expanded
                // Under favourites and recents, a label marks where the full lists begin.
                if (index == firstProvider && index > 0) SectionLabel(stringResource(R.string.chat_modelsheet_all))
                ProviderCard(
                    section = section,
                    provider = provider,
                    isCurrent = provider.id == current?.id,
                    open = open,
                    canCollapse = !searching,
                    onToggle = { expanded = if (provider.id in expanded) expanded - provider.id else expanded + provider.id },
                    onPick = pick,
                    onToggleFavorite = onToggleFavorite,
                )
            }
        }
        if (searching && sections.none { it.rows.isNotEmpty() }) SectionNote(stringResource(R.string.chat_modelsheet_no_match, query.trim()), Modifier.padding(top = Spacing.md))
        if (!picker.catalogOn) CatalogOffer(onEnableCatalog)
        // On-device models live on their own screen; the app routes this link there.
        dev.locus.core.designsystem.Locus.settings?.let { links ->
            LocusCard(Modifier.padding(top = Spacing.md), contentPadding = PaddingValues(0.dp)) {
                dev.locus.core.designsystem.AddRow(stringResource(R.string.chat_modelsheet_get_on_device), icon = dev.locus.core.designsystem.LocusIcons.Phone) {
                    onDismiss()
                    links.open(dev.locus.core.model.SettingsDestination.OnDeviceModels)
                }
            }
        }
        SectionNote(
            stringResource(R.string.chat_modelsheet_cache_note),
            Modifier.padding(top = Spacing.md),
        )
    }
}

@Composable
private fun ProviderCard(
    section: PickerSection,
    provider: ChatProvider,
    isCurrent: Boolean,
    open: Boolean,
    canCollapse: Boolean,
    onToggle: () -> Unit,
    onPick: (PickerRow) -> Unit,
    onToggleFavorite: (ModelRef) -> Unit,
) {
    val hideShowLabel = stringResource(if (open) R.string.chat_modelsheet_hide else R.string.chat_modelsheet_show)
    val expandedLabel = stringResource(if (open) R.string.chat_modelsheet_expanded else R.string.chat_modelsheet_collapsed)
    LocusCard(Modifier.padding(bottom = Spacing.sm), contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .then(
                    if (canCollapse) {
                        Modifier
                            .clickable(onClickLabel = hideShowLabel, onClick = onToggle)
                            .semantics { stateDescription = expandedLabel }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderBadge(provider.name, provider.baseUrl, provider.onDevice, size = IconSize.tileSmall, model = provider.model)
            Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                Text(provider.name, style = LocusType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Two lines: the default and the model count are both worth reading.
                SupportingText(providerStatusText(section.list, provider.model), maxLines = 2)
            }
            if (isCurrent) StatusDot(Tone.Brand)
            // The row says Show or Hide models to TalkBack (onClickLabel); the chevron shows which way it goes.
            if (canCollapse) Icon(LocusIcons.ChevronRight, contentDescription = null, modifier = Modifier.padding(start = Spacing.sm).rotate(if (open) -90f else 90f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            section.rows.forEach { row ->
                GroupDivider(inset = 0.dp)
                PickerModelRow(row, showProvider = false, onPick = { onPick(row) }, onToggleFavorite = { onToggleFavorite(row.ref) })
            }
            when (val list = section.list) {
                is ModelList.Failed -> SectionNote(stringResource(R.string.chat_modelsheet_load_failed, list.message), Modifier.padding(top = Spacing.sm))
                null, ModelList.Loading -> SectionNote(stringResource(R.string.chat_modelsheet_loading), Modifier.padding(top = Spacing.sm))
                is ModelList.Loaded -> if (section.more > 0) SectionNote(stringResource(R.string.chat_modelsheet_more, section.more), Modifier.padding(top = Spacing.sm))
            }
        }
    }
}

/**
 * What the sheet says about tools and Chat only models: before the first message, that picking one
 * starts the chat without tools; once a chat-only chat has started, that it stays that way.
 */
internal fun chatOnlyNote(controls: ChatControls, anyChatOnly: Boolean): UiText? = when {
    controls.chatOnly && !controls.empty -> UiText.Res(R.string.chat_modelsheet_chat_only_started)
    controls.empty && anyChatOnly -> UiText.Res(R.string.chat_modelsheet_chat_only_new)
    else -> null
}

@Composable
private fun providerStatusText(list: ModelList?, default: String): String {
    val shown = (list as? ModelList.Loaded)?.models?.firstOrNull { it.id == default }?.takeIf { it.detail != null }?.displayName ?: default
    val base = stringResource(R.string.chat_modelsheet_default_is, shown.ifBlank { stringResource(R.string.chat_modelsheet_not_set) })
    return when (list) {
        is ModelList.Loaded -> pluralStringResource(R.plurals.chat_modelsheet_status_models, list.models.size, base, list.models.size)
        else -> base
    }
}

internal fun providerStatus(list: ModelList?, default: String): String {
    // The listed name where there is one: an on-device default is a "gguf:…" key, not a name.
    val shown = (list as? ModelList.Loaded)?.models?.firstOrNull { it.id == default }?.takeIf { it.detail != null }?.displayName ?: default
    val base = "Default: ${shown.ifBlank { "not set" }}"
    return when (list) {
        is ModelList.Loaded -> "$base · ${list.models.size} model${if (list.models.size == 1) "" else "s"}"
        else -> base
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerModelRow(row: PickerRow, showProvider: Boolean, onPick: () -> Unit, onToggleFavorite: () -> Unit) {
    val inUse = stringResource(R.string.chat_modelsheet_in_use)
    val subtitle = listOfNotNull(
        row.detail ?: row.id.takeIf { it != row.title },
        row.providerName.takeIf { showProvider },
        stringResource(R.string.chat_modelsheet_default).takeIf { row.isDefault },
        stringResource(R.string.chat_modelsheet_in_use_unlisted).takeIf { row.inUseOnly },
        // Unknown never disqualifies a model, but the sheet says what couldn't be checked for this chat.
        unconfirmedLabel(row.fit),
    ).joinToString(" · ").ifEmpty { null }
    // The chat's model says so in its chips, not with a check in the trailing column, so the name
    // and detail keep the row's width beside the star (at 200% font too).
    val chips = listOfNotNull((inUse to Tone.Brand).takeIf { row.selected }) +
        capabilityChips(row.caps).map { it to Tone.Neutral } +
        listOfNotNull(chatOnlyChip(row.caps, row.fit)?.let { it to Tone.Caution }, cantUseLabel(row.fit)?.let { it to Tone.Caution })
    ListRow(
        title = row.title,
        subtitle = subtitle,
        // The name and detail ("Q4_K_M · 0.5 GB · llama.cpp") wrap rather than hide behind the star.
        titleMaxLines = 2,
        subtitleMaxLines = 3,
        onClick = onPick,
        modifier = Modifier.semantics {
            selected = row.selected
            if (row.selected) stateDescription = inUse
        },
        leading = { ModelLogo(row.id, size = IconSize.tileSmall) },
        meta = if (chips.isEmpty()) {
            null
        } else {
            {
                dev.locus.core.designsystem.ChipFlow {
                    chips.forEach { (label, tone) -> LocusChip(label, tone = tone) }
                }
            }
        },
        trailing = {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (row.favorite) LocusIcons.StarFilled else LocusIcons.Star,
                    contentDescription = stringResource(if (row.favorite) R.string.chat_modelsheet_unfavourite else R.string.chat_modelsheet_favourite, row.title),
                    tint = if (row.favorite) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSize.medium),
                )
            }
        },
    )
}

/** Asks once per sheet whether to fill in model details from models.dev; off until the user says yes. */
@Composable
private fun CatalogOffer(onEnable: () -> Unit) {
    LocusCard(Modifier.padding(top = Spacing.md)) {
        Text(stringResource(R.string.chat_modelsheet_catalog_title), style = LocusType.rowTitle)
        SupportingText(
            stringResource(R.string.chat_modelsheet_catalog_body),
            Modifier.padding(top = Spacing.xs),
        )
        dev.locus.core.designsystem.ActionRow(contentPadding = PaddingValues(0.dp)) {
            dev.locus.core.designsystem.SettingsLink(dev.locus.core.model.SettingsDestination.Models)
            TextButton(onClick = onEnable) { Text(stringResource(R.string.chat_modelsheet_turn_on)) }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "On-device", widthDp = 360, heightDp = 900)
@Composable
private fun ModelSheetOnDevicePreview() {
    val phone = chatProvider(dev.locus.core.model.ProviderConfig("p", "On this phone", dev.locus.core.model.ApiFormat.ON_DEVICE, "", "gguf:qwen3.5-0.8b-q4_k_m", requiresKey = false))
    val anthropic = chatProvider(dev.locus.core.model.ProviderConfig("a", "Anthropic", dev.locus.core.model.ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5"))
    val lists = mapOf(
        "p" to dev.locus.core.data.repository.ModelList.Loaded(
            listOf(
                dev.locus.core.llm.RemoteModel("gguf:qwen3.5-0.8b-q4_k_m", "Qwen3.5 0.8B", contextWindow = 16_384, tools = false, detail = "Q4_K_M · 0.5 GB · llama.cpp"),
                dev.locus.core.llm.RemoteModel("gguf:qwen3.5-2b-q4_k_m", "Qwen3.5 2B", contextWindow = 16_384, tools = true, detail = "Q4_K_M · 1.3 GB · llama.cpp"),
                dev.locus.core.llm.RemoteModel("gguf:gemma-3-1b-it-q8_0", "Gemma 3 1B", contextWindow = 16_384, tools = false, detail = "Q8_0 · 1.1 GB · llama.cpp"),
            ),
        ),
        "a" to dev.locus.core.data.repository.ModelList.Loaded(listOf(dev.locus.core.llm.RemoteModel("claude-opus-5-5", "Claude Opus 5.5"))),
    )
    dev.locus.core.designsystem.LocusTheme {
        ModelSheet(
            ComposerSettings(provider = phone, providers = listOf(phone, anthropic), controls = ChatControls(model = "gguf:qwen3.5-0.8b-q4_k_m", chatOnly = true), picker = ModelPickerData(lists = lists)),
            onPick = { _, _ -> }, onOpen = {}, onToggleFavorite = {}, onEnableCatalog = {}, onDismiss = {},
        )
    }
}
