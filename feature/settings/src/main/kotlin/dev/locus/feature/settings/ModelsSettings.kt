package dev.locus.feature.settings

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.LocalNetworkNote
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Meter
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.UiText
import dev.locus.core.designsystem.asString
import dev.locus.core.designsystem.labelRes
import dev.locus.core.designsystem.meterFraction
import dev.locus.core.designsystem.rememberLocalNetworkGate
import dev.locus.core.designsystem.compactCount
import dev.locus.core.designsystem.ModelLogo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.locus.core.data.network.NetworkSecretEdits
import dev.locus.core.data.network.SavedNetworkSecrets
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.ProviderBadge
import dev.locus.core.designsystem.CriticalOutlinedButton
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.Tone
import dev.locus.core.llm.RemoteModel
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.FallbackChain
import dev.locus.core.model.ModelRef
import dev.locus.core.model.NetworkDefaults
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ProviderPreset
import dev.locus.core.model.ProviderPresets
import dev.locus.core.ondevice.MemoryGate
import dev.locus.core.ondevice.ModelState
import dev.locus.core.ondevice.OnDeviceModel
import dev.locus.core.ui.noteText
import java.net.URI
import java.util.UUID
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.AddRow
import dev.locus.core.designsystem.SwitchRow

private sealed interface Editing {
    data object PickPreset : Editing
    data object AddFallback : Editing
    data class Provider(val config: ProviderConfig, val isNew: Boolean, val hasKey: Boolean, val isDefault: Boolean, val hasBackupKeys: Boolean = false) : Editing

    /** A provider the agent drafted, open for review (docs/design/provider-drafts.md). */
    data class Draft(val id: String) : Editing
}

/** Model providers (cloud and on-device), the default, backup models, and on-device model storage. */
@Composable
internal fun ModelsSettings(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSaveProvider: (ProviderConfig, String?, NetworkSecretEdits, String?) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onSetDefault: (String) -> Unit,
    onModelAction: (String, String) -> Unit,
    onListModels: (ProviderConfig, String?, (Result<List<RemoteModel>>) -> Unit) -> Unit,
    chatGpt: ChatGptActions = ChatGptActions.None,
    onSetModelCatalog: (Boolean) -> Unit = {},
    onSetFallbacks: (List<ModelRef>) -> Unit = {},
    onOpenOnDeviceModels: () -> Unit = {},
    onApproveDraft: (DraftApproval) -> Unit = {},
    onDiscardDraft: (String) -> Unit = {},
    /** A draft to open at once (the chat card's Review); [onDraftOpened] clears the request. */
    openDraft: String? = null,
    onDraftOpened: () -> Unit = {},
) {
    var editing by remember { mutableStateOf<Editing?>(null) }
    LaunchedEffect(openDraft, state.providerDrafts) {
        if (openDraft != null && state.providerDrafts.any { it.draft.id == openDraft }) {
            editing = Editing.Draft(openDraft)
            onDraftOpened()
        } else if (openDraft != null && !state.loading) {
            onDraftOpened()
        }
    }

    SettingsPageScaffold(stringResource(R.string.settings_models_title), onBack) {
        item { SectionLabel(stringResource(R.string.settings_models_providers_section), SectionPadding) }
        item {
            CardGroup {
                state.providers.forEachIndexed { i, row ->
                    if (i > 0) GroupDivider(inset = Insets.iconRow)
                    ListRow(
                        title = row.config.name,
                        subtitle = row.config.model.takeIf { it.isNotBlank() }?.let(dev.locus.core.model.ModelNames::short) ?: stringResource(R.string.settings_models_not_set),
                        titleMaxLines = 2,
                        onClick = { editing = Editing.Provider(row.config, isNew = false, hasKey = row.hasKey, isDefault = row.isDefault, hasBackupKeys = row.hasBackupKeys) },
                        // The provider's own mark, the same on every screen; the model is named in the subtitle.
                        leading = { ProviderBadge(row.config.name, row.config.baseUrl, row.config.format == ApiFormat.ON_DEVICE, size = IconSize.tile) },
                        trailing = {
                            when {
                                row.config.addressUnconfirmed -> StatusPill(stringResource(R.string.settings_models_pill_check_address), Tone.Caution)
                                row.config.signsIn && !row.hasKey -> StatusPill(stringResource(R.string.settings_models_pill_signed_out), Tone.Caution)
                                row.config.requiresKey && !row.hasKey -> StatusPill(stringResource(R.string.settings_models_pill_no_key), Tone.Caution)
                                row.isDefault -> StatusPill(stringResource(R.string.settings_models_pill_default), Tone.Brand)
                            }
                        },
                    )
                }
                // Drafts the agent proposed: listed with the providers, inert until reviewed.
                state.providerDrafts.forEachIndexed { i, row ->
                    if (i > 0 || state.providers.isNotEmpty()) GroupDivider(inset = Insets.iconRow)
                    ProviderDraftListRow(row) { editing = Editing.Draft(row.draft.id) }
                }
                if (state.providers.isNotEmpty() || state.providerDrafts.isNotEmpty()) GroupDivider(inset = Insets.iconRow)
                AddRow(stringResource(R.string.settings_models_add_provider)) { editing = Editing.PickPreset }
            }
        }
        item {
            SectionNote(
                stringResource(if (state.providerDrafts.isNotEmpty()) R.string.settings_models_providers_note_drafts else R.string.settings_models_providers_note),
                NotePadding,
            )
        }

        if (state.providers.any { it.config.format != ApiFormat.ON_DEVICE }) {
            item { SectionLabel(stringResource(R.string.settings_models_backup_section), SectionPadding) }
            item {
                BackupModels(
                    chain = state.fallbackModels,
                    providers = state.providers.map { it.config },
                    onChange = onSetFallbacks,
                    onAdd = { editing = Editing.AddFallback },
                )
            }
            item {
                SectionNote(
                    stringResource(R.string.settings_models_backup_note),
                    NotePadding,
                )
            }
        }

        item { SectionLabel(stringResource(R.string.settings_models_details_section), SectionPadding) }
        item {
            CardGroup {
                SwitchRow(
                    title = stringResource(R.string.settings_models_catalog_title),
                    checked = state.modelCatalog,
                    onCheckedChange = onSetModelCatalog,
                    subtitle = stringResource(R.string.settings_models_catalog_subtitle),
                    icon = LocusIcons.Library,
                )
            }
        }
        item {
            SectionNote(
                stringResource(R.string.settings_models_catalog_note),
                NotePadding,
            )
        }

        item { SectionLabel(stringResource(R.string.settings_models_phone_section), SectionPadding) }
        item {
            CardGroup {
                val installed = state.onDeviceModels.count { it.second == ModelState.Ready } + state.ggufModels.size
                val used = state.onDeviceModels.filter { it.second == ModelState.Ready }.sumOf { it.first.sizeBytes } + state.ggufModels.sumOf { it.model.sizeBytes }
                val downloading = state.onDeviceModels.count { it.second is ModelState.Downloading }
                ListRow(
                    title = stringResource(R.string.settings_models_on_device_title),
                    subtitle = summaryJoin(
                        listOfNotNull(
                            if (installed == 0) stringResource(R.string.settings_models_none_installed) else stringResource(R.string.settings_models_installed, installed, gb(used)),
                            if (downloading > 0) stringResource(R.string.settings_models_downloading, downloading) else null,
                        ),
                    ),
                    onClick = onOpenOnDeviceModels,
                    leading = { IconTile(LocusIcons.Phone) },
                    trailing = { dev.locus.core.designsystem.RowChevron() },
                )
            }
        }
    }

    when (val e = editing) {
        null -> Unit
        Editing.PickPreset -> PresetPicker(
            onPick = { preset ->
                editing = Editing.Provider(
                    ProviderConfig(UUID.randomUUID().toString(), preset.name, preset.format, preset.baseUrl, preset.model, requiresKey = preset.requiresKey),
                    isNew = true,
                    hasKey = false,
                    isDefault = state.providers.isEmpty(),
                )
            },
            onDismiss = { editing = null },
        )
        Editing.AddFallback -> FallbackPicker(
            providers = state.providers.map { it.config }.filter { it.format != ApiFormat.ON_DEVICE },
            onListModels = onListModels,
            onAdd = { ref ->
                onSetFallbacks(FallbackChain.add(state.fallbackModels, ref))
                editing = null
            },
            onDismiss = { editing = null },
        )
        is Editing.Draft -> {
            val row = state.providerDrafts.firstOrNull { it.draft.id == e.id }
            // Approved or discarded elsewhere (or dropped by the seal) while open: nothing left to review.
            if (row == null) LaunchedEffect(e.id) { editing = null } else ProviderDraftSheet(
                row = row,
                onTest = onListModels,
                onApprove = { approval -> onApproveDraft(approval); editing = null },
                onDiscard = { onDiscardDraft(row.draft.id); editing = null },
                onDismiss = { editing = null },
            )
        }
        is Editing.Provider -> ProviderEditor(
            editing = e,
            chatGptSignedIn = e.config.id in state.chatGptAccounts,
            chatGptEmail = state.chatGptAccounts[e.config.id],
            chatGpt = chatGpt,
            onDeviceModels = state.onDeviceModels,
            ggufModels = state.ggufModels,
            pausedBytes = state.pausedBytes,
            ramWarnings = state.onDeviceModels.associate { (m, _) -> m.id to state.ramWarning(m) },
            onModelAction = onModelAction,
            onListModels = onListModels,
            networkDefaults = state.network,
            networkSecrets = state.networkSecrets,
            onSave = { config, key, network, backupKeys ->
                onSaveProvider(config, key, network, backupKeys)
                editing = null
            },
            onDelete = {
                onDeleteProvider(e.config.id)
                editing = null
            },
            onMakeDefault = {
                onSetDefault(e.config.id)
                editing = null
            },
            onDismiss = {
                // A ChatGPT sign-in for a provider that was never saved would be left behind otherwise.
                if (e.isNew && e.config.signsIn) chatGpt.discard(e.config.id)
                editing = null
            },
        )
    }
}

private fun gb(bytes: Long) = "%.1f".format(bytes / 1e9)

/** The fallback chain, in the order it's tried: move a model up, remove it, or add one. */
@Composable
private fun BackupModels(chain: List<ModelRef>, providers: List<ProviderConfig>, onChange: (List<ModelRef>) -> Unit, onAdd: () -> Unit) {
    CardGroup {
        chain.forEachIndexed { i, ref ->
            val provider = providers.firstOrNull { it.id == ref.providerId } ?: return@forEachIndexed
            val model = ref.model.ifBlank { provider.model }
            if (i > 0) GroupDivider(inset = Insets.iconRow)
            ListRow(
                title = model,
                subtitle = stringResource(R.string.settings_models_backup_position, i + 1, provider.name),
                leading = { ProviderBadge(provider.name, provider.baseUrl, false, size = IconSize.tile, model = model) },
                trailing = {
                    if (i > 0) {
                        androidx.compose.material3.IconButton(onClick = { onChange(FallbackChain.moveUp(chain, i)) }) {
                            androidx.compose.material3.Icon(
                                LocusIcons.ChevronLeft,
                                contentDescription = stringResource(R.string.settings_models_backup_earlier, model),
                                modifier = Modifier.rotate(90f),
                            )
                        }
                    }
                    androidx.compose.material3.IconButton(onClick = { onChange(chain - ref) }) {
                        androidx.compose.material3.Icon(LocusIcons.Close, contentDescription = stringResource(R.string.settings_models_backup_remove, model))
                    }
                },
            )
        }
        if (chain.size < FallbackChain.MAX) {
            if (chain.isNotEmpty()) GroupDivider(inset = Insets.iconRow)
            AddRow(stringResource(R.string.settings_models_backup_add), onClick = onAdd)
        }
    }
}

/** Picks a backup model: one of the cloud providers and, optionally, another of its models. */
@Composable
private fun FallbackPicker(
    providers: List<ProviderConfig>,
    onListModels: (ProviderConfig, String?, (Result<List<RemoteModel>>) -> Unit) -> Unit,
    onAdd: (ModelRef) -> Unit,
    onDismiss: () -> Unit,
) {
    var id by remember { mutableStateOf(providers.firstOrNull()?.id) }
    var model by remember { mutableStateOf("") }
    val provider = providers.firstOrNull { it.id == id }
    LocusSheet(
        title = stringResource(R.string.settings_models_backup_add),
        subtitle = stringResource(R.string.settings_models_backup_picker_subtitle),
        onDismiss = onDismiss,
        action = stringResource(R.string.settings_models_backup_picker_action),
        actionEnabled = provider != null && model.ifBlank { provider.model }.isNotBlank(),
        onAction = { provider?.let { onAdd(ModelRef(it.id, model.ifBlank { it.model })) } },
    ) {
        CardGroup {
            providers.forEachIndexed { i, p ->
                if (i > 0) GroupDivider(inset = Insets.iconRow)
                val select = { if (id != p.id) { id = p.id; model = "" } }
                ListRow(
                    title = p.name,
                    subtitle = p.model.takeIf { it.isNotBlank() }?.let(dev.locus.core.model.ModelNames::short) ?: stringResource(R.string.settings_models_not_set),
                    onClick = select,
                    leading = { ProviderBadge(p.name, p.baseUrl, false, size = IconSize.tile, model = p.model) },
                    trailing = { RadioButton(selected = id == p.id, onClick = select) },
                )
            }
        }
        if (provider != null) {
            ModelField(
                model = model,
                onModel = { m, _ -> model = m },
                canLoad = true,
                load = { callback -> onListModels(provider, null, callback) },
                reloadKey = provider.id,
            )
            SupportingText(
                stringResource(R.string.settings_models_leave_empty, provider.model.ifBlank { stringResource(R.string.settings_models_provider_model) }),
            )
        }
    }
}

@Composable
private fun PresetPicker(onPick: (ProviderPreset) -> Unit, onDismiss: () -> Unit) {
    LocusSheet(title = stringResource(R.string.settings_models_add_provider), subtitle = stringResource(R.string.settings_models_preset_subtitle), onDismiss = onDismiss) {
        CardGroup {
            ProviderPresets.all.forEachIndexed { i, preset ->
                if (i > 0) GroupDivider(inset = Insets.iconRow)
                ListRow(
                    title = preset.name,
                    subtitle = preset.noteText(LocalResources.current),
                    titleMaxLines = 2,
                    subtitleMaxLines = Int.MAX_VALUE,
                    onClick = { onPick(preset) },
                    leading = { ProviderBadge(preset.name, preset.baseUrl, preset.format == ApiFormat.ON_DEVICE, size = IconSize.tile) },
                )
            }
        }
    }
}

@Composable
private fun ProviderEditor(
    editing: Editing.Provider,
    chatGptSignedIn: Boolean,
    chatGptEmail: String?,
    chatGpt: ChatGptActions,
    onDeviceModels: List<Pair<OnDeviceModel, ModelState>>,
    ggufModels: List<GgufRow>,
    pausedBytes: Map<String, Long>,
    ramWarnings: Map<String, String?>,
    onModelAction: (String, String) -> Unit,
    onListModels: (ProviderConfig, String?, (Result<List<RemoteModel>>) -> Unit) -> Unit,
    networkDefaults: NetworkDefaults,
    networkSecrets: SavedNetworkSecrets,
    onSave: (ProviderConfig, String?, NetworkSecretEdits, String?) -> Unit,
    onDelete: () -> Unit,
    onMakeDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = editing.config
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var baseUrl by rememberSaveable { mutableStateOf(initial.baseUrl) }
    var model by rememberSaveable { mutableStateOf(initial.model) }
    var key by rememberSaveable { mutableStateOf("") }
    var backupKeys by rememberSaveable { mutableStateOf("") }
    var clearBackupKeys by rememberSaveable { mutableStateOf(false) }
    var effort by rememberSaveable { mutableStateOf(initial.effort) }
    var contextWindow by rememberSaveable { mutableStateOf(initial.contextWindow?.toString().orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val network = remember(initial.id) { NetworkDraft(initial.network) }
    var editingNetwork by remember { mutableStateOf(false) }

    val urlWarning = remember(baseUrl) { insecureUrlWarning(baseUrl) }
    val onDevice = initial.format == ApiFormat.ON_DEVICE
    val signsIn = initial.signsIn
    val hasCredential = if (signsIn) chatGptSignedIn else !initial.requiresKey || editing.hasKey || key.isNotBlank()
    val canSave = name.isNotBlank() && (onDevice || baseUrl.isNotBlank()) && model.isNotBlank() && hasCredential && (onDevice || network.problem == null)
    val localNetwork = rememberLocalNetworkGate()

    LocusSheet(
        title = if (editing.isNew) stringResource(R.string.settings_models_new_provider) else initial.name,
        onDismiss = onDismiss,
        action = stringResource(R.string.settings_models_save),
        actionEnabled = canSave,
        onAction = {
            val config = initial.copy(
                name = name.trim(), baseUrl = baseUrl.trimEnd('/'), model = model, effort = effort,
                // On the phone the window is the model's own: an imported GGUF model's context, LiteRT-LM's fixed one otherwise.
                contextWindow = if (onDevice) {
                    ggufModels.firstOrNull { it.model.id == model }?.model?.contextTokens
                } else {
                    contextWindow.filter(Char::isDigit).toIntOrNull()?.takeIf { it > 0 }
                },
                network = if (onDevice) initial.network else network.network(),
            )
            val newKey = key.takeIf { it.isNotBlank() }
            // Null keeps the saved backup keys; empty removes them.
            val newBackups = backupKeys.takeIf { it.isNotBlank() } ?: "".takeIf { clearBackupKeys }
            if (onDevice) {
                onSave(config, newKey, NetworkSecretEdits(), null)
            } else {
                val secrets = network.secrets()
                localNetwork.run(config.baseUrl) { onSave(config, newKey, secrets, newBackups) }
            }
        },
    ) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.settings_models_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (onDevice) {
            OnDeviceModelPicker(onDeviceModels, pausedBytes, ramWarnings, selected = model, onSelect = { model = it }, onAction = onModelAction)
            GgufModelChoices(ggufModels, selected = model, onSelect = { model = it })
        } else {
            if (signsIn) ChatGptAccountSection(initial.id, chatGptSignedIn, chatGptEmail, chatGpt)
            // A ChatGPT provider's address is fixed: its tokens are only for api.openai.com.
            if (!signsIn) OutlinedTextField(
                baseUrl,
                { baseUrl = it.trim() },
                label = { Text(stringResource(R.string.settings_models_base_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = (
                    urlWarning ?: if (initial.addressUnconfirmed) {
                        UiText.Res(R.string.settings_models_address_changed)
                    } else {
                        null
                    }
                    )?.let { { Text(it.asString(), color = Locus.colors.caution) } },
            )
            if (!signsIn) LocalNetworkNote(baseUrl, localNetwork)
            if (initial.requiresKey) {
                OutlinedTextField(
                    key,
                    { key = it },
                    label = { Text(stringResource(if (editing.hasKey) R.string.settings_models_api_key_saved else R.string.settings_models_api_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                val backupsSaved = editing.hasBackupKeys && !clearBackupKeys
                OutlinedTextField(
                    backupKeys,
                    { backupKeys = it },
                    label = { Text(stringResource(if (backupsSaved) R.string.settings_models_backup_keys_saved else R.string.settings_models_backup_keys)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = {
                        Text(
                            stringResource(R.string.settings_models_backup_keys_hint),
                        )
                    },
                    trailingIcon = if (backupsSaved && backupKeys.isEmpty()) {
                        { TextButton(onClick = { clearBackupKeys = true }) { Text(stringResource(R.string.settings_models_backup_keys_remove)) } }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ModelField(
                model = model,
                onModel = { id, window -> model = id; if (window != null) contextWindow = window.toString() },
                canLoad = baseUrl.isNotBlank() && hasCredential,
                load = { callback -> onListModels(initial.copy(baseUrl = baseUrl.trimEnd('/')), key, callback) },
                reloadKey = baseUrl + key.length + chatGptSignedIn,
            )
            OutlinedTextField(
                contextWindow,
                { contextWindow = it.filter(Char::isDigit).take(8) },
                label = { Text(stringResource(R.string.settings_models_context_window)) },
                placeholder = { Text(stringResource(R.string.settings_models_context_window_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text(stringResource(R.string.settings_models_context_window_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            ProviderNetworkRow(network, networkDefaults, onOpen = { editingNetwork = true })
        }
        if (dev.locus.core.model.Effort.supports(initial.format, baseUrl)) {
            Text(stringResource(R.string.settings_models_effort), style = LocusType.label)
            // Blank is "Default": no level is sent and the model uses its own.
            val levels = listOf("", "low", "medium", "high", "xhigh")
            Segmented(
                listOf(
                    stringResource(R.string.settings_models_effort_default),
                    stringResource(R.string.settings_models_effort_low),
                    stringResource(R.string.settings_models_effort_medium),
                    stringResource(R.string.settings_models_effort_high),
                    stringResource(R.string.settings_models_effort_xhigh),
                ),
                selected = levels.indexOf(effort), onSelect = { effort = levels[it] }, modifier = Modifier.fillMaxWidth())
            val modelDefault = dev.locus.core.model.Effort.modelDefault(initial.format, baseUrl, model)
            SupportingText(
                if (modelDefault != null) {
                    stringResource(R.string.settings_models_effort_note_model, stringResource(modelDefault.labelRes).lowercase())
                } else {
                    stringResource(R.string.settings_models_effort_note)
                },
            )
        }
        if (!editing.isNew) {
            if (!editing.isDefault) TextButton(onClick = onMakeDefault) { Text(stringResource(R.string.settings_models_make_default)) }
            // Removing the provider is the one destructive action here: full width, outlined in the critical colour.
            CriticalOutlinedButton(stringResource(R.string.settings_models_remove_provider), onClick = { confirmDelete = true })
        }
    }

    if (editingNetwork) {
        ProviderNetworkSheet(
            providerId = initial.id,
            providerName = name.ifBlank { initial.name },
            signsIn = signsIn,
            draft = network,
            defaults = networkDefaults,
            savedSecrets = networkSecrets,
            onDismiss = { editingNetwork = false },
        )
    }

    if (confirmDelete) {
        ConfirmSheet(
            title = stringResource(R.string.settings_models_remove_title, initial.name),
            message = stringResource(if (signsIn) R.string.settings_models_remove_message_chatgpt else R.string.settings_models_remove_message_key),
            confirmLabel = stringResource(R.string.settings_models_remove_confirm),
            tone = Tone.Critical,
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * Model picker: fetches the provider's models once the address and key are filled in, and offers
 * them as a searchable list. Typing an id by hand still works for providers that don't list.
 */
@Composable
internal fun ModelField(
    model: String,
    onModel: (String, Int?) -> Unit,
    canLoad: Boolean,
    load: ((Result<List<RemoteModel>>) -> Unit) -> Unit,
    reloadKey: Any,
) {
    var models by remember { mutableStateOf<List<RemoteModel>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiText?>(null) }
    var open by remember { mutableStateOf(false) }
    // Each fetch gets a number; a slower, older answer (e.g. for a half-typed key) is ignored, so its
    // error can't sit under a list that a later fetch loaded.
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(reloadKey, canLoad) {
        if (!canLoad) return@LaunchedEffect
        kotlinx.coroutines.delay(600) // wait until typing pauses
        val mine = ++generation
        loading = true
        error = null
        load { result ->
            if (mine != generation) return@load
            loading = false
            result.onSuccess { models = it; error = null }.onFailure { error = UiText.Res(R.string.settings_models_load_failed, it.message?.take(120).toString()) }
        }
    }
    val matches = models.filter { model.isBlank() || it.id.contains(model, true) || it.displayName.contains(model, true) }.take(40)
    Column {
        OutlinedTextField(
            model,
            { onModel(it.trim(), null); open = true },
            label = { Text(stringResource(R.string.settings_models_model)) },
            placeholder = { Text(stringResource(if (loading) R.string.settings_models_loading else R.string.settings_models_pick)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (models.isNotEmpty()) TextButton(onClick = { open = !open }) { Text(if (open) stringResource(R.string.settings_models_hide) else pluralStringResource(R.plurals.settings_models_count, models.size, models.size)) }
            },
            supportingText = error?.let { { Text(it.asString(), color = Locus.colors.critical) } },
        )
        if (open && matches.isNotEmpty()) {
            CardGroup {
                Column(Modifier.heightIn(max = Sizes.inlineScroll).verticalScroll(rememberScrollState())) {
                    matches.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onModel(m.id, m.contextWindow); open = false }.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ModelLogo(m.id, size = IconSize.large)
                            Column(Modifier.weight(1f).padding(start = Spacing.sm)) {
                                Text(m.displayName, style = LocusType.rowSupporting)
                                if (m.displayName != m.id) SupportingText(m.id, style = LocusType.mono)
                            }
                            m.contextWindow?.let { SupportingText(compactCount(it.toLong()), style = LocusType.mono) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnDeviceModelPicker(
    models: List<Pair<OnDeviceModel, ModelState>>,
    pausedBytes: Map<String, Long>,
    ramWarnings: Map<String, String?>,
    selected: String,
    onSelect: (String) -> Unit,
    onAction: (String, String) -> Unit,
) {
    SupportingText(
        stringResource(R.string.settings_models_on_device_note),
    )
    models.forEach { (m, state) ->
        Column(Modifier.fillMaxWidth().clickable { onSelect(m.id) }.padding(vertical = Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == m.id, onClick = { onSelect(m.id) })
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_models_on_device_size, m.name, gb(m.sizeBytes)), style = LocusType.rowSupporting)
                    SupportingText(m.description)
                    ramWarnings[m.id]?.let { Text(it, style = LocusType.supporting, color = Locus.colors.caution) }
                }
            }
            OnDeviceModelStatus(m, state, onAction, indent = true, ramWarning = ramWarnings[m.id], paused = pausedBytes[m.id])
        }
    }
}

/** Imported GGUF models as choices for the on-device provider's default model. */
@Composable
private fun GgufModelChoices(rows: List<GgufRow>, selected: String, onSelect: (String) -> Unit) {
    if (rows.isEmpty()) {
        SupportingText(stringResource(R.string.settings_models_gguf_empty))
        return
    }
    rows.forEach { row ->
        val m = row.model
        Row(Modifier.fillMaxWidth().clickable { onSelect(m.id) }.padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected == m.id, onClick = { onSelect(m.id) })
            Column(Modifier.weight(1f)) {
                Text(summaryJoin(listOfNotNull(m.name, m.quant, stringResource(R.string.settings_models_gb, gb(m.sizeBytes)))), style = LocusType.rowSupporting)
                SupportingText(stringResource(if (m.tools) R.string.settings_models_gguf_tools else R.string.settings_models_gguf_chat_only))
                row.ramWarning?.let { Text(it, style = LocusType.supporting, color = Locus.colors.caution) }
            }
        }
    }
}

/** The warning to show before downloading or loading [m] on this phone, if any. */
private fun SettingsUiState.ramWarning(m: OnDeviceModel): String? = ramChecks[m.id]?.let { MemoryGate.warning(m.name, it) }

/**
 * Download progress and the action that fits the model's state (download, pause, resume, delete, retry).
 * [paused] is how much of a stopped download is already on the phone; Resume carries on from there.
 */
@Composable
private fun OnDeviceModelStatus(
    m: OnDeviceModel,
    state: ModelState,
    onAction: (String, String) -> Unit,
    indent: Boolean,
    ramWarning: String? = null,
    paused: Long? = null,
) {
    val pad = Modifier.padding(start = if (indent) Spacing.xxl else 0.dp)
    // Below the model's memory minimum: warn and ask before a multi-gigabyte download, but allow it.
    var confirmDownload by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDownload) {
        ConfirmSheet(
            title = stringResource(R.string.settings_models_download_anyway_title, m.name),
            message = ramWarning,
            confirmLabel = stringResource(R.string.settings_models_download_anyway),
            onConfirm = { confirmDownload = false; onAction(m.id, "download") },
            onDismiss = { confirmDownload = false },
        )
    }
    val download = { if (ramWarning != null) confirmDownload = true else onAction(m.id, "download") }
    when (state) {
        ModelState.NotDownloaded -> if (paused != null) {
            DownloadProgress(paused, m.sizeBytes, pad, stringResource(R.string.settings_models_resume), download)
        } else {
            TextButton(onClick = download, modifier = pad) { Text(stringResource(R.string.settings_models_download)) }
        }
        is ModelState.Downloading -> Column {
            DownloadProgress(state.bytes, state.total, pad, stringResource(R.string.settings_models_pause)) { onAction(m.id, "cancel") }
            if (state.wifiOnly) WifiOnlyRow(pad) { onAction(m.id, "anynetwork") }
        }
        ModelState.Verifying -> SupportingText(stringResource(R.string.settings_models_verifying), pad)
        ModelState.Ready -> Row(pad, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_models_downloaded), Modifier.weight(1f), style = LocusType.supporting, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.settings_models_delete)) }
        }
        is ModelState.Failed -> Column(pad) {
            Text(state.message, style = LocusType.supporting, color = Locus.colors.critical)
            TextButton(onClick = download) { Text(stringResource(R.string.settings_models_retry)) }
        }
    }
    if (confirmDelete) {
        ConfirmSheet(
            title = stringResource(R.string.settings_models_delete_title, m.name),
            message = stringResource(R.string.settings_models_delete_message, gb(m.sizeBytes)),
            confirmLabel = stringResource(R.string.settings_models_delete),
            tone = Tone.Critical,
            onConfirm = { confirmDelete = false; onAction(m.id, "delete") },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** A download set to wait for Wi-Fi (Settings → Battery & heat), with the way to go ahead on mobile data. */
@Composable
internal fun WifiOnlyRow(modifier: Modifier, onUseMobileData: () -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        SupportingText(stringResource(R.string.settings_models_wifi_only), Modifier.weight(1f))
        TextButton(onClick = onUseMobileData) { Text(stringResource(R.string.settings_models_use_mobile_data)) }
    }
}

/** A download's progress bar with its size so far, and one action (Pause or Resume). */
@Composable
private fun DownloadProgress(bytes: Long, total: Long, modifier: Modifier, action: String, onClick: () -> Unit) {
    Column(modifier) {
        Meter(meterFraction(bytes, total), tone = Tone.Live, contentDescription = stringResource(R.string.settings_models_downloaded))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_models_progress, bytes / 1_000_000, total / 1_000_000), Modifier.weight(1f), style = LocusType.mono)
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

/** Plain http is fine on a home network; anywhere else it would expose prompts and keys. */
internal fun insecureUrlWarning(url: String): UiText? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return if (url.isBlank()) null else UiText.Res(R.string.settings_models_invalid_url)
    if (uri.scheme == "https") return null
    if (uri.scheme != "http") return UiText.Res(R.string.settings_models_use_http)
    if (uri.host == null) return UiText.Res(R.string.settings_models_invalid_url)
    // The same local/public rule as the MCP server form (dev.locus.core.model.LocalNetwork.cleartextWarning).
    return dev.locus.core.model.LocalNetwork.cleartextWarning(url)
        ?.let { UiText.Res(R.string.settings_models_unencrypted) }
}
