package dev.locus.core.model

import java.net.URI

/**
 * The address of a server the user runs themselves (Home Assistant, Miniflux, Paperless…), as typed in
 * a connector template's setup sheet. See docs/design/integrations.md, "Server addresses".
 *
 * - `https://` is the default: a bare `rss.example.com` becomes `https://rss.example.com`.
 * - `http://` is accepted only for this phone (loopback) or the local network ([LocalNetwork]), and then
 *   with a warning: anyone on that network could read the key.
 * - Any other scheme, a user name or password in the address, a query or a fragment is refused.
 *
 * A path is kept (a server behind a reverse proxy at `https://home.example.com/miniflux`), without
 * its trailing slash, so a template can append its own API path.
 */
object ServiceAddress {
    /** [url] is the cleaned address when it can be used; [error] says why not; [warning] goes with a usable http address. */
    data class Checked(val url: String?, val error: String?, val warning: String?) {
        val ok: Boolean get() = url != null
    }

    const val LAN_HTTP_WARNING =
        "Plain http:// is only acceptable on your own network: anyone on it could read the key. Use https:// if your server has it."

    fun check(input: String): Checked {
        val raw = input.trim()
        if (raw.isEmpty()) return fail("Enter your server's address.")
        if (raw.any { it.isWhitespace() || it.isISOControl() }) return fail("An address can't contain spaces.")
        val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://").find(raw)?.groupValues?.get(1)?.lowercase()
        if (scheme == null && Regex("^[A-Za-z][A-Za-z0-9+.-]*:(?!\\d)").containsMatchIn(raw)) {
            return fail("Use an https:// address.")
        }
        val text = if (scheme == null) "https://$raw" else raw
        if (scheme != null && scheme != "https" && scheme != "http") return fail("Use an https:// address; $scheme:// isn't supported.")
        val uri = runCatching { URI(text) }.getOrNull() ?: return fail("That address can't be read. It should look like https://server.example.com.")
        if (uri.rawUserInfo != null) return fail("Don't put a user name or password in the address; use the fields below.")
        if (uri.rawQuery != null || uri.rawFragment != null) return fail("Leave out anything after ? or #.")
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return fail("That address has no server name.")
        if (uri.port != -1 && uri.port !in 1..65535) return fail("That port isn't valid.")
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val clean = "${uri.scheme.lowercase()}://${host.lowercase()}$port$path"
        if (uri.scheme.equals("http", ignoreCase = true)) {
            return if (LocalNetwork.isLoopback(host) || LocalNetwork.isLocalHost(host)) Checked(clean, null, LAN_HTTP_WARNING)
            else fail("Plain http:// only works for a server on this phone or your home network. Use https://.")
        }
        return Checked(clean, null, null)
    }

    /** [template] with `{host}` replaced by the checked address, e.g. `{host}/v1` → `https://rss.example.com/v1`. */
    fun fill(template: String, address: String): String = template.replace(HOST, address.trimEnd('/'))

    /** The placeholder a template's address starts with: the user's server, scheme included. */
    const val HOST = "{host}"

    private fun fail(message: String) = Checked(null, message, null)
}
