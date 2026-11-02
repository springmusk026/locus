package dev.locus.feature.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.locus.core.designsystem.LocalNetworkGate
import dev.locus.core.designsystem.LocalNetworkNote
import dev.locus.core.designsystem.rememberLocalNetworkGate
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ContinueWithChatGptButton
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.ProviderBadge
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.ModelLogo
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.compactCount
import dev.locus.core.llm.ChatGptErrors
import dev.locus.core.model.ApiFormat
import dev.locus.core.ondevice.ModelState

@Composable
internal fun ColumnScope.ProviderStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeading("Choose a model", "Locus talks to the AI model you pick, with your own key. Or run Gemma on this phone, offline.")

    if (state.providers.isNotEmpty()) {
        SectionLabel("Your models")
        CardGroup {
            state.providers.forEachIndexed { i, p ->
                if (i > 0) GroupDivider(Insets.iconRow)
                val isDefault = p.id == state.defaultProvider?.id
                ListRow(
                    p.name,
                    subtitle = p.model.ifBlank { "No model chosen" },
                    onClick = { viewModel.useExisting(p.id) },
                    leading = { ProviderBadge(p.name, p.baseUrl, p.format == ApiFormat.ON_DEVICE, size = 36.dp, model = p.model) },
                    trailing = { if (isDefault) StatusPill("Default", Tone.Brand) },
                )
            }
        }
    }

    val draft = viewModel.provider
    if (draft == null) {
        SectionLabel(if (state.providers.isEmpty()) "Where should it run?" else "Add another")
        CardGroup {
            viewModel.presets.forEachIndexed { i, preset ->
                if (i > 0) GroupDivider(Insets.iconRow)
                ListRow(
                    preset.name,
                    subtitle = preset.note,
                    subtitleMaxLines = 2,
                    onClick = { viewModel.pickPreset(preset) },
                    leading = { ProviderBadge(preset.name, preset.baseUrl, preset.format == ApiFormat.ON_DEVICE, size = 36.dp) },
                )
            }
        }
        return
    }

    SectionLabel(draft.preset.name, action = "Change", onAction = { viewModel.pickPreset(null) })
    LocusCard {
        Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.md)) {
            val localNetwork = rememberLocalNetworkGate()
            if (draft.onDevice) OnDevicePicker(state, viewModel) else RemoteFields(viewModel, localNetwork)
            Button(
                onClick = { if (draft.onDevice) viewModel.saveProvider() else localNetwork.run(draft.baseUrl, viewModel::saveProvider) },
                enabled = draft.canSave && !viewModel.providerSave.running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save and use") }
            JobStatus(viewModel.providerSave, "Saving…")
        }
    }
    Text(
        draft.preset.note,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RemoteFields(viewModel: OnboardingViewModel, localNetwork: LocalNetworkGate) {
    val draft = viewModel.provider ?: return
    if (draft.signsIn) {
        ChatGptSignInFields(viewModel)
        if (!draft.signedIn) return
    } else {
        OutlinedTextField(
            draft.baseUrl,
            { v -> viewModel.editProvider { it.copy(baseUrl = v.trim()) } },
            label = { Text("Base URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        LocalNetworkNote(draft.baseUrl, localNetwork)
    }
    if (draft.preset.requiresKey) {
        OutlinedTextField(
            draft.key,
            { v -> viewModel.editProvider { it.copy(key = v) } },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = { Text("Stored encrypted on this phone. Only sent to ${draft.baseUrl.substringAfter("://").substringBefore('/').ifBlank { "the provider" }}.") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedTextField(
        draft.model,
        { v -> viewModel.editProvider { it.copy(model = v.trim(), contextWindow = null) } },
        label = { Text("Model") },
        placeholder = { Text("Pick from the list or type an ID") },
        singleLine = true,
        supportingText = draft.contextWindow?.let { { Text("Context window: ${compactCount(it.toLong())} tokens", style = MonoCaption) } },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { localNetwork.run(draft.baseUrl, viewModel::loadModels) }, enabled = draft.canListModels && !viewModel.modelList.running) { Text("Load models") }
        JobStatus(viewModel.modelList, "Loading…", Modifier.padding(start = Spacing.md).weight(1f))
    }
    val models = viewModel.remoteModels
    if (models.isNotEmpty()) {
        val matches = models.filter { draft.model.isBlank() || it.id.contains(draft.model, true) || it.displayName.contains(draft.model, true) }
            .ifEmpty { models }
            .take(30)
        CardGroup {
            matches.forEachIndexed { i, m ->
                if (i > 0) GroupDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { viewModel.editProvider { it.copy(model = m.id, contextWindow = m.contextWindow) } }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = draft.model == m.id, onClick = { viewModel.editProvider { it.copy(model = m.id, contextWindow = m.contextWindow) } })
                    ModelLogo(m.id, Modifier.padding(end = Spacing.sm))
                    Column(Modifier.weight(1f)) {
                        Text(m.displayName, style = MaterialTheme.typography.bodyMedium)
                        val meta = listOfNotNull(m.id.takeIf { it != m.displayName }, m.contextWindow?.let { "${compactCount(it.toLong())} context" }).joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** "Continue with ChatGPT" in place of an API key; once signed in, the plan's models load below. */
@Composable
private fun ChatGptSignInFields(viewModel: OnboardingViewModel) {
    val draft = viewModel.provider ?: return
    val uriHandler = LocalUriHandler.current
    if (draft.signedIn) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            JobStatus(viewModel.chatGptSignIn, "", Modifier.weight(1f))
            TextButton(onClick = { uriHandler.openUri(ChatGptErrors.USAGE_URL) }) { Text("Manage usage") }
        }
    } else {
        Text(
            "Use your ChatGPT Plus or Pro plan instead of an API key. Usage counts toward your plan's limits.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ContinueWithChatGptButton(onClick = viewModel::signInChatGpt, busy = viewModel.chatGptSignIn.running)
        Row(verticalAlignment = Alignment.CenterVertically) {
            JobStatus(viewModel.chatGptSignIn, "Finish signing in in the browser…", Modifier.weight(1f))
            if (viewModel.chatGptSignIn.running) TextButton(onClick = viewModel::cancelChatGptSignIn) { Text("Cancel") }
        }
    }
    if (viewModel.chatGptWelcome) {
        LocusSheet(
            title = "You're using your ChatGPT plan",
            subtitle = "Locus's requests to this model now use your ChatGPT plan. You can review and manage that usage in ChatGPT settings.",
            onDismiss = viewModel::dismissChatGptWelcome,
            action = "Got it",
            onAction = viewModel::dismissChatGptWelcome,
        ) {
            TextButton(onClick = { uriHandler.openUri(ChatGptErrors.USAGE_URL) }) { Text("Manage usage") }
        }
    }
}

@Composable
private fun OnDevicePicker(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val draft = viewModel.provider ?: return
    Text(
        "Runs entirely on this phone: no internet, no key, nothing leaves the device. Smaller than cloud models, so best for everyday tasks. You can keep setting up while it downloads.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.onDeviceModels.forEach { (m, modelState) ->
        Column(Modifier.fillMaxWidth().clickable { viewModel.editProvider { it.copy(model = m.id) } }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = draft.model == m.id, onClick = { viewModel.editProvider { it.copy(model = m.id) } })
                Column(Modifier.weight(1f)) {
                    Text("${m.name} · ${"%.1f".format(m.sizeBytes / 1e9)} GB", style = MaterialTheme.typography.bodyMedium)
                    Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    remember(m.id) { viewModel.ramWarning(m.id) }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Locus.colors.caution) }
                }
            }
            Column(Modifier.padding(start = 48.dp)) {
                when (modelState) {
                    ModelState.NotDownloaded -> TextButton(onClick = { viewModel.downloadModel(m.id) }) { Text("Download") }
                    is ModelState.Downloading -> {
                        LinearProgressIndicator(progress = { modelState.bytes.toFloat() / modelState.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${modelState.bytes / 1_000_000} / ${modelState.total / 1_000_000} MB", style = MonoCaption, modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.pauseModel(m.id) }) { Text("Pause") }
                        }
                        if (modelState.wifiOnly) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("On Wi-Fi only: waits while you're on mobile data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                TextButton(onClick = { viewModel.downloadModelOverAnyNetwork(m.id) }) { Text("Use mobile data") }
                            }
                        }
                    }
                    ModelState.Verifying -> StatusPill("Checking the download", Tone.Live, dot = true)
                    ModelState.Ready -> StatusPill("Downloaded", Tone.Live)
                    is ModelState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(modelState.message, style = MaterialTheme.typography.bodySmall, color = Locus.colors.critical, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.downloadModel(m.id) }) { Text("Retry") }
                    }
                }
            }
        }
    }
}
