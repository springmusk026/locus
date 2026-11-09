package dev.locus.feature.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.LocalNetworkGate
import dev.locus.core.designsystem.LocalNetworkNote
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Meter
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.meterFraction
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
    StepHeading(stringResource(R.string.onboarding_provider_title), stringResource(R.string.onboarding_provider_body))

    if (state.providers.isNotEmpty()) {
        SectionLabel(stringResource(R.string.onboarding_provider_your_models))
        CardGroup {
            state.providers.forEachIndexed { i, p ->
                if (i > 0) GroupDivider(Insets.iconRow)
                val isDefault = p.id == state.defaultProvider?.id
                ListRow(
                    p.name,
                    subtitle = p.model.ifBlank { stringResource(R.string.onboarding_provider_no_model) },
                    onClick = { viewModel.useExisting(p.id) },
                    leading = { ProviderBadge(p.name, p.baseUrl, p.format == ApiFormat.ON_DEVICE, size = IconSize.tile, model = p.model) },
                    trailing = { if (isDefault) StatusPill(stringResource(R.string.onboarding_provider_default), Tone.Brand) },
                )
            }
        }
        // What the two buttons below do (docs/design/onboarding-quick-start.md).
        SupportingText(
            stringResource(R.string.onboarding_provider_quick_start_note),
            style = LocusType.rowSupporting,
        )
    }

    val draft = viewModel.provider
    if (draft == null) {
        SectionLabel(if (state.providers.isEmpty()) stringResource(R.string.onboarding_provider_where_to_run) else stringResource(R.string.onboarding_provider_add_another))
        CardGroup {
            viewModel.presets.forEachIndexed { i, preset ->
                if (i > 0) GroupDivider(Insets.iconRow)
                ListRow(
                    preset.name,
                    subtitle = preset.note,
                    // The note says what to enter; cutting it mid-instruction loses the point.
                    subtitleMaxLines = Int.MAX_VALUE,
                    onClick = { viewModel.pickPreset(preset) },
                    leading = { ProviderBadge(preset.name, preset.baseUrl, preset.format == ApiFormat.ON_DEVICE, size = IconSize.tile) },
                )
            }
        }
        return
    }

    SectionLabel(draft.preset.name, action = stringResource(R.string.onboarding_provider_change), onAction = { viewModel.pickPreset(null) })
    LocusCard {
        Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.md)) {
            val localNetwork = rememberLocalNetworkGate()
            if (draft.onDevice) OnDevicePicker(state, viewModel) else RemoteFields(viewModel, localNetwork)
            Button(
                onClick = { if (draft.onDevice) viewModel.saveProvider() else localNetwork.run(draft.baseUrl, viewModel::saveProvider) },
                enabled = draft.canSave && !viewModel.providerSave.running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_provider_save)) }
            JobStatus(viewModel.providerSave, stringResource(R.string.onboarding_shared_saving))
        }
    }
    SupportingText(draft.preset.note)
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
            label = { Text(stringResource(R.string.onboarding_provider_base_url)) },
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
            label = { Text(stringResource(R.string.onboarding_provider_api_key)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = {
                val host = draft.baseUrl.substringAfter("://").substringBefore('/')
                Text(if (host.isBlank()) stringResource(R.string.onboarding_provider_key_note_provider) else stringResource(R.string.onboarding_provider_key_note_host, host))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedTextField(
        draft.model,
        { v -> viewModel.editProvider { it.copy(model = v.trim(), contextWindow = null) } },
        label = { Text(stringResource(R.string.onboarding_provider_model)) },
        placeholder = { Text(stringResource(R.string.onboarding_provider_model_placeholder)) },
        singleLine = true,
        supportingText = draft.contextWindow?.let { { Text(stringResource(R.string.onboarding_provider_context_window, compactCount(it.toLong())), style = MonoCaption) } },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { localNetwork.run(draft.baseUrl, viewModel::loadModels) }, enabled = draft.canListModels && !viewModel.modelList.running) { Text(stringResource(R.string.onboarding_provider_load_models)) }
        JobStatus(viewModel.modelList, stringResource(R.string.onboarding_shared_loading), Modifier.padding(start = Spacing.md).weight(1f))
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
                        Text(m.displayName, style = LocusType.rowSupporting)
                        val meta = listOfNotNull(m.id.takeIf { it != m.displayName }, m.contextWindow?.let { stringResource(R.string.onboarding_provider_context, compactCount(it.toLong())) }).joinToString(" · ")
                        if (meta.isNotEmpty()) SupportingText(meta, style = LocusType.mono)
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
            TextButton(onClick = { uriHandler.openUri(ChatGptErrors.USAGE_URL) }) { Text(stringResource(R.string.onboarding_provider_chatgpt_manage_usage)) }
        }
    } else {
        SupportingText(stringResource(R.string.onboarding_provider_chatgpt_note))
        ContinueWithChatGptButton(onClick = viewModel::signInChatGpt, busy = viewModel.chatGptSignIn.running)
        Row(verticalAlignment = Alignment.CenterVertically) {
            JobStatus(viewModel.chatGptSignIn, stringResource(R.string.onboarding_provider_chatgpt_finish_sign_in), Modifier.weight(1f))
            if (viewModel.chatGptSignIn.running) TextButton(onClick = viewModel::cancelChatGptSignIn) { Text(stringResource(R.string.onboarding_shared_cancel)) }
        }
    }
    if (viewModel.chatGptWelcome) {
        LocusSheet(
            title = stringResource(R.string.onboarding_provider_chatgpt_welcome_title),
            subtitle = stringResource(R.string.onboarding_provider_chatgpt_welcome_body),
            onDismiss = viewModel::dismissChatGptWelcome,
            action = stringResource(R.string.onboarding_provider_chatgpt_welcome_action),
            onAction = viewModel::dismissChatGptWelcome,
        ) {
            TextButton(onClick = { uriHandler.openUri(ChatGptErrors.USAGE_URL) }) { Text(stringResource(R.string.onboarding_provider_chatgpt_manage_usage)) }
        }
    }
}

@Composable
private fun OnDevicePicker(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val draft = viewModel.provider ?: return
    SupportingText(
        stringResource(R.string.onboarding_provider_device_note),
    )
    state.onDeviceModels.forEach { (m, modelState) ->
        Column(Modifier.fillMaxWidth().clickable { viewModel.editProvider { it.copy(model = m.id) } }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = draft.model == m.id, onClick = { viewModel.editProvider { it.copy(model = m.id) } })
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.onboarding_provider_device_model_size, m.name, m.sizeBytes / 1e9), style = LocusType.rowSupporting)
                    SupportingText(m.description)
                    remember(m.id) { viewModel.ramWarning(m.id) }?.let { Text(it, style = LocusType.supporting, color = Locus.colors.onCautionContainer) }
                }
            }
            // Under the text, past the radio button's touch target.
            Column(Modifier.padding(start = Sizes.touchTarget)) {
                when (modelState) {
                    ModelState.NotDownloaded -> TextButton(onClick = { viewModel.downloadModel(m.id) }) { Text(stringResource(R.string.onboarding_provider_device_download)) }
                    is ModelState.Downloading -> {
                        Meter(meterFraction(modelState.bytes, modelState.total), tone = Tone.Live, contentDescription = stringResource(R.string.onboarding_provider_device_downloaded))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.onboarding_provider_device_progress, modelState.bytes / 1_000_000, modelState.total / 1_000_000), Modifier.weight(1f), style = LocusType.mono)
                            TextButton(onClick = { viewModel.pauseModel(m.id) }) { Text(stringResource(R.string.onboarding_provider_device_pause)) }
                        }
                        if (modelState.wifiOnly) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SupportingText(stringResource(R.string.onboarding_provider_device_wifi_only), Modifier.weight(1f))
                                TextButton(onClick = { viewModel.downloadModelOverAnyNetwork(m.id) }) { Text(stringResource(R.string.onboarding_provider_device_use_mobile_data)) }
                            }
                        }
                    }
                    ModelState.Verifying -> LocusChip(stringResource(R.string.onboarding_provider_device_verifying), tone = Tone.Live, dot = true)
                    ModelState.Ready -> LocusChip(stringResource(R.string.onboarding_provider_device_downloaded), tone = Tone.Live)
                    is ModelState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(modelState.message, style = LocusType.supporting, color = Locus.colors.critical, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.downloadModel(m.id) }) { Text(stringResource(R.string.onboarding_provider_device_retry)) }
                    }
                }
            }
        }
    }
}
