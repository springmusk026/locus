package dev.locus.feature.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.locus.core.data.network.NetworkSecretEdits
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
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.HeaderRules
import dev.locus.core.model.HeaderVerdict
import dev.locus.core.model.NetworkDefaults
import dev.locus.core.model.ProviderNetwork
import dev.locus.core.model.ProxyConfig
import dev.locus.core.model.ProxyMode
import dev.locus.core.model.ProxyType

/** One extra header in the editor: its name, and a new value when the user typed one. */
internal data class HeaderDraft(val name: String, val newValue: String? = null)

/**
 * The provider editor's network settings while they are edited; nothing is saved until the provider
 * is. Values typed here go to the vault with the provider ([secrets]), never into its settings.
 */
@Stable
internal class NetworkDraft(initial: ProviderNetwork) {
    val headers = mutableStateListOf<HeaderDraft>().apply { addAll(initial.headerNames.map { HeaderDraft(it) }) }
    var allowAuthOverride by mutableStateOf(initial.allowAuthOverride)
    var proxyMode by mutableStateOf(initial.proxyMode)
    var proxyType by mutableStateOf(initial.proxy?.type ?: ProxyType.HTTP)
    var proxyHost by mutableStateOf(initial.proxy?.host.orEmpty())
    var proxyPort by mutableStateOf(initial.proxy?.port?.toString().orEmpty())
    var proxyAuth by mutableStateOf(initial.proxy?.hasCredentials == true)
    var proxyUser by mutableStateOf("")
    var proxyPassword by mutableStateOf("")
    val certificateIds = mutableStateListOf<String>().apply { addAll(initial.certificateIds) }

    val customProxy: ProxyConfig?
        get() = ProxyConfig(proxyType, proxyHost.trim(), proxyPort.toIntOrNull() ?: 0, hasCredentials = proxyAuth).takeIf { it.isValid }

    /** Why the draft can't be saved yet, or null. */
    val problem: String?
        get() = when {
            proxyMode == ProxyMode.CUSTOM && customProxy == null -> "Enter the proxy's host and port, or pick another proxy option."
            headers.any { HeaderRules.check(it.name) is HeaderVerdict.Credential } && !allowAuthOverride ->
                "Allow replacing the key header, or remove that header."
            else -> null
        }

    fun network(): ProviderNetwork = ProviderNetwork(
        headerNames = headers.map { it.name },
        allowAuthOverride = allowAuthOverride && headers.any { HeaderRules.isCredential(it.name) },
        proxyMode = proxyMode,
        proxy = if (proxyMode == ProxyMode.CUSTOM) customProxy else customProxy.takeIf { proxyHost.isNotBlank() },
        certificateIds = certificateIds.toList(),
    )

    fun secrets(): NetworkSecretEdits = NetworkSecretEdits(
        headerValues = headers.mapNotNull { h -> h.newValue?.let { h.name to it } }.toMap(),
        proxyCredentials = if (proxyAuth && (proxyUser.isNotEmpty() || proxyPassword.isNotEmpty())) proxyUser to proxyPassword else null,
    )
}

/** "2 headers · Default proxy · 1 certificate". */
internal fun networkSummary(network: ProviderNetwork, defaults: NetworkDefaults): String {
    val parts = mutableListOf<String>()
    if (network.headerNames.isNotEmpty()) parts += "${network.headerNames.size} header${if (network.headerNames.size == 1) "" else "s"}"
    parts += when (network.proxyMode) {
        ProxyMode.DEFAULT -> if (defaults.proxy != null) "Default proxy" else "No proxy"
        ProxyMode.DIRECT -> "No proxy"
        ProxyMode.CUSTOM -> network.proxy?.let { "Proxy ${it.host}:${it.port}" } ?: "No proxy"
    }
    val certs = defaults.certificates.count { it.allProviders || it.id in network.certificateIds }
    if (certs > 0) parts += "$certs certificate${if (certs == 1) "" else "s"}"
    return parts.joinToString(" · ")
}

/** The provider editor's "Network" row; opens [ProviderNetworkSheet]. */
@Composable
internal fun ProviderNetworkRow(draft: NetworkDraft, defaults: NetworkDefaults, onOpen: () -> Unit) {
    CardGroup {
        ListRow(
            title = "Network",
            subtitle = networkSummary(draft.network(), defaults),
            onClick = onOpen,
            leading = { IconTile(LocusIcons.Globe) },
            trailing = {
                if (draft.problem != null) StatusPill("Check", Tone.Caution)
                Chevron()
            },
        )
    }
}

@Composable
internal fun ProviderNetworkSheet(
    providerId: String,
    providerName: String,
    signsIn: Boolean,
    draft: NetworkDraft,
    defaults: NetworkDefaults,
    savedSecrets: SavedNetworkSecrets,
    onDismiss: () -> Unit,
) {
    var addingHeader by remember { mutableStateOf(false) }
    LocusSheet(
        title = "Network",
        subtitle = "For $providerName only. Saved when you save the provider.",
        onDismiss = onDismiss,
        action = "Done",
        onAction = onDismiss,
    ) {
        // ---- Extra headers
        SectionLabel("Extra headers")
        CardGroup {
            draft.headers.forEachIndexed { i, header ->
                if (i > 0) GroupDivider()
                val saved = savedSecrets.hasHeader(providerId, header.name)
                ListRow(
                    title = header.name,
                    subtitle = when {
                        header.newValue != null -> "New value, saved with the provider"
                        saved -> "Value saved in the vault"
                        else -> "No value yet"
                    },
                    trailing = {
                        if (header.newValue == null && !saved) StatusPill("No value", Tone.Caution)
                        IconButton(onClick = { draft.headers.removeAt(i) }) {
                            Icon(LocusIcons.Trash, contentDescription = "Remove ${header.name}")
                        }
                    },
                )
            }
            if (draft.headers.isNotEmpty()) GroupDivider()
            if (draft.headers.size < HeaderRules.MAX_HEADERS) AddRow("Add a header") { addingHeader = true }
        }
        Text(
            "For example HTTP-Referer and X-Title for OpenRouter, or cf-aig-authorization for Cloudflare AI Gateway. Values are encrypted like keys and never shown again. " +
                "Redirects aren't followed while headers are set, so they can't reach another server.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!signsIn && draft.headers.any { HeaderRules.isCredential(it.name) }) {
            Banner(
                title = "Replace the key header?",
                text = "A header named Authorization or x-api-key replaces the key Locus sends for this provider. Only turn this on for a gateway that signs requests itself; " +
                    "otherwise your key and this value both go to the same server.",
                tone = Tone.Caution,
                actions = { Switch(checked = draft.allowAuthOverride, onCheckedChange = { draft.allowAuthOverride = it }) },
            )
        }

        // ---- Proxy
        SectionLabel("Proxy")
        val modes = listOf(ProxyMode.DEFAULT, ProxyMode.DIRECT, ProxyMode.CUSTOM)
        Segmented(
            listOf(if (defaults.proxy != null) "Default" else "Default (none)", "No proxy", "Custom"),
            selected = modes.indexOf(draft.proxyMode),
            onSelect = { draft.proxyMode = modes[it] },
            modifier = Modifier.fillMaxWidth(),
        )
        when (draft.proxyMode) {
            ProxyMode.DEFAULT -> Text(
                defaults.proxy?.let { "Uses the default proxy, ${it.type.label()} ${it.host}:${it.port}. Change it in Settings → Network." }
                    ?: "No default proxy is set, so this provider connects directly. Set one in Settings → Network.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProxyMode.DIRECT -> Text(
                "Connects directly, even when a default proxy is set. Use this for servers on your own network.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProxyMode.CUSTOM -> ProxyFields(
                type = draft.proxyType, onType = { draft.proxyType = it },
                host = draft.proxyHost, onHost = { draft.proxyHost = it },
                port = draft.proxyPort, onPort = { draft.proxyPort = it },
                auth = draft.proxyAuth, onAuth = { draft.proxyAuth = it },
                user = draft.proxyUser, onUser = { draft.proxyUser = it },
                password = draft.proxyPassword, onPassword = { draft.proxyPassword = it },
                credentialsSaved = providerId in savedSecrets.proxyScopes,
            )
        }

        // ---- Certificates
        SectionLabel("Trusted certificates")
        if (defaults.certificates.isEmpty()) {
            Text(
                "Import a CA certificate in Settings → Network to trust a private server or a company gateway.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CardGroup {
                defaults.certificates.forEachIndexed { i, cert ->
                    if (i > 0) GroupDivider()
                    val checked = cert.allProviders || cert.id in draft.certificateIds
                    ListRow(
                        title = cert.subject,
                        subtitle = if (cert.allProviders) "Trusted by every provider" else "SHA-256 ${cert.sha256.take(23)}…",
                        onClick = if (cert.allProviders) null else ({ if (checked) draft.certificateIds.remove(cert.id) else draft.certificateIds.add(cert.id) }),
                        trailing = {
                            Checkbox(
                                checked = checked,
                                enabled = !cert.allProviders,
                                onCheckedChange = { on -> if (on) draft.certificateIds.add(cert.id) else draft.certificateIds.remove(cert.id) },
                            )
                        },
                    )
                }
            }
            Text(
                "Connections to this provider also trust the certificates you tick, on top of the system's. Nothing else in the app does.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        draft.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Locus.colors.critical) }
    }

    if (addingHeader) {
        AddHeaderSheet(
            existing = draft.headers.map { it.name.lowercase() }.toSet(),
            onAdd = { name, value ->
                draft.headers.add(HeaderDraft(name, value))
                addingHeader = false
            },
            onDismiss = { addingHeader = false },
        )
    }
}

@Composable
private fun AddHeaderSheet(existing: Set<String>, onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    val verdict = HeaderRules.check(name)
    val nameError = when {
        name.isBlank() -> null
        name.trim().lowercase() in existing -> "That header is already in the list."
        verdict is HeaderVerdict.Forbidden -> verdict.reason
        else -> null
    }
    val valueError = if (value.isEmpty()) null else HeaderRules.checkValue(value)
    val canAdd = name.isNotBlank() && nameError == null && value.isNotEmpty() && valueError == null
    LocusSheet(title = "Add a header", onDismiss = onDismiss, action = "Add", actionEnabled = canAdd, onAction = { onAdd(name.trim(), value) }) {
        OutlinedTextField(
            name,
            { name = it.trim() },
            label = { Text("Name") },
            placeholder = { Text("X-Title") },
            singleLine = true,
            isError = nameError != null,
            supportingText = when {
                nameError != null -> ({ Text(nameError) })
                verdict is HeaderVerdict.Credential -> ({ Text(verdict.warning, color = Locus.colors.caution) })
                else -> null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value,
            { value = it },
            label = { Text("Value") },
            singleLine = true,
            isError = valueError != null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = { Text(valueError ?: "Encrypted on this phone. It won't be shown again.") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Type, host, port and optional credentials; shared by the provider editor and Settings → Network. */
@Composable
internal fun ProxyFields(
    type: ProxyType,
    onType: (ProxyType) -> Unit,
    host: String,
    onHost: (String) -> Unit,
    port: String,
    onPort: (String) -> Unit,
    auth: Boolean,
    onAuth: (Boolean) -> Unit,
    user: String,
    onUser: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    credentialsSaved: Boolean,
) {
    val types = listOf(ProxyType.HTTP, ProxyType.SOCKS5)
    Segmented(types.map { it.label() }, selected = types.indexOf(type), onSelect = { onType(types[it]) }, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            host,
            { onHost(it.trim()) },
            label = { Text("Host") },
            placeholder = { Text("proxy.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            port,
            { onPort(it.filter(Char::isDigit).take(5)) },
            label = { Text("Port") },
            placeholder = { Text(if (type == ProxyType.SOCKS5) "1080" else "8080") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.padding(start = Spacing.sm).weight(0.45f),
        )
    }
    SwitchLine("Proxy needs a sign-in", auth, onAuth)
    if (auth) {
        OutlinedTextField(
            user,
            onUser,
            label = { Text(if (credentialsSaved) "Username (saved — type to replace)" else "Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            password,
            onPassword,
            label = { Text(if (credentialsSaved) "Password (saved — type to replace)" else "Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(
        "Only model requests use this proxy. Addresses on this phone (localhost, 127.0.0.1) always connect directly.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SwitchLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

internal fun ProxyType.label() = when (this) {
    ProxyType.HTTP -> "HTTP"
    ProxyType.SOCKS5 -> "SOCKS5"
}
