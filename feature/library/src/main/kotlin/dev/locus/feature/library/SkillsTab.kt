package dev.locus.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.SkillImport
import dev.locus.core.data.repository.Library
import dev.locus.core.data.repository.SkillClash
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.CodeTextStyle
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Tone

/**
 * Library → Skills: active skills, drafts waiting for review, and Import (a GitHub link or a zip).
 * Imports always become drafts: the user reads SKILL.md and every file before approving.
 */
@Composable
internal fun SkillsTab(library: Library, viewModel: LibraryViewModel, modifier: Modifier) {
    var importing by rememberSaveable { mutableStateOf(false) }
    val status by viewModel.importStatus.collectAsStateWithLifecycle()
    val openFile by viewModel.openFile.collectAsStateWithLifecycle()
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importSkillZip) }

    if (library.skills.isEmpty() && status == null) {
        EmptyState(
            "No skills yet",
            "Skills are reusable how-tos. Ask Locus to “save that as a skill” after a task, or import one from GitHub or a zip. Either way you review it here first.",
            modifier,
            icon = LocusIcons.Library,
            action = { Button(onClick = { importing = true }) { Text("Import a skill") } },
        )
    } else {
        LazyColumn(modifier.fillMaxSize(), contentPadding = listPadding) {
            status?.let { item { ImportBanner(it, viewModel) } }
            item { SectionNote("Reusable how-tos Locus follows. Ones it drafts or you import wait here for your review.", Modifier.padding(start = Spacing.xs)) }
            item { CardGroup {
                library.skills.forEachIndexed { i, skill ->
                    if (i > 0) GroupDivider(inset = ROW_INSET)
                    SkillRow(skill, replacesActive = skill.draft && library.skills.any { !it.draft && it.name == skill.name }, viewModel)
                }
                if (library.skills.isNotEmpty()) GroupDivider(inset = ROW_INSET)
                AddRow("Import a skill", LocusIcons.Import) { importing = true }
            } }
        }
    }

    if (importing) {
        ImportSheet(
            onDismiss = { importing = false },
            onGitHub = { importing = false; viewModel.importSkillFromGitHub(it) },
            onZip = { importing = false; pickZip.launch(ZIP_TYPES) },
        )
    }
    (status as? SkillImportStatus.Clash)?.let { ClashSheet(it, viewModel) }
    openFile?.let { file ->
        LocusSheet(
            title = file.path,
            subtitle = "${file.skill} · ${SkillImport.mb(file.size.toLong())}",
            onDismiss = viewModel::closeSkillFile,
        ) {
            if (file.text == null) {
                Text("A binary file, not shown here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                CodeBox(file.text, maxHeight = 480)
            }
        }
    }
}

private val ZIP_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/x-zip")

@Composable
private fun SkillRow(skill: AgentHome.Skill, replacesActive: Boolean, viewModel: LibraryViewModel) {
    val source = skill.source
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove) {
        ConfirmRemove(
            title = "Remove “${skill.name}”?",
            body = "Locus stops using this skill. Its files are deleted from this phone.",
            action = "Remove",
            onDismiss = { confirmRemove = false },
            onConfirm = { confirmRemove = false; viewModel.removeSkill(skill.name) },
        )
    }
    ExpandableRow(
        title = skill.name,
        subtitle = skill.description,
        icon = LocusIcons.Library,
        badge = if (skill.draft) "Needs review" else null,
        startExpanded = skill.draft,
    ) {
        if (source != null) {
            Text("Imported from ${source.describe()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (skill.draft && source != null) {
            Banner(
                "Someone else wrote this skill. Read SKILL.md and every file below before approving: Locus follows these instructions when it uses the skill, " +
                    "and any scripts in it run in the Linux sandbox.",
                Tone.Caution,
                title = "Review before approving",
            )
        }
        if (replacesActive) {
            Text(
                "Approving replaces your active “${skill.name}”. The current version is kept in history.",
                style = MaterialTheme.typography.bodySmall,
                color = Locus.colors.caution,
            )
        }
        if (source != null && skill.raw.isNotEmpty()) {
            // Imported text is shown as written, so nothing (like an HTML comment) is hidden by rendering.
            Text("SKILL.md", style = MaterialTheme.typography.labelLarge)
            CodeBox(skill.raw, maxHeight = 360)
        } else {
            Markdown(
                content = skill.body,
                modifier = Modifier.fillMaxWidth().codeWell(),
            )
        }
        if (skill.files.isNotEmpty()) {
            val scripts = skill.files.count(::isScript)
            Text(
                "Files (${skill.files.size}${if (scripts > 0) ", $scripts ${if (scripts == 1) "script" else "scripts"}" else ""}). Tap one to read it.",
                style = MaterialTheme.typography.labelLarge,
            )
            Column {
                skill.files.forEach { path ->
                    Row(
                        Modifier.fillMaxWidth().clickable { viewModel.openSkillFile(skill, path) }.padding(vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(path, Modifier.weight(1f), style = CodeTextStyle, color = if (isScript(path)) Locus.colors.caution else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
            if (skill.draft) {
                OutlinedButton(onClick = { viewModel.rejectSkill(skill.name) }) { Text("Reject") }
                Button(onClick = { viewModel.approveSkill(skill.name) }) { Text("Approve") }
            } else {
                TextButton(onClick = { confirmRemove = true }) { Text("Remove", color = Locus.colors.critical) }
            }
        }
    }
}

private val SCRIPT_EXTENSIONS = setOf("sh", "bash", "zsh", "py", "js", "mjs", "cjs", "ts", "rb", "pl", "php", "lua", "ps1", "bat", "cmd", "exe", "bin", "jar")

private fun isScript(path: String) = path.startsWith("scripts/") || path.substringAfterLast('.', "").lowercase() in SCRIPT_EXTENSIONS

@Composable
private fun CodeBox(text: String, maxHeight: Int) {
    Box(
        Modifier.fillMaxWidth().heightIn(max = maxHeight.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Radius.control))
            .verticalScroll(rememberScrollState())
            .padding(Spacing.md),
    ) {
        SelectionContainer { Text(text, style = CodeTextStyle) }
    }
}

@Composable
private fun ImportBanner(status: SkillImportStatus, viewModel: LibraryViewModel) {
    val modifier = Modifier.padding(bottom = Spacing.md)
    when (status) {
        is SkillImportStatus.Working -> Banner("${status.label}…", Tone.Live, modifier, title = "Importing") {
            TextButton(onClick = viewModel::cancelImport) { Text("Cancel") }
        }
        is SkillImportStatus.Failed -> Banner(status.message, Tone.Critical, modifier, title = "Couldn't import") {
            TextButton(onClick = viewModel::cancelImport) { Text("OK") }
        }
        is SkillImportStatus.Imported -> Banner(
            "“${status.name}” is a draft below. Nothing in it is used until you approve it.",
            Tone.Brand,
            modifier,
            title = "Imported for review",
        ) { TextButton(onClick = viewModel::cancelImport) { Text("OK") } }
        is SkillImportStatus.Clash -> Banner("A skill named “${status.bundle.name}” already exists.", Tone.Caution, modifier, title = "Name taken")
    }
}

@Composable
private fun ImportSheet(onDismiss: () -> Unit, onGitHub: (String) -> Unit, onZip: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("https://github.com/") }
    LocusSheet(
        title = "Import a skill",
        subtitle = "An Agent Skill is a folder with a SKILL.md. It's saved as a draft for you to review; nothing runs until you approve it.",
        onDismiss = onDismiss,
        action = "Import",
        actionEnabled = url.trim().let { it.contains("github.com/") && it.substringAfter("github.com/").count { c -> c == '/' } >= 1 },
        onAction = { onGitHub(url.trim()) },
    ) {
        OutlinedTextField(
            url,
            { url = it.trim() },
            label = { Text("GitHub link") },
            supportingText = { Text("A skill folder (…/tree/main/skills/name) or a repository with SKILL.md at the top. Uses your GitHub token from Integrations if you added one.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = onZip, modifier = Modifier.fillMaxWidth()) { Text("Choose a zip file instead") }
    }
}

/** The imported skill's name is taken: import it under a new name, or replace the one there. */
@Composable
private fun ClashSheet(clash: SkillImportStatus.Clash, viewModel: LibraryViewModel) {
    val original = clash.bundle.name
    var name by remember(original) { mutableStateOf(suggestName(original, viewModel)) }
    val problem = viewModel.renameProblem(name)
    LocusSheet(
        title = "“$original” already exists",
        subtitle = if (clash.with == SkillClash.Active) {
            "Import it under a new name, or replace yours. Replacing makes this a new version once you approve it; the current one is kept in history."
        } else {
            "A draft with this name is waiting for review. Import under a new name, or replace that draft."
        },
        onDismiss = viewModel::cancelImport,
        action = "Rename",
        actionEnabled = problem == null,
        onAction = { viewModel.renameImport(name) },
    ) {
        OutlinedTextField(
            name,
            { name = it.trim().lowercase() },
            label = { Text("New name") },
            isError = problem != null,
            supportingText = problem?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = viewModel::replaceImport) { Text(if (clash.with == SkillClash.Active) "Replace yours" else "Replace the draft", color = Locus.colors.caution) }
        }
    }
}

/** "pdf-tools" → the first free of "pdf-tools-2", "pdf-tools-3", … */
private fun suggestName(name: String, viewModel: LibraryViewModel): String =
    (2..99).map { "${name.take(60)}-$it" }.firstOrNull { viewModel.renameProblem(it) == null } ?: name
