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

    SettingsPageScaffold("Saved logins", onBack) {
        state.problem?.let { problem -> item { Banner(problem, Tone.Critical, NotePadding) } }
        if (state.problem == null && !state.loading) {
            item { SectionLabel("Logins", SectionPadding) }
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
                    AddRow("Add a login") { editing = SiteLogin(UUID.randomUUID().toString(), "", "", "") }
                }
            }
            item {
                SectionNote(
                    "Locus fills a saved login only on its exact site, and only after you approve it (or chose Always for that site). " +
                        "The AI never sees the password or the one-time codes, and signing in still asks you. Logins are encrypted on this phone; " +
                        "a backup includes them only when you include saved keys.",
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
internal fun loginSubtitle(login: SiteLogin): String = listOfNotNull(
    login.username.ifBlank { null },
    SiteOrigin.host(login.origin).takeIf { login.label.isNotBlank() },
    "codes".takeIf { login.totpSecret != null },
).joinToString(" · ").ifEmpty { SiteOrigin.host(login.origin) }

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
        title = if (isNew) "Add a login" else initial.title,
        subtitle = "Locus fills it only on this exact site, never on its subdomains or look-alikes.",
        onDismiss = onDone,
        action = if (saving) "Saving…" else "Save",
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
            label = { Text("Site address") },
            placeholder = { Text("https://github.com") },
            supportingText = origin?.takeIf { it != site.trim() }?.let { { Text("Saved as $it") } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            username, { username = it },
            label = { Text("Username or email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(
            password, { password = it; error = null }, "Password",
            supportingText = if (isNew) null else "Saved. Leave blank to keep it.",
        )
        PasswordField(
            totp, { totp = it; removeTotp = false; error = null }, "Authenticator key (optional)",
            supportingText = when {
                removeTotp -> "Will be removed when you save."
                initial.totpSecret != null -> "Saved. Leave blank to keep it."
                else -> "For sites that ask for a code from an authenticator app. Locus then makes the codes itself, so this phone holds both steps of the sign-in."
            },
            imeAction = ImeAction.Next,
        )
        if (initial.totpSecret != null && !removeTotp && totp.isEmpty()) {
            TextButton(onClick = { removeTotp = true }) { Text("Remove authenticator key") }
        }
        OutlinedTextField(
            label, { label = it },
            label = { Text("Name (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = Locus.colors.critical, style = LocusType.supporting) }
        if (!isNew) {
            if (confirmDelete) {
                Banner("Delete the login for ${initial.title}? Locus can't sign in there for you any more.", Tone.Critical, title = "Delete login") {
                    TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                    TextButton(onClick = onDelete) { Text("Delete", color = Locus.colors.critical) }
                }
            } else {
                CriticalOutlinedButton("Delete login", onClick = { confirmDelete = true })
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
