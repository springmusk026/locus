package dev.locus.feature.settings

import androidx.compose.ui.res.stringResource
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.model.SettingsDestination
import dev.locus.core.designsystem.SettingsLink
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.network.NetworkSettingsRepository
import dev.locus.core.data.network.SavedNetworkSecrets
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CriticalOutlinedButton
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.NetworkDefaults
import dev.locus.core.model.ProxyConfig
import dev.locus.core.model.ProxyType
import dev.locus.core.model.TrustedCertificate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.AddRow
import dev.locus.core.designsystem.RowChevron
import android.content.res.Resources
import androidx.compose.ui.platform.LocalResources
import dev.locus.core.designsystem.UiText
import dev.locus.core.designsystem.asString

data class NetworkUiState(
    val defaults: NetworkDefaults = NetworkDefaults(),
    val saved: SavedNetworkSecrets = SavedNetworkSecrets(),
    /** Provider names that tick each certificate, by certificate id. */
    val usedBy: Map<String, List<String>> = emptyMap(),
    /** The last import's outcome: a message and whether it failed. */
    val message: Pair<UiText, Boolean>? = null,
)

@HiltViewModel
class NetworkViewModel @Inject constructor(private val network: NetworkSettingsRepository) : ViewModel() {
    private val message = MutableStateFlow<Pair<UiText, Boolean>?>(null)

    val state: StateFlow<NetworkUiState> = combine(network.defaults, network.saved, network.providersByCertificate, message) { d, s, used, m ->
        NetworkUiState(d, s, used, m)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkUiState())

    /** [credentials]: null keeps the saved ones. */
    fun setDefaultProxy(proxy: ProxyConfig?, credentials: Pair<String, String>?) = viewModelScope.launch { network.setDefaultProxy(proxy, credentials) }

    fun import(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            message.value = try {
                when (val n = network.importCertificates(uri)) {
                    0 -> UiText.Res(R.string.settings_network_import_already) to false
                    1 -> UiText.Res(R.string.settings_network_import_one) to false
                    else -> UiText.Plural(R.plurals.settings_network_import_many, n) to false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                (e.message?.let(UiText::Plain) ?: UiText.Res(R.string.settings_network_import_read_failed)) to true
            }
        }
    }

    fun dismissMessage() {
        message.value = null
    }

    fun setTrustedByAll(id: String, all: Boolean) = viewModelScope.launch { network.setTrustedByAll(id, all) }

    fun remove(id: String) = viewModelScope.launch { network.removeCertificate(id) }
}

@Composable
internal fun NetworkRoute(onBack: () -> Unit, viewModel: NetworkViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { viewModel.import(it) }
    NetworkSettings(
        state,
        onBack = onBack,
        // Any file: .pem and .crt files are often labelled octet-stream or text; the import checks the contents.
        onImport = { pick.launch(arrayOf("*/*")) },
        onDismissMessage = viewModel::dismissMessage,
        onSetProxy = viewModel::setDefaultProxy,
        onSetTrustedByAll = viewModel::setTrustedByAll,
        onRemove = viewModel::remove,
    )
}

@Composable
internal fun NetworkSettings(
    state: NetworkUiState,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onDismissMessage: () -> Unit,
    onSetProxy: (ProxyConfig?, Pair<String, String>?) -> Unit,
    onSetTrustedByAll: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
) {
    var editingProxy by remember { mutableStateOf(false) }
    var openCert by remember { mutableStateOf<String?>(null) }
    val proxy = state.defaults.proxy

    SettingsPageScaffold(stringResource(R.string.settings_network_title), onBack) {
        item { SectionLabel(stringResource(R.string.settings_network_proxy_section), SectionPadding) }
        item {
            CardGroup {
                ListRow(
                    title = proxy?.let { stringResource(R.string.settings_network_proxy_address, it.type.label(), it.host, it.port) } ?: stringResource(R.string.settings_network_no_proxy),
                    subtitle = proxy?.let { stringResource(if (it.hasCredentials) R.string.settings_network_with_sign_in else R.string.settings_network_no_sign_in) }
                        ?: stringResource(R.string.settings_network_direct),
                    onClick = { editingProxy = true },
                    leading = { IconTile(LocusIcons.Server) },
                    trailing = { RowChevron() },
                )
            }
        }
        item {
            SectionNote(
                stringResource(R.string.settings_network_proxy_note),
                NotePadding,
            )
        }
        // The link's text lines up with the note above (its button has an xs inset of its own).
        item { SettingsLink(SettingsDestination.Models, Modifier.padding(start = Spacing.md)) }

        item { SectionLabel(stringResource(R.string.settings_network_certificates_section), SectionPadding) }
        state.message?.let { (message, failed) ->
            item {
                val text = message.asString()
                Banner(
                    if (failed) stringResource(R.string.settings_network_import_failed_hint, text) else text,
                    if (failed) Tone.Critical else Tone.Brand,
                    modifier = Modifier.padding(bottom = Spacing.md),
                    title = if (failed) stringResource(R.string.settings_network_import_failed_title) else null,
                    actions = { TextButton(onClick = onDismissMessage) { Text(stringResource(R.string.settings_network_dismiss)) } },
                )
            }
        }
        item {
            CardGroup {
                state.defaults.certificates.forEachIndexed { i, cert ->
                    if (i > 0) GroupDivider(inset = Insets.iconRow)
                    val users = state.usedBy[cert.id].orEmpty()
                    ListRow(
                        title = cert.subject,
                        subtitle = certificateUse(LocalResources.current, cert, users),
                        onClick = { openCert = cert.id },
                        leading = { IconTile(LocusIcons.Shield) },
                        trailing = {
                            when {
                                // A choice the user made, worth noticing, not an error.
                                cert.allProviders -> StatusPill(stringResource(R.string.settings_network_all_providers), Tone.Caution)
                                cert.notAfter < System.currentTimeMillis() -> StatusPill(stringResource(R.string.settings_network_expired), Tone.Caution)
                            }
                            RowChevron()
                        },
                    )
                }
                if (state.defaults.certificates.isNotEmpty()) GroupDivider(inset = Insets.iconRow)
                AddRow(stringResource(R.string.settings_network_import)) { onImport() }
            }
        }
        item {
            SectionNote(
                stringResource(R.string.settings_network_certificates_note),
                NotePadding,
            )
        }
    }

    if (editingProxy) {
        DefaultProxySheet(proxy, credentialsSaved = SavedNetworkSecrets.DEFAULT_SCOPE in state.saved.proxyScopes, onSave = { p, c ->
            onSetProxy(p, c)
            editingProxy = false
        }, onDismiss = { editingProxy = false })
    }

    openCert?.let { id ->
        val cert = state.defaults.certificates.firstOrNull { it.id == id }
        if (cert == null) {
            openCert = null
        } else {
            CertificateSheet(cert, state.usedBy[id].orEmpty(), onSetTrustedByAll = { onSetTrustedByAll(id, it) }, onRemove = {
                onRemove(id)
                openCert = null
            }, onDismiss = { openCert = null })
        }
    }
}

internal fun certificateUse(res: Resources, cert: TrustedCertificate, users: List<String>): String = when {
    cert.allProviders -> res.getString(R.string.settings_network_cert_use_all)
    users.isEmpty() -> res.getString(R.string.settings_network_cert_use_none)
    // Provider names, as the user named them.
    users.size <= 2 -> res.getString(R.string.settings_network_cert_use_named, users.joinToString(", "))
    else -> res.getQuantityString(R.plurals.settings_network_cert_use_count, users.size, users.size)
}

@Composable
private fun DefaultProxySheet(proxy: ProxyConfig?, credentialsSaved: Boolean, onSave: (ProxyConfig?, Pair<String, String>?) -> Unit, onDismiss: () -> Unit) {
    var enabled by remember { mutableStateOf(proxy != null) }
    var type by remember { mutableStateOf(proxy?.type ?: ProxyType.HTTP) }
    var host by remember { mutableStateOf(proxy?.host.orEmpty()) }
    var port by remember { mutableStateOf(proxy?.port?.toString().orEmpty()) }
    var auth by remember { mutableStateOf(proxy?.hasCredentials == true) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val config = ProxyConfig(type, host.trim(), port.toIntOrNull() ?: 0, hasCredentials = auth).takeIf { it.isValid }
    val needsCredentials = auth && !credentialsSaved && user.isEmpty() && password.isEmpty()
    LocusSheet(
        title = stringResource(R.string.settings_network_proxy_section),
        subtitle = stringResource(R.string.settings_network_sheet_subtitle),
        onDismiss = onDismiss,
        action = stringResource(R.string.settings_network_save),
        actionEnabled = !enabled || (config != null && !needsCredentials),
        onAction = {
            onSave(if (enabled) config else null, if (enabled && auth && (user.isNotEmpty() || password.isNotEmpty())) user to password else null)
        },
    ) {
        Segmented(listOf(stringResource(R.string.settings_network_no_proxy), stringResource(R.string.settings_network_use_proxy)), selected = if (enabled) 1 else 0, onSelect = { enabled = it == 1 }, modifier = Modifier.fillMaxWidth())
        if (enabled) {
            ProxyFields(
                type = type, onType = { type = it },
                host = host, onHost = { host = it },
                port = port, onPort = { port = it },
                auth = auth, onAuth = { auth = it },
                user = user, onUser = { user = it },
                password = password, onPassword = { password = it },
                credentialsSaved = credentialsSaved,
            )
        }
    }
}

@Composable
private fun CertificateSheet(
    cert: TrustedCertificate,
    users: List<String>,
    onSetTrustedByAll: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmAll by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    LocusSheet(title = cert.subject, subtitle = certificateUse(LocalResources.current, cert, users), onDismiss = onDismiss) {
        val expired = cert.notAfter < System.currentTimeMillis()
        val date = DateFormat.getDateInstance(DateFormat.LONG).format(Date(cert.notAfter))
        if (expired) {
            Banner(
                title = stringResource(R.string.settings_network_cert_expired_title, date),
                text = stringResource(R.string.settings_network_cert_expired_text),
                tone = Tone.Caution,
            )
        }
        Detail(stringResource(R.string.settings_network_cert_fingerprint), fingerprintLines(cert.sha256))
        if (!expired) Detail(stringResource(R.string.settings_network_cert_expires), date)
        SupportingText(
            stringResource(R.string.settings_network_cert_check),
        )
        CardGroup {
            SwitchRow(
                title = stringResource(R.string.settings_network_cert_trust_all),
                checked = cert.allProviders,
                onCheckedChange = { on -> if (on) confirmAll = true else onSetTrustedByAll(false) },
            )
        }
        if (cert.allProviders) {
            Banner(
                stringResource(R.string.settings_network_cert_trust_all_warning),
                Tone.Critical,
            )
        }
        CriticalOutlinedButton(stringResource(R.string.settings_network_cert_remove), onClick = { confirmRemove = true })
    }

    if (confirmAll) {
        ConfirmSheet(
            title = stringResource(R.string.settings_network_cert_trust_all_title),
            message = stringResource(R.string.settings_network_cert_trust_all_message, cert.subject),
            confirmLabel = stringResource(R.string.settings_network_cert_trust_all_confirm),
            tone = Tone.Critical,
            onConfirm = {
                onSetTrustedByAll(true)
                confirmAll = false
            },
            onDismiss = { confirmAll = false },
        )
    }
    if (confirmRemove) {
        ConfirmSheet(
            title = stringResource(R.string.settings_network_cert_remove_title, cert.subject),
            message = stringResource(R.string.settings_network_cert_remove_message),
            confirmLabel = stringResource(R.string.settings_network_cert_remove_confirm),
            tone = Tone.Critical,
            onConfirm = onRemove,
            onDismiss = { confirmRemove = false },
        )
    }
}

/** A colon-separated fingerprint that wraps only between bytes, never inside one. */
internal fun fingerprintLines(sha256: String): String = sha256.replace(":", ":\u200B")

@Composable
private fun Detail(label: String, value: String) {
    Text(label, style = LocusType.label)
    SupportingText(value, style = LocusType.mono)
}

/** For the Settings hub: "HTTP proxy · 2 certificates", or "Direct". */
internal fun networkHubSummary(res: Resources, defaults: NetworkDefaults): String {
    val parts = mutableListOf<String>()
    parts += defaults.proxy?.let { res.getString(R.string.settings_network_hub_proxy, it.type.label()) } ?: res.getString(R.string.settings_network_hub_direct)
    val n = defaults.certificates.size
    if (n > 0) parts += res.getQuantityString(R.plurals.settings_network_certificates_count, n, n)
    val everywhere = defaults.certificates.count { it.allProviders }
    if (everywhere > 0) parts += res.getQuantityString(R.plurals.settings_network_hub_all_providers, everywhere, everywhere)
    return summaryJoin(res, parts)
}
