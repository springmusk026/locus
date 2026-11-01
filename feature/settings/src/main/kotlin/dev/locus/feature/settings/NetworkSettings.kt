package dev.locus.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.network.NetworkSettingsRepository
import dev.locus.core.data.network.SavedNetworkSecrets
import dev.locus.core.designsystem.Banner
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

data class NetworkUiState(
    val defaults: NetworkDefaults = NetworkDefaults(),
    val saved: SavedNetworkSecrets = SavedNetworkSecrets(),
    /** Provider names that tick each certificate, by certificate id. */
    val usedBy: Map<String, List<String>> = emptyMap(),
    /** The last import's outcome: a message and whether it failed. */
    val message: Pair<String, Boolean>? = null,
)

@HiltViewModel
class NetworkViewModel @Inject constructor(private val network: NetworkSettingsRepository) : ViewModel() {
    private val message = MutableStateFlow<Pair<String, Boolean>?>(null)

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
                    0 -> "That certificate is already imported." to false
                    1 -> "Imported. Tick it in a provider's Network settings to trust it there." to false
                    else -> "Imported $n certificates. Tick them in a provider's Network settings to trust them there." to false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                (e.message ?: "Couldn't read that file.") to true
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

    SettingsPageScaffold("Network", onBack) {
        item { SectionLabel("Default proxy", SectionPadding) }
        item {
            CardGroup {
                ListRow(
                    title = proxy?.let { "${it.type.label()} ${it.host}:${it.port}" } ?: "No proxy",
                    subtitle = proxy?.let { if (it.hasCredentials) "With a sign-in" else "No sign-in" } ?: "Model providers connect directly",
                    onClick = { editingProxy = true },
                    leading = { IconTile(LocusIcons.Server) },
                    trailing = { RowChevron() },
                )
            }
        }
        item {
            SectionNote(
                "Used by every model provider set to Default. A provider can pick No proxy or its own in Settings → Models → provider → Network. " +
                    "Addresses on this phone always connect directly. Integrations, MCP servers and web fetches don't use it.",
                NotePadding,
            )
        }

        item { SectionLabel("Trusted certificates", SectionPadding) }
        state.message?.let { (text, failed) ->
            item {
                Banner(text, if (failed) Tone.Critical else Tone.Brand, actions = { TextButton(onClick = onDismissMessage) { Text("OK") } })
            }
        }
        item {
            CardGroup {
                state.defaults.certificates.forEachIndexed { i, cert ->
                    if (i > 0) GroupDivider(inset = Insets.iconRow)
                    val users = state.usedBy[cert.id].orEmpty()
                    ListRow(
                        title = cert.subject,
                        subtitle = certificateUse(cert, users),
                        onClick = { openCert = cert.id },
                        leading = { IconTile(LocusIcons.Shield) },
                        trailing = {
                            when {
                                cert.allProviders -> StatusPill("All providers", Tone.Critical)
                                cert.notAfter < System.currentTimeMillis() -> StatusPill("Expired", Tone.Caution)
                            }
                            RowChevron()
                        },
                    )
                }
                if (state.defaults.certificates.isNotEmpty()) GroupDivider(inset = Insets.iconRow)
                AddRow("Import a certificate") { onImport() }
            }
        }
        item {
            SectionNote(
                "For a private server or a company gateway with its own CA. Pick a .pem, .crt or .cer file. A certificate is trusted only by the providers that tick it, " +
                    "on top of the system's certificates; the rest of the app and other apps are not affected.",
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

internal fun certificateUse(cert: TrustedCertificate, users: List<String>): String = when {
    cert.allProviders -> "Trusted by every provider"
    users.isEmpty() -> "Not used by any provider yet"
    users.size <= 2 -> "Trusted by ${users.joinToString(", ")}"
    else -> "Trusted by ${users.size} providers"
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
        title = "Default proxy",
        subtitle = "For model providers set to Default.",
        onDismiss = onDismiss,
        action = "Save",
        actionEnabled = !enabled || (config != null && !needsCredentials),
        onAction = {
            onSave(if (enabled) config else null, if (enabled && auth && (user.isNotEmpty() || password.isNotEmpty())) user to password else null)
        },
    ) {
        Segmented(listOf("No proxy", "Use a proxy"), selected = if (enabled) 1 else 0, onSelect = { enabled = it == 1 }, modifier = Modifier.fillMaxWidth())
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
    LocusSheet(title = cert.subject, subtitle = certificateUse(cert, users), onDismiss = onDismiss) {
        Detail("SHA-256 fingerprint", cert.sha256)
        Detail(
            if (cert.notAfter < System.currentTimeMillis()) "Expired" else "Expires",
            DateFormat.getDateInstance(DateFormat.LONG).format(Date(cert.notAfter)),
        )
        Text(
            "Check the fingerprint with whoever runs the server before you trust it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Trust for every provider", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = cert.allProviders, onCheckedChange = { on -> if (on) confirmAll = true else onSetTrustedByAll(false) })
        }
        if (cert.allProviders) {
            Banner(
                "Whoever holds this CA's key can read and change your traffic to every model provider, including your API keys. Turn this off unless you need it.",
                Tone.Critical,
            )
        }
        Row {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirmRemove = true }) { Text("Remove", color = Locus.colors.critical) }
        }
    }

    if (confirmAll) {
        LocusSheet(
            title = "Trust for every provider?",
            subtitle = "Every model provider will accept servers signed by ${cert.subject}. Whoever holds its key could then read and change those connections, " +
                "including your API keys and chats. Only do this for a CA you control, such as your company's gateway.",
            onDismiss = { confirmAll = false },
            action = "Trust everywhere",
            actionTone = Tone.Critical,
            onAction = {
                onSetTrustedByAll(true)
                confirmAll = false
            },
        ) {}
    }
    if (confirmRemove) {
        LocusSheet(
            title = "Remove ${cert.subject}?",
            subtitle = "Providers that trust it stop connecting to servers it signed.",
            onDismiss = { confirmRemove = false },
            action = "Remove",
            actionTone = Tone.Critical,
            onAction = onRemove,
        ) {}
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Text(value, style = dev.locus.core.designsystem.MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** For the Settings hub: "HTTP proxy · 2 certificates", or "Direct". */
internal fun networkHubSummary(defaults: NetworkDefaults): String {
    val parts = mutableListOf<String>()
    parts += defaults.proxy?.let { "${it.type.label()} proxy" } ?: "Direct"
    val n = defaults.certificates.size
    if (n > 0) parts += "$n certificate${if (n == 1) "" else "s"}"
    val everywhere = defaults.certificates.count { it.allProviders }
    if (everywhere > 0) parts += "$everywhere for all providers"
    return parts.joinToString(" · ")
}
