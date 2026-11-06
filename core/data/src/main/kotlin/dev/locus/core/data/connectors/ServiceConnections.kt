package dev.locus.core.data.connectors

import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.home.ConnectorKeys
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** What Test connection found: it signed in, or why not, in words for the user. Never contains a key. */
sealed interface ConnectionCheck {
    data class Passed(val detail: String) : ConnectionCheck

    data class Failed(val message: String) : ConnectionCheck
}

/**
 * The setup sheets' network calls for a self-hosted service: Test connection (one harmless GET with the
 * key, nothing else) and the FreshRSS sign-in exchange. Redirects aren't followed, so a key is never
 * sent anywhere but the address the user typed. See docs/design/integrations.md, "Test connection".
 */
class ServiceConnections(http: HttpClient) {
    private val http = http.config { followRedirects = false; expectSuccess = false }

    /** GETs [url] with [auth] carrying [secret], and says whether the server took it. */
    suspend fun test(url: String, auth: Connector.Auth, secret: String?): ConnectionCheck {
        val host = hostOf(url)
        val forms = secret?.let { ConnectorKeys.secretForms(auth, it) }.orEmpty()
        return try {
            val response = http.get(url) {
                if (secret != null) {
                    ConnectorKeys.header(auth, secret)?.let { (name, value) -> header(name, value) }
                }
            }
            judge(response, host)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionCheck.Failed(scrub(networkMessage(e, host), forms))
        }
    }

    /**
     * Google Reader ClientLogin: the `Auth` token for [username] and [password] at [apiBase]
     * (`…/api/greader.php`). Throws [IllegalArgumentException] with a message for the user.
     */
    suspend fun googleReaderLogin(apiBase: String, username: String, password: String): String {
        val host = hostOf(apiBase)
        val response = try {
            http.submitForm(apiBase.trimEnd('/') + "/accounts/ClientLogin", parameters {
                append("Email", username.trim())
                append("Passwd", password)
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException(scrub(networkMessage(e, host), listOf(password)))
        }
        val code = response.status.value
        if (code == 401 || code == 403) throw IllegalArgumentException("$host refused that user name or API password. Check the API password under Profile → API management.")
        if (code == 404) throw IllegalArgumentException("No Google Reader API at that address. Check it, and that the API is enabled in FreshRSS (Administration → Authentication).")
        if (code !in 200..299) throw IllegalArgumentException("$host answered HTTP $code to the sign-in.")
        val token = response.bodyAsText().lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Auth=") }?.removePrefix("Auth=")
        require(!token.isNullOrBlank() && token.none { it.isWhitespace() || it.isISOControl() }) { "$host didn't return a sign-in token. Is the API enabled?" }
        return token
    }

    private suspend fun judge(response: HttpResponse, host: String): ConnectionCheck {
        val code = response.status.value
        return when {
            code in 200..299 -> ConnectionCheck.Passed("Reached $host and it accepted the key (HTTP $code).")
            code in 300..399 -> ConnectionCheck.Failed(
                "$host redirects to ${response.headers["Location"]?.let(::hostOrPath) ?: "another address"}. Locus doesn't follow redirects with a key: use the address it redirects to.",
            )
            code == 401 -> ConnectionCheck.Failed("$host refused the key (HTTP 401). Check it was copied whole and hasn't expired.")
            code == 403 -> ConnectionCheck.Failed("$host accepted the key but it lacks permission for this (HTTP 403). Check the key's permissions.")
            code == 404 -> ConnectionCheck.Failed("Nothing found at that address (HTTP 404). Check it, including any sub-path like /miniflux.")
            code >= 500 -> ConnectionCheck.Failed("$host had an error (HTTP $code). Try again later.")
            else -> ConnectionCheck.Failed("$host answered HTTP $code.")
        }
    }

    private fun networkMessage(e: Exception, host: String): String = when (e) {
        is UnknownHostException -> "Couldn't find $host. Check the address, and that this phone can reach it (same network or VPN)."
        is SSLException -> "The secure connection to $host failed: ${e.message ?: "its certificate isn't trusted"}. A self-signed certificate isn't accepted."
        is ConnectException -> "Couldn't connect to $host. Is the server running, and is the port right?"
        is SocketTimeoutException -> "$host didn't answer in time."
        else -> if (e::class.simpleName.orEmpty().contains("Timeout")) "$host didn't answer in time." else "Couldn't reach $host: ${e.message ?: e::class.simpleName}"
    }

    private fun hostOrPath(location: String): String = runCatching { URI(location) }.getOrNull()?.let { u ->
        if (u.host != null) u.host + (if (u.port != -1) ":${u.port}" else "") + u.rawPath.orEmpty() else u.rawPath
    } ?: location.take(80)

    private fun hostOf(url: String) = runCatching { URI(url).let { it.host + if (it.port != -1) ":${it.port}" else "" } }.getOrDefault(url)

    private fun scrub(text: String, forms: List<String>) = forms.filter { it.isNotEmpty() }.fold(text) { acc, f -> acc.replace(f, "[secret]") }
}
