package dev.locus.core.data.connectors

import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.home.ConnectorKeys
import dev.locus.core.data.R
import dev.locus.core.linux.EngineText
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

/** What Test connection found: it signed in, or why not, in words for the user (only shown). Never contains a key. */
sealed interface ConnectionCheck {
    data class Passed(val detail: EngineText) : ConnectionCheck

    data class Failed(val message: EngineText) : ConnectionCheck
}

/** A setup step that failed for a reason the user can fix, said in [text]; [message] is the same in English, for logs. */
class ServiceSetupException(val text: EngineText, message: String) : IllegalArgumentException(message)

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
            ConnectionCheck.Failed(networkMessage(e, host) { scrub(it, forms) })
        }
    }

    /**
     * Google Reader ClientLogin: the `Auth` token for [username] and [password] at [apiBase]
     * (`…/api/greader.php`). Throws [ServiceSetupException] with a message for the user.
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
            throw ServiceSetupException(networkMessage(e, host) { scrub(it, listOf(password)) }, "Couldn't reach $host")
        }
        val code = response.status.value
        if (code == 401 || code == 403) throw ServiceSetupException(EngineText.Res(R.string.data_service_login_refused, host), "$host refused that user name or API password.")
        if (code == 404) throw ServiceSetupException(EngineText.Res(R.string.data_service_login_no_api), "No Google Reader API at that address.")
        if (code !in 200..299) throw ServiceSetupException(EngineText.Res(R.string.data_service_login_http, host, code), "$host answered HTTP $code to the sign-in.")
        val token = response.bodyAsText().lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Auth=") }?.removePrefix("Auth=")
        if (token.isNullOrBlank() || token.any { it.isWhitespace() || it.isISOControl() }) {
            throw ServiceSetupException(EngineText.Res(R.string.data_service_login_no_token, host), "$host didn't return a sign-in token.")
        }
        return token
    }

    private suspend fun judge(response: HttpResponse, host: String): ConnectionCheck {
        val code = response.status.value
        return when {
            code in 200..299 -> ConnectionCheck.Passed(EngineText.Res(R.string.data_service_passed, host, code))
            code in 300..399 -> ConnectionCheck.Failed(
                response.headers["Location"]?.let(::hostOrPath)?.let { EngineText.Res(R.string.data_service_redirect, host, it) }
                    ?: EngineText.Res(R.string.data_service_redirect_elsewhere, host),
            )
            code == 401 -> ConnectionCheck.Failed(EngineText.Res(R.string.data_service_refused, host))
            code == 403 -> ConnectionCheck.Failed(EngineText.Res(R.string.data_service_forbidden, host))
            code == 404 -> ConnectionCheck.Failed(EngineText.Res(R.string.data_service_not_found))
            code >= 500 -> ConnectionCheck.Failed(EngineText.Res(R.string.data_service_server_error, host, code))
            else -> ConnectionCheck.Failed(EngineText.Res(R.string.data_service_http, host, code))
        }
    }

    /** Why [e] stopped the request; [scrub] takes any key out of the exception's own words. */
    private fun networkMessage(e: Exception, host: String, scrub: (String) -> String): EngineText = when (e) {
        is UnknownHostException -> EngineText.Res(R.string.data_service_unknown_host, host)
        is SSLException -> e.message?.let { EngineText.Res(R.string.data_service_tls, host, scrub(it)) }
            ?: EngineText.Res(R.string.data_service_tls_untrusted, host)
        is ConnectException -> EngineText.Res(R.string.data_service_connect, host)
        is SocketTimeoutException -> EngineText.Res(R.string.data_service_timeout, host)
        else -> if (e::class.simpleName.orEmpty().contains("Timeout")) EngineText.Res(R.string.data_service_timeout, host)
        else EngineText.Res(R.string.data_service_unreachable, host, scrub(e.message ?: e::class.simpleName.orEmpty()))
    }

    private fun hostOrPath(location: String): String = runCatching { URI(location) }.getOrNull()?.let { u ->
        if (u.host != null) u.host + (if (u.port != -1) ":${u.port}" else "") + u.rawPath.orEmpty() else u.rawPath
    } ?: location.take(80)

    private fun hostOf(url: String) = runCatching { URI(url).let { it.host + if (it.port != -1) ":${it.port}" else "" } }.getOrDefault(url)

    private fun scrub(text: String, forms: List<String>) = forms.filter { it.isNotEmpty() }.fold(text) { acc, f -> acc.replace(f, "[secret]") }
}
