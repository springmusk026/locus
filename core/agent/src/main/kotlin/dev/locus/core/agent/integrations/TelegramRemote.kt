package dev.locus.core.agent.integrations

import dev.locus.core.agent.ApprovalRequest
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.SecureRandom
import java.util.Base64

/**
 * The Telegram remote channel (docs/design/telegram-remote.md): approvals answered by inline button,
 * and, in remote mode, messages to the bot as tasks. Locus has no server, so it reads the bot's updates
 * with `getUpdates` long polling, and only while something needs it (an approval sent to the chat, or
 * remote mode on).
 *
 * Everything that comes from Telegram is untrusted: an update counts only when it comes from the
 * configured private chat ([fromOwner]), a button only with a live single-use nonce ([ApprovalDesk]),
 * and a message only after remote mode was switched on, at a limited rate ([Inbox]).
 */
object TelegramRemote {
    /** Long-poll length. Telegram holds the request open up to this long when there's nothing new. */
    const val POLL_TIMEOUT_S = 25

    /** At most this much of a message is used; Telegram's own limit is 4,096 characters. */
    const val MAX_TEXT = 4_000

    /** What remote mode switches itself off after, at most. */
    const val MAX_REMOTE_MS = 8 * 60 * 60 * 1000L

    /** A bot update, reduced to what Locus uses. [id] is the update_id. */
    sealed interface Update {
        val id: Long

        /** A text message. [date] is Telegram's (Unix seconds). */
        data class Text(override val id: Long, val chatId: Long, val fromId: Long?, val date: Long, val text: String) : Update

        /** A press on an inline button. [chatId] is the chat of the message the button is on. */
        data class Press(override val id: Long, val queryId: String, val chatId: Long?, val fromId: Long, val data: String?) : Update

        /** Anything else: only its id matters (to move the offset past it). */
        data class Other(override val id: Long) : Update
    }

    /** Parses getUpdates' `result`; malformed entries without an update_id are dropped. */
    fun parseUpdates(result: JsonElement?): List<Update> = (result as? JsonArray).orEmpty().mapNotNull { element ->
        val u = element as? JsonObject ?: return@mapNotNull null
        val id = u.long("update_id") ?: return@mapNotNull null
        val message = u["message"] as? JsonObject
        val callback = u["callback_query"] as? JsonObject
        when {
            message != null -> {
                val chatId = (message["chat"] as? JsonObject)?.long("id")
                val text = message.text("text")
                if (chatId == null || text == null) Update.Other(id)
                else Update.Text(id, chatId, (message["from"] as? JsonObject)?.long("id"), message.long("date") ?: 0, text)
            }
            callback != null -> {
                val queryId = callback.text("id")
                val fromId = (callback["from"] as? JsonObject)?.long("id")
                if (queryId == null || fromId == null) Update.Other(id)
                else {
                    val chatId = ((callback["message"] as? JsonObject)?.get("chat") as? JsonObject)?.long("id")
                    Update.Press(id, queryId, chatId, fromId, callback.text("data"))
                }
            }
            else -> Update.Other(id)
        }
    }

    /**
     * The offset for the next getUpdates: one more than the highest update_id seen, which also
     * confirms everything up to it. Unchanged when there was nothing new.
     */
    fun nextOffset(current: Long?, updates: List<Update>): Long? {
        val highest = updates.maxOfOrNull { it.id } ?: return current
        return maxOf(current ?: Long.MIN_VALUE, highest + 1)
    }

    /** One long poll: messages and button presses only, after [offset] (null: whatever is pending). */
    suspend fun getUpdates(
        http: HttpClient,
        token: String,
        offset: Long?,
        timeoutS: Int = POLL_TIMEOUT_S,
        pause: suspend (Long) -> Unit = Telegram.realPause,
    ): List<Update> = parseUpdates(
        Telegram.call(
            http, token, "getUpdates",
            buildJsonObject {
                offset?.let { put("offset", it) }
                put("timeout", timeoutS)
                put("limit", 100)
                putJsonArray("allowed_updates") { add("message"); add("callback_query") }
            },
            pause,
        ),
    )

    /**
     * Whether an update comes from the user: the configured chat, and in it, the configured user.
     * Remote features need a private chat, whose id is the user's id, so both must equal it; a group
     * (negative id) never qualifies, since anyone in it could press a button.
     */
    fun fromOwner(target: Telegram.Target, chatId: Long?, fromId: Long?): Boolean =
        target.chatId > 0 && chatId == target.chatId && fromId == target.chatId

    /** Whether remote features can work with this chat (a private chat with the user). */
    fun privateChat(chatId: Long): Boolean = chatId > 0

    // ---- Approval buttons --------------------------------------------------------------------------

    private const val DATA_PREFIX = "lc1"
    private val DATA = Regex("^$DATA_PREFIX:([A-Za-z0-9_-]{22}):([ad])$")

    /** A parsed button: which approval ([nonce]) and the answer. */
    data class Choice(val nonce: String, val approve: Boolean)

    /** A fresh nonce: 128 random bits, base64url (22 characters). */
    fun newNonce(random: SecureRandom = SecureRandom()): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(random::nextBytes))

    /** The button's callback data (Telegram allows 1–64 bytes; this is 28). */
    fun callbackData(nonce: String, approve: Boolean) = "$DATA_PREFIX:$nonce:${if (approve) "a" else "d"}"

    /** Strictly parses [callbackData]; anything else is null. */
    fun parseChoice(data: String?): Choice? {
        val m = DATA.matchEntire(data ?: return null) ?: return null
        return Choice(m.groupValues[1], m.groupValues[2] == "a")
    }

    /**
     * The approvals sent to Telegram, by nonce. A nonce is answered at most once: a press takes it, and
     * so does the approval ending another way (answered in Locus, timed out, the task stopped), after
     * which its buttons do nothing.
     */
    class ApprovalDesk {
        /** One approval in the chat: its message, and whether it has buttons (false: "open Locus"). */
        data class Sent(val nonce: String, val callId: String, val messageId: Long?, val buttons: Boolean)

        sealed interface Outcome {
            /** A valid press: answer [callId] with [approve]. */
            data class Decide(val sent: Sent, val approve: Boolean) : Outcome

            /** Not from the configured chat and user: ignored. */
            data object Stranger : Outcome

            /** Not Locus's button data. */
            data object Malformed : Outcome

            /** A nonce that was answered, expired or never sent. */
            data object Expired : Outcome

            /** An approval that only a tap in Locus may answer (it was sent without buttons). */
            data object InAppOnly : Outcome
        }

        private val byNonce = LinkedHashMap<String, Sent>()

        @Synchronized
        fun add(sent: Sent) {
            byNonce[sent.nonce] = sent
        }

        @Synchronized
        fun has(callId: String): Boolean = byNonce.values.any { it.callId == callId }

        /** The call ids with an approval in the chat. */
        @Synchronized
        fun callIds(): Set<String> = byNonce.values.mapTo(mutableSetOf()) { it.callId }

        /** Whether any approval in the chat has buttons, so presses must be read. */
        @Synchronized
        fun waitingForPress(): Boolean = byNonce.values.any { it.buttons }

        /** Ends [callId]'s approval in the chat; its buttons stop working. Returns it, to edit its message. */
        @Synchronized
        fun expire(callId: String): Sent? {
            val entry = byNonce.entries.firstOrNull { it.value.callId == callId } ?: return null
            byNonce.remove(entry.key)
            return entry.value
        }

        /** Checks a press; a valid one takes its nonce, so it can't be used twice. */
        @Synchronized
        fun press(target: Telegram.Target, press: Update.Press): Outcome {
            if (!fromOwner(target, press.chatId, press.fromId)) return Outcome.Stranger
            val choice = parseChoice(press.data) ?: return Outcome.Malformed
            val sent = byNonce[choice.nonce] ?: return Outcome.Expired
            if (!sent.buttons) return Outcome.InAppOnly
            byNonce.remove(choice.nonce)
            return Outcome.Decide(sent, choice.approve)
        }
    }

    /** The approval's heading in the chat: "Locus wants to…" or "Researcher #2 wants to…". */
    fun approvalTitle(request: ApprovalRequest) = "${request.origin?.label ?: "Locus"} asks to run ${request.toolName}"

    /**
     * Sends [request] to the chat: with Allow and Deny buttons carrying [nonce], or, when it may only be
     * answered in Locus ([ApprovalRequest.inAppOnly]), without buttons and saying so. Returns the
     * message id, to edit it once the approval ends.
     */
    suspend fun sendApproval(
        http: HttpClient,
        target: Telegram.Target,
        request: ApprovalRequest,
        nonce: String,
        pause: suspend (Long) -> Unit = Telegram.realPause,
    ): Long? {
        val inApp = request.inAppOnly
        val text = buildString {
            append("<b>").append(Telegram.escape(approvalTitle(request))).append("</b>\n")
            append(Telegram.escape(request.summary.take(1_500)))
            if (inApp != null) {
                append("\n\n").append(Telegram.escape("Open Locus to approve. This one can't be approved from Telegram. $inApp"))
            }
        }
        val result = Telegram.call(
            http, target.token, "sendMessage",
            buildJsonObject {
                put("chat_id", target.chatId)
                put("text", text)
                put("parse_mode", "HTML")
                putJsonObject("link_preview_options") { put("is_disabled", true) }
                if (inApp == null) {
                    putJsonObject("reply_markup") {
                        putJsonArray("inline_keyboard") {
                            add(
                                JsonArray(
                                    listOf(
                                        button("Deny", callbackData(nonce, approve = false)),
                                        button("Allow", callbackData(nonce, approve = true)),
                                    ),
                                ),
                            )
                        }
                    }
                }
            },
            pause,
        )
        return (result as? JsonObject)?.long("message_id")
    }

    private fun button(label: String, data: String) = buildJsonObject {
        put("text", label)
        put("callback_data", data)
    }

    /** Stops the button's spinner in the user's app, with a short toast ([text], at most 200 characters). */
    suspend fun answerPress(http: HttpClient, token: String, queryId: String, text: String?, pause: suspend (Long) -> Unit = Telegram.realPause) {
        Telegram.call(
            http, token, "answerCallbackQuery",
            buildJsonObject {
                put("callback_query_id", queryId)
                text?.let { put("text", it.take(200)) }
            },
            pause,
        )
    }

    /** Rewrites an approval's message with how it ended; with no reply_markup the buttons go away. */
    suspend fun closeApproval(
        http: HttpClient,
        target: Telegram.Target,
        messageId: Long,
        request: ApprovalRequest?,
        outcome: String,
        pause: suspend (Long) -> Unit = Telegram.realPause,
    ) {
        val text = buildString {
            request?.let { append("<b>").append(Telegram.escape(approvalTitle(it))).append("</b>\n").append(Telegram.escape(it.summary.take(1_500))).append("\n\n") }
            append("<i>").append(Telegram.escape(outcome)).append("</i>")
        }
        Telegram.call(
            http, target.token, "editMessageText",
            buildJsonObject {
                put("chat_id", target.chatId)
                put("message_id", messageId)
                put("text", text)
                put("parse_mode", "HTML")
                putJsonObject("link_preview_options") { put("is_disabled", true) }
            },
            pause,
        )
    }

    // ---- Remote mode ---------------------------------------------------------------------------------

    /**
     * At most [max] messages in any [windowMs]; the rest are dropped. A message that's dropped says so
     * only once per window, so a flood can't make Locus flood back.
     */
    class RateLimit(private val max: Int = 6, private val windowMs: Long = 60_000) {
        private val times = ArrayDeque<Long>()
        private var warnedAt = Long.MIN_VALUE

        sealed interface Verdict {
            data object Pass : Verdict
            data class Limited(val warn: Boolean) : Verdict
        }

        @Synchronized
        fun check(now: Long): Verdict {
            while (times.isNotEmpty() && times.first() <= now - windowMs) times.removeFirst()
            if (times.size >= max) {
                val warn = warnedAt <= now - windowMs
                if (warn) warnedAt = now
                return Verdict.Limited(warn)
            }
            times.addLast(now)
            return Verdict.Pass
        }
    }

    /** What to do with a text message to the bot. */
    sealed interface Inbound {
        /** A task, or a note for the running one. */
        data class Task(val text: String) : Inbound

        /** "/off": switch remote mode off. */
        data object TurnOff : Inbound

        /** From the user, but remote mode is off: tell them, do nothing. */
        data object RemoteOff : Inbound

        /** Too many messages: dropped; [warn] says whether to tell the user (once a window). */
        data class Limited(val warn: Boolean) : Inbound

        /** Dropped silently: someone else, sent before remote mode was switched on, or empty. */
        data class Ignore(val why: String) : Inbound
    }

    /**
     * Decides about one text message. Only the configured chat and user count; messages dated before
     * remote mode was switched on ([sinceMs]) are old ones Telegram kept (up to 24 hours) and never run.
     * "/off" works whatever the rate, so the user can always stop it.
     */
    fun inbound(
        message: Update.Text,
        target: Telegram.Target,
        remoteOn: Boolean,
        sinceMs: Long,
        limit: RateLimit,
        nowMs: Long,
    ): Inbound {
        if (!fromOwner(target, message.chatId, message.fromId)) return Inbound.Ignore("not the configured chat")
        if (message.date * 1000 < sinceMs) return Inbound.Ignore("sent before remote mode was on")
        val text = message.text.trim()
        if (text.isEmpty()) return Inbound.Ignore("empty")
        val command = text.substringBefore(' ').substringBefore('@').lowercase()
        if (command == "/off" || command == "/stop_remote") return Inbound.TurnOff
        if (!remoteOn) return Inbound.RemoteOff
        if (command == "/start") return Inbound.Ignore("bot start")
        return when (val v = limit.check(nowMs)) {
            RateLimit.Verdict.Pass -> Inbound.Task(text.take(MAX_TEXT))
            is RateLimit.Verdict.Limited -> Inbound.Limited(v.warn)
        }
    }

    /** What the channel needs from the app: the remote settings, and what to do with answers and tasks. */
    interface Host {
        /** Remote mode's state now: on (and not timed out), and when it was switched on (epoch ms). */
        suspend fun remote(): Pair<Boolean, Long>

        /** The user pressed Allow or Deny on [callId]'s approval; false when it had already ended. */
        fun answer(callId: String, approve: Boolean): Boolean

        /** A message from the user in remote mode: start or steer the Telegram chat's task. Returns the reply. */
        suspend fun task(text: String): String

        /** "/off": switch remote mode off. */
        suspend fun turnOff()

        fun now(): Long
    }

    /**
     * One bot's remote channel: approvals sent to the chat ([offer], [withdraw]) and the updates read
     * back ([poll]). It keeps the getUpdates offset; the app runs [poll] in a loop only while
     * [needsPolling] (buttons waiting) or remote mode is on.
     */
    class Channel(
        private val http: HttpClient,
        private val host: Host,
        private val desk: ApprovalDesk = ApprovalDesk(),
        private val limit: RateLimit = RateLimit(),
        private val random: SecureRandom = SecureRandom(),
        private val pause: suspend (Long) -> Unit = Telegram.realPause,
    ) {
        /** The next getUpdates offset; null until the first poll. */
        var offset: Long? = null
            private set

        /** The requests in the chat, by call id, to rewrite their messages when they end. */
        private val requests = java.util.concurrent.ConcurrentHashMap<String, ApprovalRequest>()

        fun needsPolling(): Boolean = desk.waitingForPress()

        /** The call ids with an approval in the chat. */
        fun offered(): Set<String> = desk.callIds()

        /** Sends [request] to the chat (with buttons unless it's in-app only). False when it was already there. */
        suspend fun offer(target: Target, request: ApprovalRequest): Boolean {
            if (desk.has(request.callId)) return false
            val nonce = newNonce(random)
            // Registered before sending, so a press that comes back fast finds it; the message id follows.
            val buttons = request.inAppOnly == null
            desk.add(ApprovalDesk.Sent(nonce, request.callId, null, buttons))
            requests[request.callId] = request
            val messageId = try {
                sendApproval(http, target.target, request, nonce, pause)
            } catch (e: Exception) {
                desk.expire(request.callId)
                requests.remove(request.callId)
                endedWhileSending.remove(request.callId)
                throw e
            }
            // Still offered (not answered meanwhile): keep the message id with it.
            if (desk.expire(request.callId) != null) {
                desk.add(ApprovalDesk.Sent(nonce, request.callId, messageId, buttons))
            } else {
                // It ended while the message was on its way, so nothing could edit it then: close it now,
                // or the chat keeps buttons that only answer "This request has ended."
                val outcome = endedWhileSending.remove(request.callId) ?: "This request has ended."
                if (messageId != null) closeApproval(http, target.target, messageId, request, outcome, pause)
            }
            return true
        }

        /** [callId]'s approval ended in Locus (answered there, timed out, the task stopped): its buttons stop working. */
        suspend fun withdraw(target: Target, callId: String, outcome: String) {
            val sent = desk.expire(callId) ?: return
            val request = requests.remove(callId)
            closeOrRemember(target.target, sent, request, outcome)
        }

        /**
         * How each approval that ended before its message was sent ended, by call id; [offer] closes the
         * message with it once the send returns.
         */
        private val endedWhileSending = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** Closes [sent]'s message with [outcome], or, while it is still being sent, leaves that to [offer]. */
        private suspend fun closeOrRemember(target: Telegram.Target, sent: ApprovalDesk.Sent, request: ApprovalRequest?, outcome: String) {
            val messageId = sent.messageId
            if (messageId == null) endedWhileSending[sent.callId] = outcome else closeApproval(http, target, messageId, request, outcome, pause)
        }

        /** One long poll, then each update handled. The offset moves past them first, so none is handled twice. */
        suspend fun poll(target: Target, timeoutS: Int = POLL_TIMEOUT_S) {
            val updates = getUpdates(http, target.target.token, offset, timeoutS, pause)
            offset = nextOffset(offset, updates)
            for (update in updates) {
                try {
                    handle(target, update)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // One update that fails (Telegram refused a reply) doesn't stop the rest.
                }
            }
        }

        private suspend fun handle(target: Target, update: Update) {
            val t = target.target
            when (update) {
                is Update.Press -> when (val outcome = desk.press(t, update)) {
                    is ApprovalDesk.Outcome.Decide -> {
                        val applied = host.answer(outcome.sent.callId, outcome.approve)
                        val said = when {
                            !applied -> "This request had already ended."
                            outcome.approve -> "Allowed from Telegram."
                            else -> "Denied from Telegram."
                        }
                        answerPress(http, t.token, update.queryId, said, pause)
                        val request = requests.remove(outcome.sent.callId)
                        closeOrRemember(t, outcome.sent, request, said)
                    }
                    ApprovalDesk.Outcome.Expired -> answerPress(http, t.token, update.queryId, "This request has ended.", pause)
                    ApprovalDesk.Outcome.InAppOnly -> answerPress(http, t.token, update.queryId, "Open Locus to approve this one.", pause)
                    // Someone else's press, or not Locus's button: stop their spinner, say nothing.
                    ApprovalDesk.Outcome.Stranger, ApprovalDesk.Outcome.Malformed -> answerPress(http, t.token, update.queryId, null, pause)
                }
                is Update.Text -> {
                    val (on, since) = host.remote()
                    when (val verdict = inbound(update, t, on, since, limit, host.now())) {
                        is Inbound.Task -> reply(t, host.task(verdict.text))
                        Inbound.TurnOff -> {
                            host.turnOff()
                            reply(t, "Remote mode is off. Turn it on again in Locus → Settings → Integrations → Telegram.")
                        }
                        Inbound.RemoteOff -> if (limit.check(host.now()) == RateLimit.Verdict.Pass) {
                            reply(t, "Remote mode is off, so Locus didn't read that. Turn it on in Locus → Settings → Integrations → Telegram.")
                        }
                        is Inbound.Limited -> if (verdict.warn) reply(t, "Too many messages. Locus skipped some; wait a minute and send again.")
                        is Inbound.Ignore -> Unit
                    }
                }
                is Update.Other -> Unit
            }
        }

        private suspend fun reply(t: Telegram.Target, text: String) = Telegram.send(http, t, null, text, pause = pause)
    }

    /** The configured bot and chat, checked for remote use (a private chat). */
    class Target private constructor(val target: Telegram.Target) {
        companion object {
            fun of(target: Telegram.Target?): Target? = target?.takeIf { privateChat(it.chatId) }?.let(::Target)
        }
    }

    /** When remote mode switched on at [now] for [durationMs] ends (at most [MAX_REMOTE_MS]). */
    fun until(now: Long, durationMs: Long): Long = now + durationMs.coerceIn(60_000, MAX_REMOTE_MS)

    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
