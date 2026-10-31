package dev.locus.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.AgentHome.PersonaFile
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusLine
import dev.locus.core.designsystem.Tone

/** A few friendly questions; Locus writes the persona files from the answers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColumnScope.IdentityFields(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeading("Make it yours", "A few quick questions. Locus turns your answers into short notes your agent reads at the start of every chat.")
    if (!viewModel.personaLoaded) {
        StatusLine("Loading…", Tone.Neutral)
        return
    }
    val a = viewModel.answers
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    SectionLabel("Your agent")
    LocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            OutlinedTextField(
                a.agentName,
                { v -> viewModel.editAnswers { it.copy(agentName = v.take(40)) } },
                label = { Text("Its name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Question("How should it talk?")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TalkStyle.entries.forEach { s ->
                    Choice(s.label, selected = a.style == s) { viewModel.editAnswers { it.copy(style = s) } }
                }
            }
            Question("Before acting, it should…")
            Segmented(
                Autonomy.entries.map { it.label },
                selected = a.autonomy.ordinal,
                onSelect = { i -> viewModel.editAnswers { it.copy(autonomy = Autonomy.entries[i]) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Hint("${a.autonomy.summary} Your approval settings still apply on top.")
        }
    }

    SectionLabel("What do you want help with?")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        HelpArea.entries.forEach { area ->
            Choice(area.label, selected = area in a.areas) {
                viewModel.editAnswers { it.copy(areas = if (area in it.areas) it.areas - area else it.areas + area) }
            }
        }
    }

    SectionLabel("About you")
    LocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Field(a.userName, "Your name", words = true) { v -> viewModel.editAnswers { it.copy(userName = v) } }
            Field(a.callMe, "What should it call you?", words = true, placeholder = a.userName.substringBefore(' ').ifBlank { null }) { v -> viewModel.editAnswers { it.copy(callMe = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Field(a.timeZone, "Time zone", Modifier.weight(1f)) { v -> viewModel.editAnswers { it.copy(timeZone = v) } }
                Field(a.language, "Language", Modifier.weight(1f), words = true) { v -> viewModel.editAnswers { it.copy(language = v) } }
            }
            Field(a.location, "Where you are (city or country)", words = true) { v -> viewModel.editAnswers { it.copy(location = v) } }
        }
    }

    SectionLabel("Anything to always or never do?")
    LocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Rules(a.always, "Always (optional)", "e.g. Use metric units") { v -> viewModel.editAnswers { it.copy(always = v) } }
            Rules(a.never, "Never (optional)", "e.g. Email anyone without showing me the draft") { v -> viewModel.editAnswers { it.copy(never = v) } }
            Hint("One per line.")
        }
    }

    SectionLabel("Here's what ${a.agentName.trim().ifBlank { AgentHome.DEFAULT_NAME }} will remember", action = "Edit files", onAction = { editing = PersonaFile.IDENTITY.name })
    val override = viewModel.personaOverride
    if (override != null) {
        Banner(
            "This is edited text, so changes to the answers above won't show up here until you switch back.",
            Tone.Caution,
            actions = { TextButton(onClick = { viewModel.setPersonaText(null) }) { Text("Use answers") } },
        )
    }
    val preview = viewModel.personaPreview
    CardGroup {
        PersonaFile.entries.forEachIndexed { i, f ->
            if (i > 0) GroupDivider()
            ListRow(
                f.title(),
                subtitle = preview[f].readable().ifBlank { "Nothing yet" },
                subtitleMaxLines = 5,
                onClick = { editing = f.name },
                trailing = { Text(f.fileName, style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
    }
    if (viewModel.canPolish(state)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedButton(onClick = viewModel::polishPersona, enabled = !viewModel.personaPolish.running) {
                Icon(LocusIcons.Sparkle, null, Modifier.size(18.dp))
                Text("Write it with my model", Modifier.padding(start = Spacing.sm))
            }
        }
        JobStatus(viewModel.personaPolish, "Asking your model…")
    }
    Hint("Stored as plain files on this phone and sent to your model with each new chat. Keep secrets out of them.")
    JobStatus(viewModel.identitySave, "Saving…")

    editing?.let { name ->
        PersonaEditorSheet(
            initial = preview,
            start = PersonaFile.valueOf(name),
            onSave = { viewModel.setPersonaText(it); editing = null },
            onDismiss = { editing = null },
        )
    }
}

/** The optional raw editor: the four Markdown files as Locus will write them. */
@Composable
private fun PersonaEditorSheet(initial: PersonaFiles, start: PersonaFile, onSave: (PersonaFiles) -> Unit, onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(start.ordinal) }
    var identity by rememberSaveable { mutableStateOf(initial.identity) }
    var soul by rememberSaveable { mutableStateOf(initial.soul) }
    var user by rememberSaveable { mutableStateOf(initial.user) }
    var agents by rememberSaveable { mutableStateOf(initial.agents) }
    val files = PersonaFiles(identity, soul, user, agents)
    LocusSheet(
        title = "Edit files directly",
        subtitle = "Optional. Markdown your agent reads at the start of every chat.",
        onDismiss = onDismiss,
        action = "Done",
        onAction = { onSave(files) },
    ) {
        Segmented(PersonaFile.entries.map { it.title() }, selected = tab, onSelect = { tab = it }, compact = true, modifier = Modifier.fillMaxWidth())
        val file = PersonaFile.entries[tab]
        OutlinedTextField(
            files[file],
            { v ->
                when (file) {
                    PersonaFile.IDENTITY -> identity = v
                    PersonaFile.SOUL -> soul = v
                    PersonaFile.USER -> user = v
                    PersonaFile.AGENTS -> agents = v
                }
            },
            label = { Text(file.fileName) },
            textStyle = MonoCaption,
            minLines = 8,
            maxLines = 18,
            supportingText = {
                val n = files[file].length
                Text(if (n > AgentHome.PERSONA_BUDGET) "$n characters; only the first ${AgentHome.PERSONA_BUDGET} are used" else "$n / ${AgentHome.PERSONA_BUDGET}")
            },
            isError = files[file].length > AgentHome.PERSONA_BUDGET,
            modifier = Modifier.fillMaxWidth(),
        )
        if (file == PersonaFile.IDENTITY) Hint("Keep a “- Name: …” line: it's what the agent calls itself.")
    }
}

/** The questionnaire on its own, for Settings → Agent identity. */
@Composable
fun PersonaRoute(onBack: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { LocusTopBar("Agent identity", onBack = onBack) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(Spacing.lg)) {
                Button(
                    onClick = { viewModel.saveIdentity(onBack) },
                    enabled = viewModel.personaLoaded && !viewModel.identitySave.running,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text(if (viewModel.identitySave.running) "Saving…" else "Save") }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) { IdentityFields(state, viewModel) }
    }
}

// ---- Pieces --------------------------------------------------------------------------------------

private fun PersonaFile.title() = when (this) {
    PersonaFile.IDENTITY -> "Identity"
    PersonaFile.SOUL -> "Personality"
    PersonaFile.USER -> "About you"
    PersonaFile.AGENTS -> "Rules"
}

/** The file without Markdown headings and bullets, for the preview rows. */
private fun String.readable() = lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    .joinToString("\n") { it.removePrefix("- ").removePrefix("* ") }

@Composable
private fun Question(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(LocusIcons.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
    )
}

@Composable
private fun Field(
    value: String,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    words: Boolean = false,
    placeholder: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value,
        { onChange(it.take(80)) },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = if (words) KeyboardCapitalization.Words else KeyboardCapitalization.None,
            imeAction = ImeAction.Next,
        ),
        modifier = modifier,
    )
}

@Composable
private fun Rules(value: String, label: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        { onChange(it.take(1_000)) },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        minLines = 2,
        maxLines = 6,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
}
