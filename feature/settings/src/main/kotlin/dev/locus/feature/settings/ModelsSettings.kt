package dev.locus.feature.settings

import dev.locus.core.designsystem.LocalNetworkNote
import dev.locus.core.designsystem.rememberLocalNetworkGate
import dev.locus.core.designsystem.MonoCaption
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import dev.locus.core.ondevice.EngineStatus
import dev.locus.core.ondevice.MemoryGate
import dev.locus.core.ondevice.ModelState
import dev.locus.core.ondevice.OnDeviceModel
import dev.locus.core.ondevice.OnDeviceFormat
import java.net.URI
import java.util.UUID
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.AddRow

private sealed interface Editing {
    data object PickPreset : Editing
    data object AddFallback : Editing
    data class Provider(val config: ProviderConfig, val isNew: Boolean, val hasKey: Boolean, val isDefault: Boolean, val hasBackupKeys: Boolean = false) : Editing
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
) {
    var editing by remember { mutableStateOf<Editing?>(null) }
    var storageFor by remember { mutableStateOf<String?>(null) }

    SettingsPageScaffold("Models", onBack) {
        item { SectionLabel("Providers", SectionPadding) }
        item {
            CardGroup {
                state.providers.forEachIndexed { i, row ->
                    if (i > 0) GroupDivider(inset = Insets.iconRow)
                    ListRow(
                        title = row.config.name,
                        subtitle = row.config.model.ifBlank { "Model not set" },
                        onClick = { editing = Editing.Provider(row.config, isNew = false, hasKey = row.hasKey, isDefault = row.isDefault, hasBackupKeys = row.hasBackupKeys) },
                        leading = { ProviderBadge(row.config.name, row.config.baseUrl, row.config.format == ApiFormat.ON_DEVICE, size = 36.dp, model = row.config.model) },
                        trailing = {
                            when {
                                row.config.signsIn && !row.hasKey -> StatusPill("Signed out", Tone.Caution)
                                row.config.requiresKey && !row.hasKey -> StatusPill("No key", Tone.Caution)
                                row.isDefault -> StatusPill("Default", Tone.Brand)
                            }
                        },
                    )
                }
                if (state.providers.isNotEmpty()) GroupDivider(inset = Insets.iconRow)
                AddRow("Add a model provider") { editing = Editing.PickPreset }
            }
        }
        item {
            SectionNote(
                "Requests go straight from this phone to the provider. Keys and sign-ins are encrypted with a key held in Android's secure hardware. Open a provider to make it the default.",
                NotePadding,
            )
        }

        if (state.providers.any { it.config.format != ApiFormat.ON_DEVICE }) {
            item { SectionLabel("Backup models", SectionPadding) }
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
                    "When a chat's model is out of quota, its key is refused, or it stays busy after a few retries, Locus asks the next model here instead, " +
                        "for the rest of that turn. A reply never mixes two models, and the reply notes which one answered. " +
                        "Switching to another provider starts its prompt cache from zero, so that first reply costs more.",
                    NotePadding,
                )
            }
        }

        item { SectionLabel("Model details", SectionPadding) }
        item {
            CardGroup {
                SwitchRow(
                    "Context sizes and abilities",
                    "From the public models.dev catalogue, shown in the model picker.",
                    LocusIcons.Library,
                    state.modelCatalog,
                    onSetModelCatalog,
                )
            }
        }
        item {
            SectionNote(
                "When on, Locus downloads the catalogue from models.dev at most once a day and keeps a copy on the phone, so the picker works offline. The download carries no keys, chats or identifiers.",
                NotePadding,
            )
        }

        if (state.onDeviceModels.isNotEmpty()) {
            item { SectionLabel("On-device models", SectionPadding) }
            item {
                CardGroup {
                    state.onDeviceModels.forEachIndexed { i, (m, s) ->
                        if (i > 0) GroupDivider(inset = Insets.iconRow)
                        ListRow(
                            title = m.name,
                            subtitle = onDeviceSubtitle(m, state),
                            subtitleMaxLines = 2,
                            onClick = { storageFor = m.id },
                            leading = { IconTile(LocusIcons.Phone) },
                            trailing = { ModelStatePill(s, state.pausedBytes[m.id]) },
                        )
                    }
                }
            }
            item {
                val used = state.onDeviceModels.filter { it.second == ModelState.Ready }.sumOf { it.first.sizeBytes }
                SectionNote(
                    (if (used > 0) "${gb(used)} GB used on this phone. " else "") +
                        "On-device models run without internet or a key. Add the \"On this phone\" provider to chat with one.",
                    NotePadding,
                )
            }
        }
    }

    storageFor?.let { id ->
        val entry = state.onDeviceModels.firstOrNull { it.first.id == id }
        if (entry == null) {
            storageFor = null
        } else {
            LocusSheet(title = entry.first.name, subtitle = entry.first.description, onDismiss = { storageFor = null }) {
                Text("${gb(entry.first.sizeBytes)} GB download", style = MaterialTheme.typography.bodyMedium)
                val ramWarning = state.ramWarning(entry.first)
                ramWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Locus.colors.caution) }
                OnDeviceModelStatus(entry.first, entry.second, onModelAction, indent = false, ramWarning = ramWarning, paused = state.pausedBytes[id])
                if (entry.second == ModelState.Ready) OnDeviceEngineSection(entry.first, state.engine, ramWarning, onModelAction)
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
        is Editing.Provider -> ProviderEditor(
            editing = e,
            chatGptSignedIn = e.config.id in state.chatGptAccounts,
            chatGptEmail = state.chatGptAccounts[e.config.id],
            chatGpt = chatGpt,
            onDeviceModels = state.onDeviceModels,
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
                subtitle = "${i + 1}. ${provider.name}",
                leading = { ProviderBadge(provider.name, provider.baseUrl, false, size = 36.dp, model = model) },
                trailing = {
                    if (i > 0) {
                        androidx.compose.material3.IconButton(onClick = { onChange(FallbackChain.moveUp(chain, i)) }) {
                            androidx.compose.material3.Icon(
                                LocusIcons.ChevronLeft,
                                contentDescription = "Try $model earlier",
                                modifier = Modifier.rotate(90f),
                            )
                        }
                    }
                    androidx.compose.material3.IconButton(onClick = { onChange(chain - ref) }) {
                        androidx.compose.material3.Icon(LocusIcons.Close, contentDescription = "Remove $model")
                    }
                },
            )
        }
        if (chain.size < FallbackChain.MAX) {
            if (chain.isNotEmpty()) GroupDivider(inset = Insets.iconRow)
            AddRow("Add a backup model", onClick = onAdd)
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
        title = "Add a backup model",
        subtitle = "Asked when a chat's own model can't answer. It gets the same request: the same instructions, tools and messages.",
        onDismiss = onDismiss,
        action = "Add",
        actionEnabled = provider != null && model.ifBlank { provider.model }.isNotBlank(),
        onAction = { provider?.let { onAdd(ModelRef(it.id, model.ifBlank { it.model })) } },
    ) {
        CardGroup {
            providers.forEachIndexed { i, p ->
                if (i > 0) GroupDivider(inset = Insets.iconRow)
                val select = { if (id != p.id) { id = p.id; model = "" } }
                ListRow(
                    title = p.name,
                    subtitle = p.model.ifBlank { "Model not set" },
                    onClick = select,
                    leading = { ProviderBadge(p.name, p.baseUrl, false, size = 36.dp, model = p.model) },
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
            Text(
                "Leave empty to use ${provider.model.ifBlank { "the provider's model" }}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelStatePill(state: ModelState, paused: Long?) = when (state) {
    ModelState.NotDownloaded -> if (paused != null) StatusPill("Paused", Tone.Neutral) else StatusPill("Not downloaded", Tone.Neutral)
    is ModelState.Downloading -> StatusPill("${(state.bytes * 100 / state.total.coerceAtLeast(1))}%", Tone.Live, dot = true)
    ModelState.Verifying -> StatusPill("Checking", Tone.Live, dot = true)
    ModelState.Ready -> StatusPill("Downloaded", Tone.Brand)
    is ModelState.Failed -> StatusPill("Failed", Tone.Critical)
}

@Composable
private fun PresetPicker(onPick: (ProviderPreset) -> Unit, onDismiss: () -> Unit) {
    LocusSheet(title = "Add a model provider", subtitle = "Pick where your model runs. You can add several.", onDismiss = onDismiss) {
        CardGroup {
            ProviderPresets.all.forEachIndexed { i, preset ->
                if (i > 0) GroupDivider(inset = Insets.iconRow)
                ListRow(
                    title = preset.name,
                    subtitle = preset.note,
                    subtitleMaxLines = 2,
                    onClick = { onPick(preset) },
                    leading = { ProviderBadge(preset.name, preset.baseUrl, preset.format == ApiFormat.ON_DEVICE, size = 36.dp) },
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
        title = if (editing.isNew) "New provider" else initial.name,
        onDismiss = onDismiss,
        action = "Save",
        actionEnabled = canSave,
        onAction = {
            val config = initial.copy(
                name = name.trim(), baseUrl = baseUrl.trimEnd('/'), model = model, effort = effort,
                contextWindow = contextWindow.filter(Char::isDigit).toIntOrNull()?.takeIf { it > 0 },
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
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (onDevice) {
            OnDeviceModelPicker(onDeviceModels, pausedBytes, ramWarnings, selected = model, onSelect = { model = it }, onAction = onModelAction)
        } else {
            if (signsIn) ChatGptAccountSection(initial.id, chatGptSignedIn, chatGptEmail, chatGpt)
            // A ChatGPT provider's address is fixed: its tokens are only for api.openai.com.
            if (!signsIn) OutlinedTextField(
                baseUrl,
                { baseUrl = it.trim() },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = urlWarning?.let { { Text(it, color = Locus.colors.caution) } },
            )
            if (!signsIn) LocalNetworkNote(baseUrl, localNetwork)
            if (initial.requiresKey) {
                OutlinedTextField(
                    key,
                    { key = it },
                    label = { Text(if (editing.hasKey) "API key (saved — type to replace)" else "API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                val backupsSaved = editing.hasBackupKeys && !clearBackupKeys
                OutlinedTextField(
                    backupKeys,
                    { backupKeys = it },
                    label = { Text(if (backupsSaved) "Backup keys (saved — type to replace)" else "Backup keys (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = {
                        Text(
                            "More keys for this provider, separated by commas. When a key runs out of quota or is refused, the next one takes over. " +
                                "Keys from the same account or project share its limits.",
                        )
                    },
                    trailingIcon = if (backupsSaved && backupKeys.isEmpty()) {
                        { TextButton(onClick = { clearBackupKeys = true }) { Text("Remove") } }
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
                label = { Text("Context window (tokens)") },
                placeholder = { Text("e.g. 200000") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("Filled in from the model list when the provider reports it. Used to show how full a chat is and to summarise before it overflows.") },
                modifier = Modifier.fillMaxWidth(),
            )
            ProviderNetworkRow(network, networkDefaults, onOpen = { editingNetwork = true })
        }
        if (dev.locus.core.model.Effort.supports(initial.format)) {
            Text("Effort", style = MaterialTheme.typography.labelLarge)
            val levels = listOf("low", "medium", "high", "xhigh")
            Segmented(listOf("Low", "Medium", "High", "X-high"), selected = levels.indexOf(effort), onSelect = { effort = levels[it] }, modifier = Modifier.fillMaxWidth())
            Text(
                "Higher effort thinks longer and costs more. High suits most agent work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!editing.isNew) {
            Row {
                if (!editing.isDefault) TextButton(onClick = onMakeDefault) { Text("Make default") }
                Box(Modifier.weight(1f))
                TextButton(onClick = { confirmDelete = true }) { Text("Remove", color = Locus.colors.critical) }
            }
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
        LocusSheet(
            title = "Remove ${initial.name}?",
            subtitle = if (signsIn) {
                "Locus signs out of ChatGPT on this phone. Chats that used it switch to your default provider."
            } else {
                "Its saved API key is deleted from this phone. Chats that used it switch to your default provider."
            },
            onDismiss = { confirmDelete = false },
            action = "Remove",
            actionTone = Tone.Critical,
            onAction = onDelete,
        ) {}
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
    var error by remember { mutableStateOf<String?>(null) }
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
            result.onSuccess { models = it; error = null }.onFailure { error = "Couldn't load models: ${it.message?.take(120)}" }
        }
    }
    val matches = models.filter { model.isBlank() || it.id.contains(model, true) || it.displayName.contains(model, true) }.take(40)
    Column {
        OutlinedTextField(
            model,
            { onModel(it.trim(), null); open = true },
            label = { Text("Model") },
            placeholder = { Text(if (loading) "Loading models…" else "Pick or type a model ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (models.isNotEmpty()) TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "${models.size} models") }
            },
            supportingText = error?.let { { Text(it, color = Locus.colors.critical) } },
        )
        if (open && matches.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    matches.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onModel(m.id, m.contextWindow); open = false }.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ModelLogo(m.id, size = 24.dp)
                            Column(Modifier.weight(1f).padding(start = Spacing.sm)) {
                                Text(m.displayName, style = MaterialTheme.typography.bodyMedium)
                                if (m.displayName != m.id) Text(m.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            m.contextWindow?.let { Text(compactCount(it.toLong()), style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
    Text(
        "Runs entirely on this phone: no internet, no key, nothing leaves the device. Smaller than cloud models, so best for everyday tasks.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    models.forEach { (m, state) ->
        Column(Modifier.fillMaxWidth().clickable { onSelect(m.id) }.padding(vertical = Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == m.id, onClick = { onSelect(m.id) })
                Column(Modifier.weight(1f)) {
                    Text("${m.name} · ${gb(m.sizeBytes)} GB", style = MaterialTheme.typography.bodyMedium)
                    Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ramWarnings[m.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Locus.colors.caution) }
                }
            }
            OnDeviceModelStatus(m, state, onAction, indent = true, ramWarning = ramWarnings[m.id], paused = pausedBytes[m.id])
        }
    }
}

/** "2.6 GB", then the engine's real state when this model is loaded, then a memory caution. */
private fun onDeviceSubtitle(m: OnDeviceModel, state: SettingsUiState): String = listOfNotNull(
    "${gb(m.sizeBytes)} GB",
    OnDeviceFormat.backendLine(state.engine, m.id).takeIf { state.engine.modelId == m.id },
    "Meant for ${m.minRamGb} GB+ phones".takeIf { state.ramChecks[m.id]?.belowMinimum == true },
).joinToString(" · ")

/** The warning to show before downloading or loading [m] on this phone, if any. */
private fun SettingsUiState.ramWarning(m: OnDeviceModel): String? = ramChecks[m.id]?.let { MemoryGate.warning(m.name, it) }

/**
 * Whether [m] is loaded, on which backend (as actually created) and how fast it ran; an Unload button;
 * and the optional local benchmark.
 */
@Composable
private fun OnDeviceEngineSection(m: OnDeviceModel, engine: EngineStatus, ramWarning: String?, onAction: (String, String) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val mine = engine.modelId == m.id
    Text("Engine", style = MaterialTheme.typography.labelLarge)
    Text(OnDeviceFormat.backendLine(engine, m.id), style = MaterialTheme.typography.bodyMedium)
    if (mine) {
        OnDeviceFormat.loadedLine(engine)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
        engine.lastTurn?.let { turn ->
            OnDeviceFormat.turnLine(turn)?.let { Text("Last reply: $it", style = MaterialTheme.typography.bodySmall, color = muted) }
            if (!turn.measured) Text("≈ marks estimates from the streamed reply.", style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
    if (mine && engine.loaded) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (engine.busy) "Replying now. Unload when it finishes." else "Frees its memory. The next on-device message loads it again.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onAction(m.id, "unload") }, enabled = !engine.busy) { Text("Unload") }
        }
    }

    Text("Benchmark", style = MaterialTheme.typography.labelLarge)
    Text(
        "Runs a fixed short prompt (256 tokens in, 128 out) and measures load time and speed. Unloads the model first. Results stay on this phone.",
        style = MaterialTheme.typography.bodySmall,
        color = muted,
    )
    val result = engine.benchmark?.takeIf { it.modelId == m.id }
    result?.let { OnDeviceFormat.benchmark(it).forEach { line -> Text(line, style = MonoCaption) } }
    if (engine.benchmarkError != null && !engine.benchmarking) {
        Text("Benchmark failed: ${engine.benchmarkError}", style = MaterialTheme.typography.bodySmall, color = Locus.colors.critical)
    }
    var confirm by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (engine.benchmarking) {
            Text("Running… this can take a minute.", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.weight(1f))
        } else {
            Box(Modifier.weight(1f))
            TextButton(
                onClick = { if (ramWarning != null) confirm = true else onAction(m.id, "benchmark") },
                enabled = !(mine && engine.busy) && !engine.loading,
            ) { Text(if (result == null) "Run benchmark" else "Run again") }
        }
    }
    if (confirm) {
        LocusSheet(
            title = "Run the benchmark anyway?",
            subtitle = ramWarning,
            onDismiss = { confirm = false },
            action = "Run anyway",
            onAction = { confirm = false; onAction(m.id, "benchmark") },
        ) {}
    }
}

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
        LocusSheet(
            title = "Download ${m.name} anyway?",
            subtitle = ramWarning,
            onDismiss = { confirmDownload = false },
            action = "Download anyway",
            onAction = { confirmDownload = false; onAction(m.id, "download") },
        ) {}
    }
    val download = { if (ramWarning != null) confirmDownload = true else onAction(m.id, "download") }
    when (state) {
        ModelState.NotDownloaded -> if (paused != null) {
            DownloadProgress(paused, m.sizeBytes, pad, "Resume", download)
        } else {
            TextButton(onClick = download, modifier = pad) { Text("Download") }
        }
        is ModelState.Downloading -> Column {
            DownloadProgress(state.bytes, state.total, pad, "Pause") { onAction(m.id, "cancel") }
            if (state.wifiOnly) WifiOnlyRow(pad) { onAction(m.id, "anynetwork") }
        }
        ModelState.Verifying -> Text("Checking the download…", pad, style = MaterialTheme.typography.labelSmall)
        ModelState.Ready -> Row(pad, verticalAlignment = Alignment.CenterVertically) {
            Text("Downloaded", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
        }
        is ModelState.Failed -> Column(pad) {
            Text(state.message, style = MaterialTheme.typography.labelSmall, color = Locus.colors.critical)
            TextButton(onClick = download) { Text("Retry") }
        }
    }
    if (confirmDelete) {
        LocusSheet(
            title = "Delete ${m.name}?",
            subtitle = "Frees ${gb(m.sizeBytes)} GB on this phone. Using it again means downloading it again.",
            onDismiss = { confirmDelete = false },
            action = "Delete",
            actionTone = Tone.Critical,
            onAction = { confirmDelete = false; onAction(m.id, "delete") },
        ) {}
    }
}

/** A download set to wait for Wi-Fi (Settings → Battery & heat), with the way to go ahead on mobile data. */
@Composable
internal fun WifiOnlyRow(modifier: Modifier, onUseMobileData: () -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "On Wi-Fi only: waits while you're on mobile data.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onUseMobileData) { Text("Use mobile data") }
    }
}

/** A download's progress bar with its size so far, and one action (Pause or Resume). */
@Composable
private fun DownloadProgress(bytes: Long, total: Long, modifier: Modifier, action: String, onClick: () -> Unit) {
    Column(modifier) {
        LinearProgressIndicator(progress = { bytes.toFloat() / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${bytes / 1_000_000} / ${total / 1_000_000} MB", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

/** Plain http is fine on a home network; anywhere else it would expose prompts and keys. */
internal fun insecureUrlWarning(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return if (url.isBlank()) null else "Not a valid URL."
    if (uri.scheme == "https") return null
    if (uri.scheme != "http") return "Use an http:// or https:// address."
    val host = uri.host ?: return "Not a valid URL."
    val local = host == "localhost" || host.endsWith(".local") || host.startsWith("10.") || host.startsWith("192.168.") ||
        host.startsWith("127.") || Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(host) || host.startsWith("100.")
    return if (local) null else "Unencrypted connection to a public address. Your prompts and key would be readable in transit."
}
