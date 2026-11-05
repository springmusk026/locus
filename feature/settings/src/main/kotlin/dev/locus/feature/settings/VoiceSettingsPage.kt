package dev.locus.feature.settings

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
    SettingsPageScaffold("Voice", onBack) {
        item { SectionLabel("Speaking", SectionPadding) }
        item {
            CardGroup {
                // Drag freely; saved when let go, so the store isn't written on every frame.
                var speed by remember(voice.rate) { mutableFloatStateOf(voice.rate) }
                Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
                    Text("Speed: ${speedLabel(speed)}", style = LocusType.cardTitle)
                    SupportingText(
                        "For read-aloud and voice mode.",
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
                    title = "Voice and speech engine",
                    subtitle = "Android's Text-to-speech settings: the engine, its voices and languages.",
                    subtitleMaxLines = 2,
                    onClick = {
                        runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    },
                    leading = { IconTile(LocusIcons.Speaker) },
                    trailing = { RowChevron() },
                )
            }
        }
        item { SectionLabel("Voice mode", SectionPadding) }
        item {
            CardGroup {
                SwitchRow(
                    title = "Listen after each reply",
                    checked = voice.autoListen,
                    onCheckedChange = { on -> onChange { it.copy(autoListen = on) } },
                    subtitle = "Keeps the conversation going hands-free. Off: tap the circle to talk each time.",
                    icon = LocusIcons.Mic,
                )
                GroupDivider(inset = Insets.iconRow)
                SwitchRow(
                    title = "Say what it's doing",
                    checked = voice.spokenStatus,
                    onCheckedChange = { on -> onChange { it.copy(spokenStatus = on) } },
                    subtitle = "A short spoken status while tools run, like “Working on it. Running a command.”",
                    icon = LocusIcons.Activity,
                )
                GroupDivider(inset = Insets.iconRow)
                SwitchRow(
                    title = "Interrupt by speaking",
                    checked = voice.interruptByVoice,
                    onCheckedChange = { on -> onChange { it.copy(interruptByVoice = on) } },
                    subtitle = "Talk over Locus to stop it and be heard. Turn off if it keeps hearing itself on the speaker; tapping always works.",
                    icon = LocusIcons.Waveform,
                )
            }
        }
        item {
            SectionNote(
                when {
                    onDevice -> "Speech recognition: on this phone (Android's on-device recognizer). "
                    anyRecognizer -> "Speech recognition: the phone's speech recognizer, asked to stay offline; it may use its own service when it can't. "
                    else -> "No speech recognizer is installed, so voice mode isn't available. "
                } +
                    "No audio is kept, and the microphone is on only while voice mode shows “Microphone on”. Approvals can be answered by voice " +
                    "only for actions set to Ask; payments, anything after Locus read outside content, and anything the safety reviewer flags need a tap.",
                NotePadding,
            )
        }
    }
}
