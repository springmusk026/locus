package dev.locus.feature.settings

import android.provider.Settings
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.Banner
import android.content.Intent
import android.os.Build
import android.speech.SpeechRecognizer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.RowChevron
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.model.VoiceSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings → Voice: read-aloud speed and voice mode (docs/design/voice-mode.md). */
@HiltViewModel
class VoiceSettingsViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    val voice: StateFlow<VoiceSettings> = settings.settings.map { it.voice }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceSettings())

    fun update(transform: (VoiceSettings) -> VoiceSettings) = viewModelScope.launch { settings.setVoice(transform) }
}

@Composable
internal fun VoiceRoute(onBack: () -> Unit, viewModel: VoiceSettingsViewModel = hiltViewModel()) {
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    VoiceSettingsPage(voice, onBack, viewModel::update)
}

/** "1.25×" for a speed. */
internal fun speedLabel(rate: Float): String {
    val r = Math.round(rate * 100) / 100f
    return (if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString().trimEnd('0')) + "×"
}

@Composable
internal fun VoiceSettingsPage(voice: VoiceSettings, onBack: () -> Unit, onChange: (transform: (VoiceSettings) -> VoiceSettings) -> Unit) {
    val context = LocalContext.current
    val onDevice = remember { Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false) }
    val anyRecognizer = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    SettingsPageScaffold(stringResource(R.string.settings_voice_title), onBack) {
        item { SectionLabel(stringResource(R.string.settings_voice_speaking_section), SectionPadding) }
        item {
            CardGroup {
                // Drag freely; saved when let go, so the store isn't written on every frame.
                var speed by remember(voice.rate) { mutableFloatStateOf(voice.rate) }
                Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
                    Text(stringResource(R.string.settings_voice_speed, speedLabel(speed)), style = LocusType.cardTitle)
                    SupportingText(
                        stringResource(R.string.settings_voice_speed_note),
                    )
                    Slider(
                        value = speed,
                        onValueChange = { speed = it },
                        onValueChangeFinished = { onChange { v -> v.copy(speechRate = speed) } },
                        valueRange = VoiceSettings.MIN_RATE..VoiceSettings.MAX_RATE,
                        steps = 5,
                        modifier = Modifier.semantics { stateDescription = speedLabel(speed) },
                    )
                }
                GroupDivider(inset = Insets.iconRow)
                ListRow(
                    title = stringResource(R.string.settings_voice_engine_title),
                    subtitle = stringResource(R.string.settings_voice_engine_subtitle),
                    // Read whole: the last words say what's there.
                    subtitleMaxLines = 3,
                    onClick = {
                        runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    },
                    leading = { IconTile(LocusIcons.Speaker) },
                    trailing = { RowChevron() },
                )
            }
        }
        item { SectionLabel(stringResource(R.string.settings_voice_mode_section), SectionPadding) }
        // No recognizer: voice mode can't listen, so its switches are off limits and one line says why.
        if (!anyRecognizer) {
            item {
                Banner(stringResource(R.string.settings_voice_no_recognizer), Tone.Caution, Modifier.padding(bottom = Spacing.sm)) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                    }) { Text(stringResource(R.string.settings_voice_speech_settings)) }
                }
            }
        }
        item {
            CardGroup {
                SwitchRow(
                    title = stringResource(R.string.settings_voice_listen_title),
                    checked = voice.autoListen,
                    onCheckedChange = { on -> onChange { it.copy(autoListen = on) } },
                    subtitle = stringResource(R.string.settings_voice_listen_subtitle),
                    icon = LocusIcons.Mic,
                    enabled = anyRecognizer,
                )
                GroupDivider(inset = Insets.iconRow)
                SwitchRow(
                    title = stringResource(R.string.settings_voice_status_title),
                    checked = voice.spokenStatus,
                    onCheckedChange = { on -> onChange { it.copy(spokenStatus = on) } },
                    subtitle = stringResource(R.string.settings_voice_status_subtitle),
                    icon = LocusIcons.Activity,
                    enabled = anyRecognizer,
                )
                GroupDivider(inset = Insets.iconRow)
                SwitchRow(
                    title = stringResource(R.string.settings_voice_interrupt_title),
                    checked = voice.interruptByVoice,
                    onCheckedChange = { on -> onChange { it.copy(interruptByVoice = on) } },
                    subtitle = stringResource(R.string.settings_voice_interrupt_subtitle),
                    icon = LocusIcons.Waveform,
                    enabled = anyRecognizer,
                )
            }
        }
        item {
            SectionNote(
                stringResource(
                    when {
                        onDevice -> R.string.settings_voice_note_on_device
                        anyRecognizer -> R.string.settings_voice_note_service
                        else -> R.string.settings_voice_note_none
                    },
                ),
                NotePadding,
            )
        }
    }
}
