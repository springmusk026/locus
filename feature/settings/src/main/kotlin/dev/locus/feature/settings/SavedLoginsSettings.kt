package dev.locus.feature.settings

import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.logins.SiteLoginStore
import dev.locus.core.designsystem.AddRow
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.CriticalOutlinedButton
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.PasswordField
import dev.locus.core.designsystem.RowChevron
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.SiteLogin
import dev.locus.core.model.SiteOrigin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** What the Saved logins page shows. Secrets are in [logins] only to be edited; the list never shows them. */
internal data class SavedLoginsState(
    val loading: Boolean = true,
    val logins: List<SiteLogin> = emptyList(),
    /** Why the logins can't be read (the vault is locked or unusable); null when they can. */
    val problem: String? = null,
)

@HiltViewModel
internal class SavedLoginsViewModel @Inject constructor(private val store: SiteLoginStore) : ViewModel() {
    private val _state = MutableStateFlow(SavedLoginsState())
    val state: StateFlow<SavedLoginsState> = _state.asStateFlow()

    init {
        viewModelScope.launch { store.changes.collect { reload() } }
    }

    private suspend fun reload() {
        _state.value = runCatching { SavedLoginsState(loading = false, logins = store.all()) }
            .getOrElse { SavedLoginsState(loading = false, problem = "Saved logins can't be opened right now: ${it.message ?: "the vault is locked"}.") }
    }

    /** Saves [login]; [onDone] gets null, or what to fix. */
    fun save(login: SiteLogin, onDone: (String?) -> Unit) = viewModelScope.launch {
        onDone(runCatching { store.upsert(login) }.exceptionOrNull()?.let { it.message ?: "Couldn't save the login." })
    }

    fun delete(id: String) = viewModelScope.launch { runCatching { store.delete(id) } }
}

@Composable
internal fun SavedLoginsRoute(onBack: () -> Unit, viewModel: SavedLoginsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SavedLoginsSettings(state, onBack, onSave = viewModel::save, onDelete = viewModel::delete)
}

/**
 * Settings → Saved logins (docs/design/browser.md, "Saved logins"): the sites Locus can sign in to for
 * the user. The whole page is marked sensitive (Android 15+), so screen sharing and recording hide it.
 */
@Composable
internal fun SavedLoginsSettings(
    state: SavedLoginsState,
    onBack: () -> Unit,
    onSave: (SiteLogin, (String?) -> Unit) -> Unit,
    onDelete: (String) -> Unit,
    /** The login open in the editor at first; for screenshots. */
    initialEditing: SiteLogin? = null,
) {
    MarkSensitive()
    var editing by remember { mutableStateOf(initialEditing) }

    SettingsPageScaffold(stringResource(R.string.settings_logins_title), onBack) {
        state.problem?.let { problem -> item { Banner(problem, Tone.Critical, NotePadding) } }
        if (state.problem == null && !state.loading) {
            item { SectionLabel(stringResource(R.string.settings_logins_section), SectionPadding) }
            item {
                CardGroup {
                    state.logins.forEachIndexed { i, login ->
                        if (i > 0) GroupDivider(inset = Insets.iconRow)
                        ListRow(
                            title = login.title,
                            subtitle = loginSubtitle(login),
                            onClick = { editing = login },
                            leading = { IconTile(LocusIcons.Key) },
                            trailing = { RowChevron() },
                        )
                    }
                    if (state.logins.isNotEmpty()) GroupDivider(inset = Insets.iconRow)
                    AddRow(stringResource(R.string.settings_logins_add)) { editing = SiteLogin(UUID.randomUUID().toString(), "", "", "") }
                }
            }
            item {
                SectionNote(
                    stringResource(R.string.settings_logins_note),
                    NotePadding,
                )
            }
        }
    }

    editing?.let { login ->
        LoginEditor(
            initial = login,
            isNew = state.logins.none { it.id == login.id },
            onSave = onSave,
            onDelete = { onDelete(login.id); editing = null },
            onDone = { editing = null },
        )
    }
}

/** "alice · github.com · codes": who, where, and whether Locus makes the one-time codes. */
@Composable
internal fun loginSubtitle(login: SiteLogin): String = listOfNotNull(
    login.username.ifBlank { null },
    SiteOrigin.host(login.origin).takeIf { login.label.isNotBlank() },
    stringResource(R.string.settings_logins_codes).takeIf { login.totpSecret != null },
).let { parts -> if (parts.isEmpty()) SiteOrigin.host(login.origin) else summaryJoin(parts) }

@Composable
private fun LoginEditor(
    initial: SiteLogin,
    isNew: Boolean,
    onSave: (SiteLogin, (String?) -> Unit) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    var site by rememberSaveable { mutableStateOf(initial.origin) }
    var username by rememberSaveable { mutableStateOf(initial.username) }
    // Blank while editing a saved one: the saved password stays unless something is typed.
    var password by rememberSaveable { mutableStateOf("") }
    var totp by rememberSaveable { mutableStateOf("") }
    var removeTotp by rememberSaveable { mutableStateOf(false) }
    var label by rememberSaveable { mutableStateOf(initial.label) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val origin = SiteOrigin.of(site.trim().let { if ("://" in it || it.isEmpty()) it else "https://$it" })

    LocusSheet(
        title = if (isNew) stringResource(R.string.settings_logins_add) else initial.title,
        subtitle = stringResource(R.string.settings_logins_editor_subtitle),
        onDismiss = onDone,
        action = stringResource(if (saving) R.string.settings_logins_saving else R.string.settings_logins_save),
        actionEnabled = !saving && origin != null && (password.isNotEmpty() || !isNew),
        onAction = {
            saving = true
            error = null
            val saved = initial.copy(
                origin = origin.orEmpty(),
                username = username,
                password = password.ifEmpty { initial.password },
                totpSecret = when {
                    removeTotp -> null
                    totp.isNotBlank() -> totp
                    else -> initial.totpSecret
                },
                label = label,
                createdAt = if (isNew) System.currentTimeMillis() else initial.createdAt,
            )
            onSave(saved) { problem ->
                saving = false
                if (problem == null) onDone() else error = problem
            }
        },
    ) {
        OutlinedTextField(
            site, { site = it.trim(); error = null },
            label = { Text(stringResource(R.string.settings_logins_site)) },
            placeholder = { Text(stringResource(R.string.settings_logins_site_placeholder)) },
            supportingText = origin?.takeIf { it != site.trim() }?.let { { Text(stringResource(R.string.settings_logins_saved_as, it)) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            username, { username = it },
            label = { Text(stringResource(R.string.settings_logins_username)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(
            password, { password = it; error = null }, stringResource(R.string.settings_logins_password),
            supportingText = if (isNew) null else stringResource(R.string.settings_logins_keep_saved),
        )
        PasswordField(
            totp, { totp = it; removeTotp = false; error = null }, stringResource(R.string.settings_logins_totp),
            supportingText = stringResource(
                when {
                    removeTotp -> R.string.settings_logins_totp_removed
                    initial.totpSecret != null -> R.string.settings_logins_keep_saved
                    else -> R.string.settings_logins_totp_hint
                },
            ),
            imeAction = ImeAction.Next,
        )
        if (initial.totpSecret != null && !removeTotp && totp.isEmpty()) {
            TextButton(onClick = { removeTotp = true }) { Text(stringResource(R.string.settings_logins_totp_remove)) }
        }
        OutlinedTextField(
            label, { label = it },
            label = { Text(stringResource(R.string.settings_logins_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = Locus.colors.critical, style = LocusType.supporting) }
        if (!isNew) {
            if (confirmDelete) {
                Banner(stringResource(R.string.settings_logins_delete_message, initial.title), Tone.Critical, title = stringResource(R.string.settings_logins_delete_title)) {
                    TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.settings_logins_delete_cancel)) }
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.settings_logins_delete_confirm), color = Locus.colors.critical) }
                }
            } else {
                CriticalOutlinedButton(stringResource(R.string.settings_logins_delete_title), onClick = { confirmDelete = true })
            }
        }
    }
}

/** Marks the window's content sensitive while this page shows (Android 15+): screen sharing and recording hide it. */
@Composable
private fun MarkSensitive() {
    val view = LocalView.current
    DisposableEffect(view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val before = view.contentSensitivity
            view.contentSensitivity = View.CONTENT_SENSITIVITY_SENSITIVE
            onDispose { view.contentSensitivity = before }
        } else {
            onDispose { }
        }
    }
}
