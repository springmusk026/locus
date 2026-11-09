package dev.locus.feature.library

import dev.locus.core.designsystem.RowChevron
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.FileTypeIcon
import dev.locus.core.designsystem.SectionLabel
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.SkillImport
import dev.locus.core.agent.home.SkillManifestDiff
import dev.locus.core.agent.home.SkillReview
import dev.locus.core.data.repository.Library
import dev.locus.core.data.repository.SkillClash
import dev.locus.core.designsystem.AddRow
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ConnectorIcon
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.ExpandableRow
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone

/**
 * Library → Skills: active skills, drafts waiting for review, and Import (a GitHub link or a zip).
 * Imports always become drafts: the user reads SKILL.md and every file before approving.
 */
@Composable
internal fun SkillsTab(
    library: Library,
    viewModel: LibraryViewModel,
    modifier: Modifier,
    workflows: LibraryWorkflowsViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(),
    onOpenChat: (String) -> Unit = {},
) {
    var importing by rememberSaveable { mutableStateOf(false) }
    val status by viewModel.importStatus.collectAsStateWithLifecycle()
    val openFile by viewModel.openFile.collectAsStateWithLifecycle()
    val savedWorkflows by workflows.all.collectAsStateWithLifecycle()
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importSkillZip) }

    if (library.skills.isEmpty() && status == null && savedWorkflows.isEmpty()) {
        EmptyState(
            stringResource(R.string.library_skills_empty_title),
            stringResource(R.string.library_skills_empty_body),
            modifier,
            icon = LocusIcons.Library,
            action = { Button(onClick = { importing = true }) { Text(stringResource(R.string.library_skills_import)) } },
        )
    } else {
        LazyColumn(modifier.fillMaxSize(), contentPadding = listPadding) {
            status?.let { item { ImportBanner(it, viewModel) } }
            if (savedWorkflows.isNotEmpty()) {
                item {
                    SectionLabel(stringResource(R.string.library_skills_workflows_label))
                    SectionNote(stringResource(R.string.library_skills_workflows_note))
                }
                item { WorkflowsCard(workflows, onOpenChat) }
            }
            item {
                SectionLabel(stringResource(R.string.library_skills_label))
                SectionNote(stringResource(R.string.library_skills_note))
            }
            item { CardGroup {
                library.skills.forEachIndexed { i, skill ->
                    if (i > 0) GroupDivider(inset = Insets.iconRow)
                    val replacesActive = skill.draft && library.skills.any { !it.draft && it.name == skill.name }
                    SkillRow(skill, replacesActive, if (replacesActive) library.skillUpdates[skill.name] else null, viewModel)
                }
                if (library.skills.isNotEmpty()) GroupDivider(inset = Insets.iconRow)
                AddRow(stringResource(R.string.library_skills_import), LocusIcons.Import) { importing = true }
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
    (status as? SkillImportStatus.Review)?.let { ImportReviewSheet(it, viewModel) }
    openFile?.let { file ->
        LocusSheet(
            title = file.path,
            subtitle = stringResource(R.string.library_skills_file_subtitle, file.skill, SkillImport.mb(file.size.toLong())),
            onDismiss = viewModel::closeSkillFile,
        ) {
            if (file.text == null) {
                SupportingText(stringResource(R.string.library_skills_binary_file), style = LocusType.rowSupporting)
            } else {
                CodeBox(file.text, maxHeight = 480)
            }
        }
    }
}

private val ZIP_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/x-zip")

@Composable
private fun SkillRow(skill: AgentHome.Skill, replacesActive: Boolean, update: SkillManifestDiff?, viewModel: LibraryViewModel) {
    val source = skill.source
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmGrown by remember { mutableStateOf(false) }
    var confirmChanged by remember { mutableStateOf(false) }
    if (confirmChanged) {
        ConfirmApproveChanged(
            skill.name,
            stringResource(R.string.library_kind_skill),
            onDismiss = { confirmChanged = false },
            onConfirm = { confirmChanged = false; if (update?.grown == true) confirmGrown = true else viewModel.approveSkill(skill.name) },
        )
    }
    if (confirmGrown && update != null) {
        LocusSheet(
            title = stringResource(R.string.library_skills_grown_title),
            subtitle = stringResource(R.string.library_skills_grown_body, skill.name, update.lines().filter { it.startsWith("New") }.joinToString("\n") { "• $it" }),
            onDismiss = { confirmGrown = false },
            action = stringResource(R.string.library_action_approve),
            actionTone = Tone.Caution,
            onAction = { confirmGrown = false; viewModel.approveSkill(skill.name) },
        ) {}
    }
    if (confirmRemove) {
        ConfirmRemove(
            title = stringResource(R.string.library_skills_remove_title, skill.name),
            body = stringResource(R.string.library_skills_remove_body),
            action = stringResource(R.string.library_action_remove),
            onDismiss = { confirmRemove = false },
            onConfirm = { confirmRemove = false; viewModel.removeSkill(skill.name) },
        )
    }
    ExpandableRow(
        title = skill.name,
        subtitle = skill.description,
        leading = { SkillTile(skill) },
        badge = when {
            skill.changedOutside -> stringResource(R.string.library_changed_outside_badge)
            skill.draft -> stringResource(R.string.library_badge_needs_review)
            !skill.manifest.declared -> stringResource(R.string.library_skills_undeclared)
            else -> null
        },
        badgeTone = when {
            skill.changedOutside -> Tone.Critical
            skill.draft -> Tone.Caution
            else -> Tone.Neutral
        },
        meta = skill.manifest.version?.let { "v${it.removePrefix("v")}" },
        startExpanded = skill.draft,
    ) {
        if (skill.changedOutside) ChangedOutsideBanner(stringResource(R.string.library_kind_skill), approvedActive = replacesActive)
        SupportingText(manifestLine(skill))
        if (source != null) {
            SupportingText(stringResource(R.string.library_skills_imported_from, source.describe()))
        }
        skill.manifestProblem?.let {
            Banner(stringResource(R.string.library_skills_manifest_problem, it), Tone.Critical, title = stringResource(R.string.library_skills_manifest_problem_title))
        }
        if (skill.draft && skill.manifestInferred && skill.manifest.declared) {
            Banner(
                stringResource(R.string.library_skills_inferred_body),
                Tone.Caution,
                title = stringResource(R.string.library_skills_inferred_title),
            )
        }
        update?.let { UpdateDiff(it, current = stringResource(R.string.library_skills_current_active)) }
        ManifestSummary(SkillReview.of(skill))
        if (skill.draft && source != null) {
            Banner(
                stringResource(R.string.library_skills_review_before_body),
                Tone.Caution,
                title = stringResource(R.string.library_skills_review_before_title),
            )
        }
        if (replacesActive && !skill.changedOutside) {
            Text(
                stringResource(R.string.library_skills_replaces_active, skill.name),
                style = LocusType.supporting,
                color = Locus.colors.onCautionContainer,
            )
        }
        if (source != null && skill.raw.isNotEmpty()) {
            // Imported text is shown as written, so nothing (like an HTML comment) is hidden by rendering.
            Text("SKILL.md", style = LocusType.label)
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
                if (scripts > 0) pluralStringResource(R.plurals.library_skills_files_with_scripts, scripts, skill.files.size, scripts)
                else stringResource(R.string.library_skills_files, skill.files.size),
                style = LocusType.label,
            )
            Column {
                skill.files.forEach { path ->
                    // A file opens to read: its type icon, the path, a Script chip for code that runs, and a chevron.
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget).clickable(onClickLabel = stringResource(R.string.library_skills_read_file)) { viewModel.openSkillFile(skill, path) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        FileTypeIcon(path, isDirectory = false, size = IconSize.tileSmall)
                        Text(path, Modifier.weight(1f), style = LocusType.code)
                        if (isScript(path)) LocusChip(stringResource(R.string.library_skills_script_chip), tone = Tone.Caution)
                        RowChevron()
                    }
                }
            }
        }
        // Actions wrap to a second line at a large font instead of running off the card.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (skill.draft) {
                OutlinedButton(onClick = { viewModel.rejectSkill(skill.name) }) { Text(stringResource(if (skill.changedOutside) R.string.library_helpers_discard_change else R.string.library_action_reject)) }
                Button(
                    onClick = {
                        when {
                            skill.changedOutside -> confirmChanged = true
                            update?.grown == true -> confirmGrown = true
                            else -> viewModel.approveSkill(skill.name)
                        }
                    },
                ) { Text(stringResource(R.string.library_action_approve)) }
            } else {
                TextButton(onClick = { confirmRemove = true }) { Text(stringResource(R.string.library_action_remove), color = Locus.colors.critical) }
            }
        }
    }
}

/**
 * A skill's leading tile: GitHub's logo for one imported from GitHub (the same tile connectors use),
 * an import mark for one from a zip, the skills mark for one Locus or the user wrote.
 */
@Composable
private fun SkillTile(skill: AgentHome.Skill) {
    when (skill.source?.kind) {
        "github" -> ConnectorIcon("github", contentDescription = stringResource(R.string.library_skills_icon_github))
        null -> IconTile(LocusIcons.Library, contentDescription = stringResource(R.string.library_skills_icon_skill))
        else -> IconTile(LocusIcons.Import, contentDescription = stringResource(R.string.library_skills_icon_zip))
    }
}

private val SCRIPT_EXTENSIONS = setOf("sh", "bash", "zsh", "py", "js", "mjs", "cjs", "ts", "rb", "pl", "php", "lua", "ps1", "bat", "cmd", "exe", "bin", "jar")

private fun isScript(path: String) = path.startsWith("scripts/") || path.substringAfterLast('.', "").lowercase() in SCRIPT_EXTENSIONS

@Composable
private fun CodeBox(text: String, maxHeight: Int) {
    Box(
        Modifier.fillMaxWidth().heightIn(max = maxHeight.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.md),
    ) {
        SelectionContainer { Text(text, style = LocusType.code) }
    }
}

@Composable
private fun ImportBanner(status: SkillImportStatus, viewModel: LibraryViewModel) {
    val modifier = Modifier.padding(bottom = Spacing.md)
    when (status) {
        is SkillImportStatus.Working -> Banner(stringResource(R.string.library_skills_importing_body, status.label), Tone.Live, modifier, title = stringResource(R.string.library_skills_importing_title)) {
            TextButton(onClick = viewModel::cancelImport) { Text(stringResource(R.string.library_action_cancel)) }
        }
        is SkillImportStatus.Failed -> Banner(status.message, Tone.Critical, modifier, title = stringResource(R.string.library_skills_import_failed_title)) {
            TextButton(onClick = viewModel::cancelImport) { Text(stringResource(R.string.library_action_ok)) }
        }
        is SkillImportStatus.Imported -> Banner(
            stringResource(R.string.library_skills_imported_body, status.name),
            Tone.Brand,
            modifier,
            title = stringResource(R.string.library_skills_imported_title),
        ) { TextButton(onClick = viewModel::cancelImport) { Text(stringResource(R.string.library_action_ok)) } }
        is SkillImportStatus.Review -> Banner(stringResource(R.string.library_skills_review_waiting_body, status.bundle.name), Tone.Caution, modifier, title = stringResource(R.string.library_skills_review_waiting_title)) {
            TextButton(onClick = viewModel::cancelImport) { Text(stringResource(R.string.library_action_cancel)) }
        }
    }
}

@Composable
private fun ImportSheet(onDismiss: () -> Unit, onGitHub: (String) -> Unit, onZip: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("https://github.com/") }
    LocusSheet(
        title = stringResource(R.string.library_skills_import),
        subtitle = stringResource(R.string.library_skills_import_sheet_subtitle),
        onDismiss = onDismiss,
        action = stringResource(R.string.library_skills_import_action),
        actionEnabled = url.trim().let { it.contains("github.com/") && it.substringAfter("github.com/").count { c -> c == '/' } >= 1 },
        onAction = { onGitHub(url.trim()) },
    ) {
        OutlinedTextField(
            url,
            { url = it.trim() },
            label = { Text(stringResource(R.string.library_skills_import_link_label)) },
            supportingText = { Text(stringResource(R.string.library_skills_import_link_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = onZip, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.library_skills_import_zip)) }
    }
}

/**
 * The import review: what the skill declares it needs, where it came from, its version and hash, and,
 * when its name is taken, how it changes the skill there. A replacement that asks for more must be
 * accepted first (like Chrome's permission prompt on an extension update).
 */
@Composable
private fun ImportReviewSheet(status: SkillImportStatus.Review, viewModel: LibraryViewModel) {
    val original = status.bundle.name
    val review = status.review
    val clash = status.clash
    val diff = review.diff
    var name by remember(original) { mutableStateOf(suggestName(original, viewModel)) }
    var accepted by remember(original) { mutableStateOf(false) }
    val problem = viewModel.renameProblem(name)
    LocusSheet(
        title = stringResource(if (clash == SkillClash.None) R.string.library_skills_review_title else R.string.library_skills_review_exists_title, original),
        subtitle = stringResource(
            when (clash) {
                SkillClash.None -> R.string.library_skills_review_subtitle_none
                SkillClash.Active -> R.string.library_skills_review_subtitle_active
                SkillClash.Draft -> R.string.library_skills_review_subtitle_draft
            },
        ),
        onDismiss = viewModel::cancelImport,
        action = stringResource(if (clash == SkillClash.None) R.string.library_skills_review_save_draft else R.string.library_skills_review_save_new_name),
        actionEnabled = clash == SkillClash.None || problem == null,
        onAction = { if (clash == SkillClash.None) viewModel.saveImport() else viewModel.renameImport(name) },
    ) {
        if (diff != null) UpdateDiff(diff, current = stringResource(if (clash == SkillClash.Active) R.string.library_skills_current_yours else R.string.library_skills_current_draft))
        ManifestSummary(review)
        if (clash != SkillClash.None) {
            OutlinedTextField(
                name,
                { name = it.trim().lowercase() },
                label = { Text(stringResource(R.string.library_skills_review_new_name)) },
                isError = problem != null,
                supportingText = problem?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val grown = diff?.grown == true
            if (grown) {
                Row(Modifier.fillMaxWidth().clickable { accepted = !accepted }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                    Text(stringResource(R.string.library_skills_review_accept), style = LocusType.rowSupporting)
                }
            }
            // Actions wrap to a second line at a large font instead of running off the card.
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                TextButton(onClick = { viewModel.saveImport(acceptedNewPermissions = accepted) }, enabled = !grown || accepted) {
                    Text(stringResource(if (clash == SkillClash.Active) R.string.library_skills_review_replace_yours else R.string.library_skills_review_replace_draft), color = if (!grown || accepted) Locus.colors.onCautionContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** What changes against the skill of the same name: new permissions, a changed copy, a new source. */
@Composable
internal fun UpdateDiff(diff: SkillManifestDiff, current: String) {
    when {
        diff.grown -> Banner(diff.lines().joinToString("\n"), Tone.Critical, title = stringResource(R.string.library_skills_diff_more, current))
        diff.shrunk -> Banner(diff.lines().joinToString("\n"), Tone.Brand, title = stringResource(R.string.library_skills_diff_less, current))
        diff.same -> Banner(stringResource(R.string.library_skills_diff_same, current), Tone.Neutral, title = stringResource(R.string.library_skills_diff_no_changes))
    }
    if (diff.changedWithoutVersionBump) {
        Banner(
            stringResource(R.string.library_skills_diff_no_bump_body, current, diff.versionAfter ?: stringResource(R.string.library_skills_version_none)),
            Tone.Caution,
            title = stringResource(R.string.library_skills_diff_no_bump_title),
        )
    } else if (diff.versionBefore != diff.versionAfter) {
        SupportingText(stringResource(R.string.library_skills_diff_version, diff.versionBefore ?: stringResource(R.string.library_skills_version_none), diff.versionAfter ?: stringResource(R.string.library_skills_version_none)))
    }
    if (diff.sourceChanged) {
        Text(stringResource(R.string.library_skills_diff_source, current), style = LocusType.supporting, color = Locus.colors.onCautionContainer)
    }
}

/** A manifest in plain words: what it can do, which hosts, what it installs, and where it's from. */
@Composable
private fun ManifestSummary(review: SkillReview) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (!review.declared) {
            Banner(SkillReview.UNDECLARED, Tone.Caution, title = stringResource(R.string.library_skills_undeclared))
        } else {
            Text(stringResource(R.string.library_skills_can_do), style = LocusType.label)
            val can = review.capabilities + review.network
            if (can.isEmpty()) Bullet(stringResource(R.string.library_skills_nothing_beyond)) else can.forEach { Bullet(it) }
            SupportingText(stringResource(R.string.library_skills_else_asks))
        }
        if (review.dependencies.isNotEmpty()) {
            Text(stringResource(R.string.library_skills_installs), style = LocusType.label)
            review.dependencies.forEach { Bullet(it) }
        }
        review.notes.filter { it != SkillReview.UNDECLARED }.forEach {
            Text(it, style = LocusType.supporting, color = Locus.colors.onCautionContainer)
        }
        Text(stringResource(R.string.library_skills_from), style = LocusType.label)
        review.source?.let { Detail(stringResource(R.string.library_skills_detail_imported_from), it) }
        review.declaredSource?.let { Detail(stringResource(R.string.library_skills_detail_published_at), it) }
        Detail(stringResource(R.string.library_skills_detail_version), review.version ?: stringResource(R.string.library_skills_version_none_given))
        if (review.hash.isNotEmpty()) Detail(stringResource(if (review.hashDeclared) R.string.library_skills_detail_hash_pinned else R.string.library_skills_detail_hash), review.hash)
    }
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text("•  ", style = LocusType.rowSupporting)
        Text(text, style = LocusType.rowSupporting)
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column {
        SupportingText(label)
        SelectionContainer { Text(value, style = if (value.startsWith("sha256:")) LocusType.code else LocusType.rowSupporting) }
    }
}

/** A skill's manifest in one line for Library's list, e.g. "Declares: files.read, linux · 2 hosts". */
@Composable
private fun manifestLine(skill: AgentHome.Skill): String {
    val m = skill.manifest
    if (!m.declared) return stringResource(R.string.library_skills_manifest_undeclared)
    val parts = buildList {
        add(if (m.capabilities.isEmpty()) stringResource(R.string.library_skills_manifest_no_tools) else m.capabilities.joinToString())
        if (m.network.isNotEmpty()) add(if (m.network.size == 1) m.network.single() else pluralStringResource(R.plurals.library_skills_manifest_hosts, m.network.size, m.network.size))
        if (!m.dependencies.isEmpty) add(pluralStringResource(R.plurals.library_skills_manifest_dependencies, m.dependencies.all().size, m.dependencies.all().size))
    }
    return stringResource(R.string.library_skills_manifest_declares, parts.joinToString(" · "))
}

/** "pdf-tools" → the first free of "pdf-tools-2", "pdf-tools-3", … */
private fun suggestName(name: String, viewModel: LibraryViewModel): String =
    (2..99).map { "${name.take(60)}-$it" }.firstOrNull { viewModel.renameProblem(it) == null } ?: name
