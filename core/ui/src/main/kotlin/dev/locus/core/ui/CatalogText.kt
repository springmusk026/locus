package dev.locus.core.ui

import android.content.res.Resources
import dev.locus.core.agent.integrations.Integration
import dev.locus.core.agent.integrations.ServerCheck
import dev.locus.core.data.connectors.ConnectorTemplate
import dev.locus.core.data.mcp.McpTemplate
import dev.locus.core.model.ProviderPreset

/*
 * The catalogues' text in the app's language (docs/design/localization.md, "Text from the engine"):
 * Settings → Integrations, Library's MCP and service templates, the provider presets and the email
 * check. The catalogues keep their English, because the model reads it (a template's description is
 * saved into the connector it adds) or it is saved (a preset's name); screens show these instead.
 * An entry without a resource (one a later change adds) shows its English.
 */

private fun Resources.textOr(id: Int?, english: String): String = id?.let(::getString) ?: english

/** "a, b and c" in the app's language. */
fun Resources.catalogList(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items.single()
    else -> getString(R.string.ui_catalog_list_last, items.dropLast(1).joinToString(getString(R.string.ui_catalog_list_separator)), items.last())
}

// ---- Integrations (core/agent) ---------------------------------------------------------------

/** The name; a product's stays, "Email" is translated. */
fun Integration.displayName(r: Resources): String = r.textOr(CatalogRes.INTEGRATION_NAME[id], name)

fun Integration.summaryText(r: Resources): String = r.textOr(CatalogRes.INTEGRATION_SUMMARY[id], summary)

fun Integration.descriptionText(r: Resources): String = r.textOr(CatalogRes.INTEGRATION_DESCRIPTION[id], description)

fun Integration.keyLabelText(r: Resources): String = r.textOr(CatalogRes.LABEL[keyLabel], keyLabel)

fun Integration.linkLabelText(r: Resources): String = r.textOr(CatalogRes.LINK[linkLabel], linkLabel)

fun Integration.keyHelpText(r: Resources): String? = keyHelp?.let { r.textOr(CatalogRes.INTEGRATION_KEY_HELP[id], it) }

// ---- MCP templates (core/data) ---------------------------------------------------------------

fun McpTemplate.descriptionText(r: Resources): String = r.textOr(CatalogRes.MCP_DESCRIPTION[id], description)

fun McpTemplate.noteText(r: Resources): String? = note?.let { r.textOr(CatalogRes.MCP_NOTE[id], it) }

/** The key's scopes or permissions, in the vendor's words where they are its setting names. */
fun McpTemplate.scopesText(r: Resources): String? = key?.scopes?.let { r.textOr(CatalogRes.MCP_SCOPES[id], it) }

fun McpTemplate.fullWarningText(r: Resources): String? = selfHosted?.fullWarning?.let { r.textOr(CatalogRes.MCP_FULL_WARNING[id], it) }

fun McpTemplate.Category.labelText(r: Resources): String = r.textOr(CatalogRes.MCP_CATEGORY[name], label)

/** [McpTemplate.byline] in the app's language. */
fun McpTemplate.bylineText(r: Resources): String = when {
    community -> r.getString(R.string.ui_catalog_mcp_by_community, vendor)
    category == McpTemplate.Category.LOCAL -> r.getString(R.string.ui_catalog_mcp_by_reference)
    selfHosted != null -> r.getString(R.string.ui_catalog_mcp_by_self_hosted, vendor)
    else -> r.getString(R.string.ui_catalog_mcp_by_official, vendor)
}

/** [McpTemplate.authSummary] in the app's language. */
fun McpTemplate.authSummaryText(r: Resources): String {
    val key = key
    val local = local
    return when {
        local != null && local.env.isEmpty() -> r.getString(R.string.ui_catalog_mcp_auth_none)
        local != null -> r.getString(R.string.ui_catalog_mcp_auth_needs, r.catalogList(local.env.map { local.phrase(r, it) }))
        signIn && key != null -> r.getString(R.string.ui_catalog_mcp_auth_sign_in_or, key.indefinite(r))
        signIn -> r.getString(R.string.ui_catalog_mcp_auth_sign_in)
        key == null -> r.getString(R.string.ui_catalog_mcp_auth_none)
        key.optional -> r.getString(R.string.ui_catalog_mcp_auth_optional, key.labelText(r))
        else -> r.getString(R.string.ui_catalog_mcp_auth_needs, key.indefinite(r))
    }
}

fun McpTemplate.Key.labelText(r: Resources): String = r.textOr(CatalogRes.LABEL[label], label)

/** What "Or paste … instead" names: English puts "a" before the lower-case label; other languages give the phrase. */
fun McpTemplate.Key.pasteText(r: Resources): String = r.textOr(CatalogRes.PASTE[label], label.lowercase())

/** "an API key", "a personal access token". */
private fun McpTemplate.Key.indefinite(r: Resources): String = r.textOr(CatalogRes.INDEFINITE[label], label)

/** [McpTemplate.Key.sentAs] in the app's language. */
fun McpTemplate.Key.sentAsText(r: Resources): String = when {
    header != null -> r.getString(R.string.ui_catalog_mcp_sent_header, header)
    query != null -> r.getString(R.string.ui_catalog_mcp_sent_query, query)
    else -> r.getString(R.string.ui_catalog_mcp_sent_bearer)
}

fun McpTemplate.Local.needsText(r: Resources): String = r.textOr(CatalogRes.MCP_NEEDS[needs], needs)

/** What variable [name] is, as "Needs …" lists it ("your Nextcloud address"). */
private fun McpTemplate.Local.phrase(r: Resources, name: String): String = label(name).let { r.textOr(CatalogRes.MCP_ENV_PHRASE[it], it) }

/** Variable [name]'s field label ("Nextcloud address"). */
fun McpTemplate.Local.fieldLabel(r: Resources, name: String): String = label(name).let { english ->
    r.textOr(CatalogRes.MCP_ENV_FIELD[english], english.removePrefix("your ").removePrefix("a ").replaceFirstChar { it.uppercase() })
}

// ---- Service templates (core/data) -----------------------------------------------------------

fun ConnectorTemplate.descriptionText(r: Resources): String = r.textOr(CatalogRes.SERVICE_DESCRIPTION[id], description)

fun ConnectorTemplate.noteText(r: Resources): String? = note?.let { r.textOr(CatalogRes.SERVICE_NOTE[id], it) }

fun ConnectorTemplate.hostHintText(r: Resources): String = r.textOr(CatalogRes.SERVICE_HOST_HINT[id], hostHint)

/** Where in the service the key is made, in its own menu names. */
fun ConnectorTemplate.whereText(r: Resources): String = r.textOr(CatalogRes.SERVICE_WHERE[id], auth.where)

fun ConnectorTemplate.AuthSpec.labelText(r: Resources): String = r.textOr(CatalogRes.LABEL[label], label)

/** The label inside a sentence ("your API key", "your access token"). */
fun ConnectorTemplate.AuthSpec.sentenceLabel(r: Resources): String = r.textOr(CatalogRes.SENTENCE[label], label)

fun ConnectorTemplate.AuthSpec.usernameLabelText(r: Resources): String? = usernameLabel?.let { r.textOr(CatalogRes.LABEL[it], it) }

/** [ConnectorTemplate.AuthSpec.sentAs] in the app's language. */
fun ConnectorTemplate.AuthSpec.sentAsText(r: Resources): String = when {
    login != null -> r.getString(R.string.ui_catalog_service_sent_login)
    type == "basic" -> r.getString(R.string.ui_catalog_service_sent_basic)
    type == "bearer" -> r.getString(R.string.ui_catalog_service_sent_bearer)
    prefix.isNotEmpty() -> r.getString(R.string.ui_catalog_service_sent_header_prefix, header, prefix.trim())
    else -> r.getString(R.string.ui_catalog_service_sent_header, header)
}

// ---- Provider presets (core/model) -----------------------------------------------------------

fun ProviderPreset.noteText(r: Resources): String = r.textOr(CatalogRes.PRESET_NOTE[name], note)

// ---- Email presets (core/agent) -------------------------------------------------------------

/** An email preset's link label, password hint, field label or note, in the app's language. */
fun Resources.emailPresetText(english: String): String = textOr(CatalogRes.EMAIL[english], english)

// ---- The email check (core/agent) ------------------------------------------------------------

/** What went wrong, in the app's language; the server's own words stay as they are. */
fun ServerCheck.Failed.messageText(r: Resources): String = when {
    host.isEmpty() -> message
    else -> when (problem) {
        ServerCheck.Problem.SIGN_IN -> r.getString(R.string.ui_email_check_sign_in, host)
        ServerCheck.Problem.HOST_NOT_FOUND -> r.getString(R.string.ui_email_check_host_not_found, host)
        ServerCheck.Problem.TLS -> r.getString(R.string.ui_email_check_tls, where, other)
        ServerCheck.Problem.TIMEOUT -> r.getString(R.string.ui_email_check_timeout, where, other)
        ServerCheck.Problem.UNREACHABLE -> r.getString(R.string.ui_email_check_unreachable, where)
        ServerCheck.Problem.OTHER -> r.getString(R.string.ui_email_check_other, host, said.ifBlank { r.getString(R.string.ui_email_check_failed) })
    }
}
