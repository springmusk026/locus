package dev.locus.core.agent.integrations

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * The user's own "reach me" channels: ntfy and Telegram. A message goes to every channel the user
 * has set up, so `notify_me` stays one tool whatever they connect (the tool list is fixed per chat).
 * Each channel only ever reaches the user, so background tasks may use it.
 */
object ReachMe {
    /** One channel's result; [error] is null when it was delivered. Errors never contain the channel's secret. */
    data class Outcome(val channel: String, val error: String?)

    /** Sends to every configured channel; an empty list means none is set up. */
    suspend fun send(
        http: HttpClient,
        secrets: SecretResolver,
        title: String?,
        message: String,
        priority: Int? = null,
        click: String? = null,
        pause: suspend (Long) -> Unit = Telegram.realPause,
    ): List<Outcome> {
        val outcomes = mutableListOf<Outcome>()
        secrets.resolve(Integrations.ntfy.secret)?.let(Ntfy::parse)?.let { target ->
            outcomes += attempt("ntfy", listOfNotNull(target.url, target.token)) { Ntfy.publish(http, target, title, message, priority, click) }
        }
        secrets.resolve(Integrations.telegram.secret)?.let(Telegram::parse)?.let { target ->
            outcomes += attempt("Telegram", listOf(target.token)) {
                Telegram.send(http, target, title, message, silent = priority != null && priority <= 2, click = click, pause = pause)
            }
        }
        return outcomes
    }

    private suspend fun attempt(channel: String, secrets: List<String>, block: suspend () -> Unit): Outcome = try {
        block()
        Outcome(channel, null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        var reason = e.message ?: e::class.simpleName.orEmpty()
        secrets.filter { it.length >= 6 }.forEach { reason = reason.replace(it, "<secret>") }
        Outcome(channel, Telegram.scrub(reason, null).take(300))
    }
}

internal class NotifyMe(private val http: HttpClient, private val secrets: SecretResolver) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "notify_me",
        "Send the user a notification on their own channels (ntfy push and/or a Telegram bot, whichever they set up; reaches their other devices too). " +
            "Use for results of long or background work, not for chatting. Markdown is allowed; keep it short.",
        ObjectSchema()
            .string("message", "The notification text.")
            .string("title", "Short title.", required = false)
            .integer("priority", "1 (min) to 5 (urgent). Default 3.")
            .string("click", "A link to open when the notification is tapped.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Notify you: ${args.str("title") ?: args.str("message")?.take(60)}"

    /** Only ever reaches the user's own channels, so background (read-only) tasks may use it. */
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val outcomes = ReachMe.send(http, secrets, args.str("title"), args.str("message").orEmpty(), args.int("priority"), args.str("click"))
        if (outcomes.isEmpty()) {
            throw ToolException("Notifications need a channel. Ask the user to set up ntfy or Telegram in Settings → Integrations.")
        }
        val sent = outcomes.filter { it.error == null }.map { it.channel }
        val failed = outcomes.filter { it.error != null }.joinToString(" ") { "${it.channel} failed: ${it.error}" }
        if (sent.isEmpty()) throw ToolException(failed)
        return ToolResult("Sent to ${sent.joinToString(" and ")}." + if (failed.isNotEmpty()) " $failed" else "")
    }
}
