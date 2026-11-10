package dev.locus.feature.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.locus.core.data.network.NetworkSecretEdits
import dev.locus.core.data.network.SavedNetworkSecrets
import dev.locus.core.designsystem.SecretContent
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.UiText
import dev.locus.core.designsystem.asString
import dev.locus.core.model.SettingsDestination
import dev.locus.core.designsystem.SettingsLink
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.HeaderRules
import dev.locus.core.model.HeaderVerdict
import dev.locus.core.model.NetworkDefaults
import dev.locus.core.model.ProviderNetwork
import dev.locus.core.model.ProxyConfig
import dev.locus.core.model.ProxyMode
import dev.locus.core.model.ProxyType
import dev.locus.core.designsystem.AddRow
import dev.locus.core.designsystem.RowChevron
import android.content.res.Resources
import androidx.compose.ui.platform.LocalResources

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
    val problem: UiText?
        get() = when {
            proxyMode == ProxyMode.CUSTOM && customProxy == null -> UiText.Res(R.string.settings_pnet_problem_proxy)
            headers.any { HeaderRules.check(it.name) is HeaderVerdict.Credential } && !allowAuthOverride ->
                UiText.Res(R.string.settings_pnet_problem_header)
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
internal fun networkSummary(res: Resources, network: ProviderNetwork, defaults: NetworkDefaults): String {
    val parts = mutableListOf<String>()
    val headers = network.headerNames.size
    if (headers > 0) parts += res.getQuantityString(R.plurals.settings_pnet_summary_headers, headers, headers)
    val noProxy = res.getString(R.string.settings_pnet_proxy_none)
    parts += when (network.proxyMode) {
        ProxyMode.DEFAULT -> if (defaults.proxy != null) res.getString(R.string.settings_pnet_summary_default_proxy) else noProxy
        ProxyMode.DIRECT -> noProxy
        ProxyMode.CUSTOM -> network.proxy?.let { res.getString(R.string.settings_pnet_summary_custom_proxy, "${it.host}:${it.port}") } ?: noProxy
    }
    val certs = defaults.certificates.count { it.allProviders || it.id in network.certificateIds }
    if (certs > 0) parts += res.getQuantityString(R.plurals.settings_network_certificates_count, certs, certs)
    return summaryJoin(res, parts)
}

/** The provider editor's "Network" row; opens [ProviderNetworkSheet]. */
@Composable
internal fun ProviderNetworkRow(draft: NetworkDraft, defaults: NetworkDefaults, onOpen: () -> Unit) {
    CardGroup {
        ListRow(
            title = stringResource(R.string.settings_pnet_title),
            subtitle = networkSummary(LocalResources.current, draft.network(), defaults),
            subtitleMaxLines = 2,
            onClick = onOpen,
            leading = { IconTile(LocusIcons.Globe) },
            trailing = {
                if (draft.problem != null) StatusPill(stringResource(R.string.settings_pnet_check), Tone.Caution)
                RowChevron()
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
        title = stringResource(R.string.settings_pnet_title),
        subtitle = stringResource(R.string.settings_pnet_subtitle, providerName),
        onDismiss = onDismiss,
        action = stringResource(R.string.settings_pnet_done),
        onAction = onDismiss,
    ) {
        // What stops Done from saving, at the top where it's seen first.
        draft.problem?.let { Banner(it.asString(), Tone.Caution, title = stringResource(R.string.settings_pnet_cant_save)) }

        // ---- Extra headers
        SectionLabel(stringResource(R.string.settings_pnet_headers_section))
        CardGroup {
            draft.headers.forEachIndexed { i, header ->
                if (i > 0) GroupDivider()
                val saved = savedSecrets.hasHeader(providerId, header.name)
                ListRow(
                    title = header.name,
                    titleMaxLines = 2,
                    // With no value yet the chip says so; a subtitle would repeat it.
                    subtitle = when {
                        header.newValue != null -> stringResource(R.string.settings_pnet_header_new_value)
                        saved -> stringResource(R.string.settings_pnet_header_saved)
                        else -> null
                    },
                    trailing = {
                        if (header.newValue == null && !saved) StatusPill(stringResource(R.string.settings_pnet_header_no_value), Tone.Caution)
                        IconButton(onClick = { draft.headers.removeAt(i) }) {
                            Icon(LocusIcons.Trash, contentDescription = stringResource(R.string.settings_pnet_header_remove, header.name))
                        }
                    },
                )
            }
            if (draft.headers.isNotEmpty()) GroupDivider()
            if (draft.headers.size < HeaderRules.MAX_HEADERS) AddRow(stringResource(R.string.settings_pnet_header_add)) { addingHeader = true }
        }
        SupportingText(
            stringResource(R.string.settings_pnet_headers_note),
        )
        if (!signsIn && draft.headers.any { HeaderRules.isCredential(it.name) }) {
            Banner(
                title = stringResource(R.string.settings_pnet_override_title),
                text = stringResource(R.string.settings_pnet_override_text),
                tone = Tone.Caution,
            )
            CardGroup {
                SwitchRow(
                    title = stringResource(R.string.settings_pnet_override_switch),
                    checked = draft.allowAuthOverride,
                    onCheckedChange = { draft.allowAuthOverride = it },
                )
            }
        }

        // ---- Proxy
        SectionLabel(stringResource(R.string.settings_pnet_proxy_section))
        val modes = listOf(ProxyMode.DEFAULT, ProxyMode.DIRECT, ProxyMode.CUSTOM)
        Segmented(
            listOf(
                stringResource(if (defaults.proxy != null) R.string.settings_pnet_proxy_default else R.string.settings_pnet_proxy_default_none),
                stringResource(R.string.settings_pnet_proxy_none),
                stringResource(R.string.settings_pnet_proxy_custom),
            ),
            selected = modes.indexOf(draft.proxyMode),
            onSelect = { draft.proxyMode = modes[it] },
            modifier = Modifier.fillMaxWidth(),
        )
        when (draft.proxyMode) {
            // The default lives on the Network page: a link there, not a path to find by hand.
            ProxyMode.DEFAULT -> SettingsLink(
                SettingsDestination.Network,
                hint = defaults.proxy?.let { stringResource(R.string.settings_pnet_proxy_default_hint, it.type.label(), it.host, it.port) }
                    ?: stringResource(R.string.settings_pnet_proxy_default_none_hint),
            )
            ProxyMode.DIRECT -> SupportingText(
                stringResource(R.string.settings_pnet_proxy_direct_hint),
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
        SectionLabel(stringResource(R.string.settings_pnet_certs_section))
        if (defaults.certificates.isEmpty()) {
            SettingsLink(SettingsDestination.Network, hint = stringResource(R.string.settings_pnet_certs_import_hint))
        } else {
            CardGroup {
                defaults.certificates.forEachIndexed { i, cert ->
                    if (i > 0) GroupDivider()
                    val checked = cert.allProviders || cert.id in draft.certificateIds
                    ListRow(
                        title = cert.subject,
                        // Who trusts it, or when it runs out; the fingerprint is in the certificate's own sheet.
                        subtitle = if (cert.allProviders) stringResource(R.string.settings_pnet_certs_all) else certificateExpiry(cert.notAfter, System.currentTimeMillis()),
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
            SupportingText(
                stringResource(R.string.settings_pnet_certs_note),
            )
        }
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
        name.trim().lowercase() in existing -> stringResource(R.string.settings_pnet_add_duplicate)
        verdict is HeaderVerdict.Forbidden -> verdict.reason
        else -> null
    }
    val valueError = if (value.isEmpty()) null else HeaderRules.checkValue(value)
    val canAdd = name.isNotBlank() && nameError == null && value.isNotEmpty() && valueError == null
    LocusSheet(title = stringResource(R.string.settings_pnet_header_add), onDismiss = onDismiss, action = stringResource(R.string.settings_pnet_add), actionEnabled = canAdd, onAction = { onAdd(name.trim(), value) }) {
        OutlinedTextField(
            name,
            { name = it.trim() },
            label = { Text(stringResource(R.string.settings_pnet_add_name)) },
            placeholder = { Text(stringResource(R.string.settings_pnet_add_name_placeholder)) },
            singleLine = true,
            isError = nameError != null,
            supportingText = when {
                nameError != null -> ({ Text(nameError) })
                verdict is HeaderVerdict.Credential -> ({
                    Text(
                        stringResource(R.string.settings_pnet_add_credential_warning, verdict.warning),
                        color = Locus.colors.onCautionContainer,
                    )
                })
                else -> null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        SecretContent()
        OutlinedTextField(
            value,
            { value = it },
            label = { Text(stringResource(R.string.settings_pnet_add_value)) },
            singleLine = true,
            isError = valueError != null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = { Text(valueError ?: stringResource(R.string.settings_pnet_add_value_hint)) },
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
    val hostField = @Composable { modifier: Modifier ->
        OutlinedTextField(
            host,
            { onHost(it.trim()) },
            label = { Text(stringResource(R.string.settings_pnet_host)) },
            placeholder = { Text(stringResource(R.string.settings_pnet_host_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = modifier,
        )
    }
    val portField = @Composable { modifier: Modifier ->
        OutlinedTextField(
            port,
            { onPort(it.filter(Char::isDigit).take(5)) },
            label = { Text(stringResource(R.string.settings_pnet_port)) },
            placeholder = { Text(stringResource(if (type == ProxyType.SOCKS5) R.string.settings_pnet_port_socks else R.string.settings_pnet_port_http)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = modifier,
        )
    }
    // Side by side, or stacked at a large font so neither value is clipped in its field.
    if (LocalDensity.current.fontScale > STACK_FIELDS_FONT) {
        hostField(Modifier.fillMaxWidth())
        portField(Modifier.fillMaxWidth())
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            hostField(Modifier.weight(1f))
            portField(Modifier.padding(start = Spacing.sm).weight(0.45f))
        }
    }
    SwitchLine(stringResource(R.string.settings_pnet_auth), auth, onAuth)
    if (auth) {
        OutlinedTextField(
            user,
            onUser,
            label = { Text(stringResource(if (credentialsSaved) R.string.settings_pnet_username_saved else R.string.settings_pnet_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SecretContent()
        OutlinedTextField(
            password,
            onPassword,
            label = { Text(stringResource(if (credentialsSaved) R.string.settings_pnet_password_saved else R.string.settings_pnet_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    SupportingText(
        stringResource(R.string.settings_pnet_proxy_note),
    )
}

/** From this font scale a host and its port take a line each. */
internal const val STACK_FIELDS_FONT = 1.3f

/** "Expires 3 March 2027", or "Expired 9 October 2025". */
@Composable
internal fun certificateExpiry(notAfter: Long, now: Long): String =
    stringResource(
        if (notAfter < now) R.string.settings_pnet_certs_expired else R.string.settings_pnet_certs_expires,
        java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date(notAfter)),
    )

@Composable
private fun SwitchLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    CardGroup { SwitchRow(title = text, checked = checked, onCheckedChange = onChange) }
}

internal fun ProxyType.label() = when (this) {
    ProxyType.HTTP -> "HTTP"
    ProxyType.SOCKS5 -> "SOCKS5"
}
