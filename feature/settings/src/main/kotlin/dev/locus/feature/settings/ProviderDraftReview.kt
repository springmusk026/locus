package dev.locus.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.CriticalOutlinedButton
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocalNetworkNote
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.ChipVariant
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.PasswordField
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.color
import dev.locus.core.designsystem.onContainer
import dev.locus.core.designsystem.rememberLocalNetworkGate
import dev.locus.core.llm.RemoteModel
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ProviderDraft
import dev.locus.core.model.ProviderDraftApi
import dev.locus.core.model.ProviderDrafts
import dev.locus.core.model.ServiceAddress

/** A provider the agent drafted, as Settings → Models lists it (docs/design/provider-drafts.md). */
data class ProviderDraftRow(
    val draft: ProviderDraft,
    /** The host is a provider Locus knows (a preset, a first-party endpoint, the models.dev catalogue). */
    val knownHost: Boolean,
) {
    /** Neither known nor named by the user in the chat: the review shows the address in the caution tone. */
    val unfamiliar: Boolean get() = !knownHost && !draft.userNamedHost && !draft.local
}

/** What the review sheet hands back on Approve: the provider, the key typed (null for none) and the header values kept. */
data class DraftApproval(val draftId: String, val provider: ProviderConfig, val key: String?, val headers: Map<String, String>)

/** Test connection's outcome in the review sheet. */
internal sealed interface DraftCheck {
    data object Running : DraftCheck
    data class Passed(val models: List<RemoteModel>) : DraftCheck
    data class Failed(val message: String) : DraftCheck
}

/** A draft in the providers list: its name, host and "Needs review". */
@Composable
internal fun ProviderDraftListRow(row: ProviderDraftRow, onClick: () -> Unit) {
    val d = row.draft
    ListRow(
        title = d.name,
        subtitle = stringResource(R.string.settings_draft_row_subtitle, d.host),
        titleMaxLines = 2,
        subtitleMaxLines = 2,
        onClick = onClick,
        leading = { IconTile(if (row.unfamiliar || d.tainted) LocusIcons.Alert else LocusIcons.Sparkle, tint = if (row.unfamiliar || d.tainted) Tone.Caution.onContainer() else MaterialTheme.colorScheme.onSurfaceVariant) },
        trailing = { StatusPill(stringResource(R.string.settings_draft_needs_review), Tone.Caution) },
    )
}

/**
 * The review sheet of a provider draft: where it would send requests, first and prominent; why to
 * double-check it (an address nobody named, a run that read outside content, plain http); what it is;
 * the key; Test connection; Approve or Discard. Everything but the origin can be edited before approving.
 * Nothing is sent anywhere until Test connection or Approve, and only with what the user typed here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProviderDraftSheet(
    row: ProviderDraftRow,
    onTest: (ProviderConfig, String?, (Result<List<RemoteModel>>) -> Unit) -> Unit,
    onApprove: (DraftApproval) -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
    /** Renders: the result of a test already run. */
    initialCheck: DraftCheck? = null,
    /** Renders: a key already typed. */
    initialKey: String = "",
) {
    val draft = row.draft
    var name by rememberSaveable(draft.id) { mutableStateOf(draft.name) }
    var api by rememberSaveable(draft.id) { mutableStateOf(draft.api) }
    var baseUrl by rememberSaveable(draft.id) { mutableStateOf(draft.baseUrl) }
    var model by rememberSaveable(draft.id) { mutableStateOf(draft.models.firstOrNull().orEmpty()) }
    var key by rememberSaveable(draft.id) { mutableStateOf(initialKey) }
    var noKey by rememberSaveable(draft.id) { mutableStateOf(draft.local) }
    var removedHeaders by rememberSaveable(draft.id) { mutableStateOf(emptyList<String>()) }
    var check by remember(draft.id) { mutableStateOf(initialCheck) }
    var generation by remember(draft.id) { mutableIntStateOf(0) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val localNetwork = rememberLocalNetworkGate()
    // Read here, since the test callback runs outside composition.
    val noAnswer = stringResource(R.string.settings_draft_no_answer)

    val address = remember(baseUrl) { ServiceAddress.check(baseUrl) }
    val url = address.url
    // The address the user is looking at now: an edit is judged afresh, never by what the agent wrote.
    val edited = url != null && url != draft.baseUrl
    val host = url?.let { dev.locus.core.model.LocalNetwork.hostOf(it) } ?: draft.host
    val local = url?.let(ProviderDraft::isLocalAddress) ?: draft.local
    val unfamiliar = if (edited) false else row.unfamiliar
    val headers = draft.headers.filterKeys { it !in removedHeaders }
    val needsKey = !noKey
    val ready = name.isNotBlank() && url != null && model.isNotBlank() && (!needsKey || key.isNotBlank())

    fun config(id: String) = ProviderDrafts.approved(draft, id, name, api, url ?: draft.baseUrl, model, requiresKey = needsKey, headerNames = headers.keys.toList())

    LocusSheet(
        title = stringResource(R.string.settings_draft_title),
        subtitle = stringResource(R.string.settings_draft_subtitle, draft.chatTitle ?: stringResource(R.string.settings_draft_a_chat)),
        onDismiss = onDismiss,
        action = stringResource(R.string.settings_draft_approve),
        actionEnabled = ready,
        onAction = {
            val approved = config(java.util.UUID.randomUUID().toString())
            localNetwork.run(approved.baseUrl) { onApprove(DraftApproval(draft.id, approved, key.trim().takeIf { needsKey && it.isNotEmpty() }, headers)) }
        },
    ) {
        Destination(host, url ?: baseUrl, known = row.knownHost && !edited, named = draft.userNamedHost && !edited, local = local, unfamiliar = unfamiliar)
        if (unfamiliar) {
            Banner(
                stringResource(R.string.settings_draft_unfamiliar_text, host),
                Tone.Caution,
                title = stringResource(R.string.settings_draft_unfamiliar_title),
            )
        }
        if (draft.tainted) {
            Banner(
                stringResource(R.string.settings_draft_tainted_text, ProviderDrafts.describeSources(draft.taintSources)),
                Tone.Caution,
                title = stringResource(R.string.settings_draft_tainted_title),
            )
        }
        address.warning?.let { Banner(it, Tone.Caution, title = stringResource(R.string.settings_draft_not_encrypted)) }

        SectionLabel(stringResource(R.string.settings_draft_provider_section))
        OutlinedTextField(name, { name = it.take(ProviderDrafts.MAX_NAME) }, label = { Text(stringResource(R.string.settings_draft_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            baseUrl, { baseUrl = it.trim(); check = null },
            label = { Text(stringResource(R.string.settings_draft_base_url)) },
            singleLine = true,
            isError = address.error != null,
            supportingText = address.error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        url?.let { LocalNetworkNote(it, localNetwork) }
        Text(stringResource(R.string.settings_draft_api_type), style = LocusType.label)
        CardGroup {
            ProviderDraftApi.entries.forEachIndexed { i, a ->
                if (i > 0) GroupDivider(inset = Insets.iconRow)
                val select = { if (api != a) { api = a; check = null } }
                ListRow(
                    title = a.label,
                    // Never cut: at a large font "OpenAI-compatible (Chat Completions)" needs every line it takes.
                    titleMaxLines = Int.MAX_VALUE,
                    onClick = select,
                    leading = { RadioButton(selected = api == a, onClick = select) },
                )
            }
        }

        OutlinedTextField(
            model, { model = it.trim() },
            label = { Text(stringResource(R.string.settings_draft_model)) },
            placeholder = { Text(stringResource(R.string.settings_draft_model_id)) },
            singleLine = true,
            supportingText = { Text(stringResource(R.string.settings_draft_model_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft.models.isNotEmpty()) {
            SupportingText(stringResource(R.string.settings_draft_suggested))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                draft.models.forEach { m ->
                    LocusChip(m, variant = ChipVariant.Filter, selected = model == m, onClick = { model = m })
                }
            }
        }

        if (headers.isNotEmpty()) {
            SectionLabel(stringResource(R.string.settings_draft_headers_section))
            CardGroup {
                headers.entries.forEachIndexed { i, (h, v) ->
                    if (i > 0) GroupDivider()
                    ListRow(
                        title = h,
                        subtitle = v,
                        subtitleMaxLines = 3,
                        trailing = {
                            IconButton(onClick = { removedHeaders = removedHeaders + h }) {
                                Icon(LocusIcons.Close, contentDescription = stringResource(R.string.settings_draft_header_remove, h), modifier = Modifier.size(IconSize.medium))
                            }
                        },
                    )
                }
            }
            SupportingText(stringResource(R.string.settings_draft_headers_note, host))
        }

        SectionLabel(stringResource(R.string.settings_draft_key_section))
        CardGroup {
            SwitchRow(
                title = stringResource(R.string.settings_draft_no_key_title),
                checked = noKey,
                onCheckedChange = { noKey = it; check = null },
                subtitle = stringResource(if (local) R.string.settings_draft_no_key_local else R.string.settings_draft_no_key_other),
                icon = LocusIcons.Key,
            )
        }
        if (needsKey) {
            PasswordField(
                key, { key = it; check = null },
                label = stringResource(R.string.settings_draft_api_key),
                supportingText = stringResource(R.string.settings_draft_api_key_hint, host),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = url != null && (!needsKey || key.isNotBlank()) && check != DraftCheck.Running,
                onClick = {
                    val probe = config(draft.id)
                    val mine = ++generation
                    check = DraftCheck.Running
                    localNetwork.run(probe.baseUrl) {
                        onTest(probe, key.trim().takeIf { needsKey && it.isNotEmpty() }) { result ->
                            if (mine == generation) check = result.fold({ DraftCheck.Passed(it) }, { DraftCheck.Failed(it.message?.take(200) ?: noAnswer) })
                        }
                    }
                },
            ) {
                if (check == DraftCheck.Running) {
                    InlineSpinner(size = IconSize.small)
                    Spacer(Modifier.size(Spacing.sm))
                }
                Text(stringResource(R.string.settings_draft_test))
            }
        }
        DraftCheckResult(check, model)
        if (headers.isNotEmpty()) SupportingText(stringResource(R.string.settings_draft_test_note))

        CriticalOutlinedButton(stringResource(R.string.settings_draft_discard), onClick = { confirmDiscard = true })
    }

    if (confirmDiscard) {
        ConfirmSheet(
            title = stringResource(R.string.settings_draft_discard_title, draft.name),
            message = stringResource(R.string.settings_draft_discard_message, host),
            confirmLabel = stringResource(R.string.settings_draft_discard_confirm),
            tone = Tone.Critical,
            onConfirm = { confirmDiscard = false; onDiscard() },
            onDismiss = { confirmDiscard = false },
        )
    }
}

/**
 * The destination, before anything else: the host large, the full address under it, and whether Locus
 * knows it, the user named it, or neither (the caution tone).
 */
@Composable
private fun Destination(host: String, url: String, known: Boolean, named: Boolean, local: Boolean, unfamiliar: Boolean) {
    val tone = when {
        unfamiliar -> Tone.Caution
        known -> Tone.Live
        else -> Tone.Neutral
    }
    val pill = when {
        local -> stringResource(R.string.settings_draft_pill_local)
        known -> stringResource(R.string.settings_draft_pill_known)
        named -> stringResource(R.string.settings_draft_pill_named)
        else -> stringResource(R.string.settings_draft_pill_unfamiliar)
    }
    LocusCard(outline = if (unfamiliar) Tone.Caution.color() else null) {
        SupportingText(stringResource(R.string.settings_draft_destination))
        Text(
            host.ifBlank { stringResource(R.string.settings_draft_no_address) },
            style = MaterialTheme.typography.headlineSmall,
            color = if (unfamiliar) Tone.Caution.onContainer() else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Spacing.xxs).semantics { heading() },
        )
        SupportingText(url, style = LocusType.mono, maxLines = 3)
        Box(Modifier.padding(top = Spacing.sm)) { StatusPill(pill, tone) }
    }
}

/** Test connection's row, in the email and service sheets' pattern: a status tile, what happened, a pill. */
@Composable
private fun DraftCheckResult(check: DraftCheck?, model: String) {
    check ?: return
    val large = LocalDensity.current.fontScale >= 1.5f
    val listed = (check as? DraftCheck.Passed)?.models
    val hasModel = listed != null && model.isNotBlank() && listed.any { it.id.equals(model, ignoreCase = true) }
    val pill = @Composable {
        when (check) {
            DraftCheck.Running -> InlineSpinner(size = IconSize.small, contentDescription = stringResource(R.string.settings_draft_testing))
            is DraftCheck.Passed -> StatusPill(stringResource(R.string.settings_draft_works), Tone.Live)
            is DraftCheck.Failed -> StatusPill(stringResource(R.string.settings_draft_failed), Tone.Critical)
        }
    }
    CardGroup {
        ListRow(
            title = stringResource(R.string.settings_draft_connection),
            subtitle = when (check) {
                DraftCheck.Running -> stringResource(R.string.settings_draft_asking)
                is DraftCheck.Passed -> when {
                    listed.isNullOrEmpty() -> stringResource(R.string.settings_draft_answered)
                    model.isBlank() -> pluralStringResource(R.plurals.settings_draft_lists, listed.size, listed.size)
                    hasModel -> pluralStringResource(R.plurals.settings_draft_lists_among, listed.size, listed.size, model)
                    else -> pluralStringResource(R.plurals.settings_draft_lists_not, listed.size, listed.size, model)
                }
                is DraftCheck.Failed -> check.message
            },
            subtitleMaxLines = Int.MAX_VALUE,
            meta = if (large) ({ Box(Modifier.padding(top = Spacing.xs)) { pill() } }) else null,
            leading = {
                when (check) {
                    DraftCheck.Running -> IconTile(LocusIcons.Server)
                    is DraftCheck.Passed -> IconTile(LocusIcons.Check, tint = Tone.Live.color())
                    is DraftCheck.Failed -> IconTile(LocusIcons.Error, tint = Tone.Critical.color())
                }
            },
            trailing = if (large) null else ({ pill() }),
        )
    }
}
