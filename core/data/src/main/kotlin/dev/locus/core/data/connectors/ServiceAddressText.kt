package dev.locus.core.data.connectors

import dev.locus.core.data.R
import dev.locus.core.linux.EngineText
import dev.locus.core.model.ServiceAddress

/*
 * A server address check ([ServiceAddress.check]) in the app's language (docs/design/localization.md, "Text
 * from the engine"). The check keeps its English, which the agent's provider draft reads; the setup sheets
 * show these instead.
 */

/** Why the address can't be used, or null when it can. */
val ServiceAddress.Checked.errorText: EngineText?
    get() = when (problem) {
        null -> error?.let(EngineText::Plain)
        ServiceAddress.Problem.EMPTY -> EngineText.Res(R.string.data_address_empty)
        ServiceAddress.Problem.SPACES -> EngineText.Res(R.string.data_address_spaces)
        ServiceAddress.Problem.NOT_HTTPS -> EngineText.Res(R.string.data_address_not_https)
        ServiceAddress.Problem.SCHEME -> EngineText.Res(R.string.data_address_scheme, scheme.orEmpty())
        ServiceAddress.Problem.UNREADABLE -> EngineText.Res(R.string.data_address_unreadable)
        ServiceAddress.Problem.USER_INFO -> EngineText.Res(R.string.data_address_user_info)
        ServiceAddress.Problem.QUERY -> EngineText.Res(R.string.data_address_query)
        ServiceAddress.Problem.NO_HOST -> EngineText.Res(R.string.data_address_no_host)
        ServiceAddress.Problem.PORT -> EngineText.Res(R.string.data_address_port)
        ServiceAddress.Problem.HTTP_PUBLIC -> EngineText.Res(R.string.data_address_http_public)
    }

/** The warning that goes with a usable plain-http address ([ServiceAddress.LAN_HTTP_WARNING]), or null. */
val ServiceAddress.Checked.warningText: EngineText?
    get() = when (val english = warning) {
        null -> null
        ServiceAddress.LAN_HTTP_WARNING -> EngineText.Res(R.string.data_address_lan_http_warning)
        else -> EngineText.Plain(english)
    }

/** [ServiceSetupException] for a refused address: its words for the screen, the English for logs. */
fun ServiceAddress.Checked.refused(): ServiceSetupException =
    ServiceSetupException(errorText ?: EngineText.Plain(error.orEmpty()), error.orEmpty())
