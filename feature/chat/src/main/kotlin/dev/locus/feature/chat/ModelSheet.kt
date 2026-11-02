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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.locus.core.data.repository.ModelList
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.ModelLogo
import dev.locus.core.designsystem.ProviderBadge
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusDot
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.Tone
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

    LocusSheet(title = "Model", subtitle = "For this chat only; other chats keep their own.", onDismiss = onDismiss) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search all models") },
            leadingIcon = { Icon(LocusIcons.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = if (searching) {
                { IconButton(onClick = { query = "" }) { Icon(LocusIcons.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp)) } }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        )
        val firstProvider = sections.indexOfFirst { it.provider != null }
        sections.forEachIndexed { index, section ->
            val provider = section.provider
            if (provider == null) {
                SectionLabel(section.title)
                LocusCard(contentPadding = PaddingValues(0.dp)) {
                    section.rows.forEachIndexed { i, row ->
                        if (i > 0) GroupDivider(inset = Spacing.lg)
                        PickerModelRow(row, showProvider = true, onPick = { pick(row) }, onToggleFavorite = { onToggleFavorite(row.ref) })
                    }
                }
            } else {
                val open = searching || provider.id in expanded
                // Under favourites and recents, a label marks where the full lists begin.
                if (index == firstProvider && index > 0) SectionLabel("All models")
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
        if (searching && sections.none { it.rows.isNotEmpty() }) SectionNote("No model matches “${query.trim()}”.", Modifier.padding(top = Spacing.md))
        if (!picker.catalogOn) CatalogOffer(onEnableCatalog)
        SectionNote(
            "Switching model starts a fresh prompt cache for this chat, so the next reply reads the whole conversation again.",
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
    LocusCard(Modifier.padding(bottom = Spacing.sm), contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (canCollapse) Modifier.clickable(onClickLabel = if (open) "Hide models" else "Show models", onClick = onToggle) else Modifier)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderBadge(provider.name, provider.baseUrl, provider.onDevice, size = 28.dp, model = provider.model)
            Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                Text(provider.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    providerStatus(section.list, provider.model),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isCurrent) StatusDot(Tone.Brand, size = 8.dp)
            if (canCollapse) Text(if (open) "Hide" else "Show", Modifier.padding(start = Spacing.sm), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (open) {
            section.rows.forEach { row ->
                GroupDivider(inset = 0.dp)
                PickerModelRow(row, showProvider = false, onPick = { onPick(row) }, onToggleFavorite = { onToggleFavorite(row.ref) })
            }
            when (val list = section.list) {
                is ModelList.Failed -> SectionNote("Couldn't load the model list: ${list.message}", Modifier.padding(top = Spacing.sm))
                null, ModelList.Loading -> SectionNote("Loading models…", Modifier.padding(top = Spacing.sm))
                is ModelList.Loaded -> if (section.more > 0) SectionNote("${section.more} more; search to narrow.", Modifier.padding(top = Spacing.sm))
            }
        }
    }
}

private fun providerStatus(list: ModelList?, default: String): String {
    val base = "Default: ${default.ifBlank { "not set" }}"
    return when (list) {
        is ModelList.Loaded -> "$base · ${list.models.size} models"
        else -> base
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerModelRow(row: PickerRow, showProvider: Boolean, onPick: () -> Unit, onToggleFavorite: () -> Unit) {
    val subtitle = listOfNotNull(
        row.id.takeIf { it != row.title },
        row.providerName.takeIf { showProvider },
        "Default".takeIf { row.isDefault },
        "In use; not in the provider's list".takeIf { row.inUseOnly },
        // Unknown never disqualifies a model, but the sheet says what couldn't be checked for this chat.
        unconfirmedLabel(row.fit),
    ).joinToString(" · ").ifEmpty { null }
    val chips = capabilityChips(row.caps).map { it to Tone.Neutral } +
        listOfNotNull(cantUseLabel(row.fit)?.let { it to Tone.Caution })
    ListRow(
        title = row.title,
        subtitle = subtitle,
        onClick = onPick,
        modifier = Modifier.semantics {
            selected = row.selected
            if (row.selected) stateDescription = "In use"
        },
        leading = { ModelLogo(row.id, size = 28.dp) },
        meta = if (chips.isEmpty()) {
            null
        } else {
            {
                FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    chips.forEach { (label, tone) -> StatusPill(label, tone) }
                }
            }
        },
        trailing = {
            if (row.selected) Icon(LocusIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (row.favorite) LocusIcons.StarFilled else LocusIcons.Star,
                    contentDescription = if (row.favorite) "Remove ${row.title} from favourites" else "Add ${row.title} to favourites",
                    tint = if (row.favorite) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

/** Asks once per sheet whether to fill in model details from models.dev; off until the user says yes. */
@Composable
private fun CatalogOffer(onEnable: () -> Unit) {
    LocusCard(Modifier.padding(top = Spacing.md)) {
        Text("Show context sizes and abilities?", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Locus can download the public models.dev catalogue (about 0.6 MB, at most once a day) to show each model's context window and whether it sees images, calls tools and reasons. Nothing about you or your chats is sent. You can turn this off in Settings → Models.",
            Modifier.padding(top = Spacing.xs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onEnable, modifier = Modifier.padding(top = Spacing.xs), contentPadding = PaddingValues(horizontal = Spacing.sm)) { Text("Turn on") }
    }
}
