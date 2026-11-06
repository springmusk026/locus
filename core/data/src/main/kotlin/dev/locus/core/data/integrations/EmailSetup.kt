package dev.locus.core.data.integrations

import dev.locus.core.agent.integrations.EmailAccount
import dev.locus.core.agent.integrations.EmailAutoconfig
import dev.locus.core.agent.integrations.EmailCheck
import dev.locus.core.agent.integrations.EmailConfig
import dev.locus.core.agent.integrations.EmailConfigException
import dev.locus.core.agent.integrations.EmailConnectionTest
import dev.locus.core.agent.integrations.EmailForm
import dev.locus.core.agent.integrations.EmailPresets
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.datastore.SecretStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/** What is saved now: the fields (no password), or why the saved value can't be read. */
data class SavedEmail(val form: EmailForm?, val problem: String?)

/**
 * Settings → Integrations → Email (docs/design/email-setup.md). The account is one vault value in the
 * form [EmailConfig.parse] reads, so the email tools read it unchanged. The saved password never
 * comes back to the UI: a blank password field means "keep the saved one" for Test and Save alike.
 */
@Singleton
class EmailSetup @Inject constructor(
    private val http: HttpClient,
    private val secrets: SecretStore,
) {
    private val name = SecretStore.connectorSecret(Integrations.email.secret)

    private suspend fun savedAccount(): EmailAccount? = secrets.get(name)?.let { runCatching { EmailConfig.parse(it) }.getOrNull() }

    /** Null when nothing is saved. */
    suspend fun saved(): SavedEmail? {
        val value = secrets.get(name) ?: return null
        return try {
            SavedEmail(EmailForm.of(EmailConfig.parse(value)), null)
        } catch (e: EmailConfigException) {
            SavedEmail(null, e.message)
        }
    }

    /** Whether a readable account is saved, so its password can stand in for a blank field. */
    suspend fun hasSavedPassword(): Boolean = savedAccount() != null

    /** The account [form] describes, exactly as the email tools will parse it. Throws with a message to show. */
    private suspend fun account(form: EmailForm): Pair<String, EmailAccount> {
        val password = form.password.takeIf { it.isNotBlank() }?.let(form::cleanPassword) ?: savedAccount()?.password
            ?: throw IllegalArgumentException("Enter the password first.")
        val value = form.toConfig(password)
        return value to EmailConfig.parse(value)
    }

    /** Signs in to both servers without sending or reading anything. */
    suspend fun test(form: EmailForm): EmailCheck {
        val (_, account) = account(form)
        return EmailConnectionTest.run(account)
    }

    suspend fun save(form: EmailForm) {
        val (value, _) = account(form)
        secrets.put(name, value)
    }

    suspend fun remove() = secrets.put(name, null)

    /**
     * Asks Thunderbird's settings list about [address]'s domain (only the domain is sent). Only on
     * the user's tap: Locus makes no network call for setup the user didn't ask for. Null when the
     * list has nothing for the domain.
     */
    suspend fun lookUp(address: String): EmailAutoconfig.Found? {
        val domain = EmailPresets.domainOf(address) ?: throw IllegalArgumentException("Enter the whole address first.")
        val response = try {
            withTimeout(LOOKUP_TIMEOUT_MS) { http.get(EmailAutoconfig.url(domain)) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw IllegalStateException("Thunderbird's settings list didn't answer. Try again, or enter the settings yourself.")
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Couldn't reach Thunderbird's settings list. Check that the phone is online.")
        }
        if (response.status.value == 404) return null
        if (!response.status.isSuccess()) throw IllegalStateException("Thunderbird's settings list answered ${response.status.value}. Try again later.")
        return EmailAutoconfig.parse(response.bodyAsText().take(MAX_LOOKUP_CHARS), address)
    }

    private companion object {
        const val LOOKUP_TIMEOUT_MS = 15_000L
        const val MAX_LOOKUP_CHARS = 200_000
    }
}
