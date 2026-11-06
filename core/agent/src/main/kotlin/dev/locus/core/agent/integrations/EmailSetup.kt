package dev.locus.core.agent.integrations

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.Properties
import javax.net.ssl.SSLException
import javax.xml.parsers.DocumentBuilderFactory

/*
 * Settings → Integrations → Email as a form (docs/design/email-setup.md): the fields, the bundled
 * provider presets, the stored value they turn into, the optional settings lookup, and the connection
 * test. The stored value is the JSON form [EmailConfig.parse] already reads, so the email tools are
 * unchanged; saved one-line values load into the same fields.
 */

/** A provider whose servers Locus knows without asking anyone (verified against its docs; see [EmailPresets]). */
data class EmailPreset(
    val id: String,
    val name: String,
    val domains: Set<String>,
    val imap: MailServer,
    val smtp: MailServer,
    /** Where the user makes the password this form needs, and the link's label. */
    val passwordUrl: String,
    val passwordLabel: String,
    /** What the password field asks for: most providers need an app password. */
    val passwordHint: String,
    /** The password field's label: "App password", or "Password" where the usual one works. */
    val fieldLabel: String = "App password",
    /** A step the provider needs before IMAP works, if any. */
    val note: String? = null,
    /** Gmail shows app passwords in groups of four; the spaces aren't part of it. */
    val stripSpaces: Boolean = false,
)

/**
 * The bundled presets, matched on the address's domain. Each host, port and security value comes from
 * the provider's own documentation (cited per entry, checked 2026-11), not from a third-party list.
 * Presets fill the server fields; the user can still change every one of them.
 */
object EmailPresets {
    private fun ssl(host: String, port: Int) = MailServer(host, port, MailSecurity.SSL)
    private fun starttls(host: String, port: Int) = MailServer(host, port, MailSecurity.STARTTLS)

    // https://developers.google.com/workspace/gmail/imap/imap-smtp
    val gmail = EmailPreset(
        "gmail", "Gmail", setOf("gmail.com", "googlemail.com"),
        ssl("imap.gmail.com", 993), ssl("smtp.gmail.com", 465),
        passwordUrl = "https://myaccount.google.com/apppasswords",
        passwordLabel = "Create a Gmail app password",
        passwordHint = "A 16-letter app password. It needs 2-Step Verification on the account.",
        stripSpaces = true,
    )

    // https://www.fastmail.help/hc/en-us/articles/1500000278342-Server-names-and-ports
    val fastmail = EmailPreset(
        "fastmail", "Fastmail", setOf("fastmail.com", "fastmail.fm"),
        ssl("imap.fastmail.com", 993), ssl("smtp.fastmail.com", 465),
        // https://www.fastmail.help/hc/en-us/articles/360058752854-App-passwords
        passwordUrl = "https://app.fastmail.com/settings/security",
        passwordLabel = "Create a Fastmail app password",
        passwordHint = "An app password with IMAP and SMTP access. Your usual password won't work.",
    )

    // https://support.apple.com/en-us/102525 (SMTP: port 587, STARTTLS if SSL fails, which it does on 587)
    val icloud = EmailPreset(
        "icloud", "iCloud Mail", setOf("icloud.com", "me.com", "mac.com"),
        ssl("imap.mail.me.com", 993), starttls("smtp.mail.me.com", 587),
        // https://support.apple.com/en-us/102654
        passwordUrl = "https://account.apple.com",
        passwordLabel = "Create an iCloud app-specific password",
        passwordHint = "An app-specific password from your Apple Account (Sign-In and Security).",
    )

    // https://help.yahoo.com/kb/SLN4075.html
    val yahoo = EmailPreset(
        "yahoo", "Yahoo Mail",
        setOf("yahoo.com", "ymail.com", "rocketmail.com", "yahoo.co.uk", "yahoo.ca", "yahoo.com.au", "yahoo.co.in", "yahoo.fr", "yahoo.de", "yahoo.es", "yahoo.it"),
        ssl("imap.mail.yahoo.com", 993), ssl("smtp.mail.yahoo.com", 465),
        // https://help.yahoo.com/kb/SLN15241.html
        passwordUrl = "https://login.yahoo.com/account/security",
        passwordLabel = "Create a Yahoo app password",
        passwordHint = "An app password from Yahoo's Account Security page.",
    )

    // https://www.zoho.com/mail/help/imap-access.html (personal accounts; organisation domains use imappro/smtppro)
    val zoho = EmailPreset(
        "zoho", "Zoho Mail", setOf("zoho.com", "zohomail.com"),
        ssl("imap.zoho.com", 993), ssl("smtp.zoho.com", 465),
        passwordUrl = "https://accounts.zoho.com/home#security/app_password",
        passwordLabel = "Create a Zoho app password",
        passwordHint = "Your password, or an app password if two-factor sign-in is on.",
        note = "Turn on IMAP Access in Zoho Mail (Settings → Mail Accounts) first.",
    )

    // https://support.gmx.com/pop-imap/setup-emailprogram-fails.html
    val gmxCom = EmailPreset(
        "gmx", "GMX", setOf("gmx.com", "gmx.us"),
        ssl("imap.gmx.com", 993), starttls("mail.gmx.com", 587),
        passwordUrl = "https://support.gmx.com/pop-imap/toggle.html",
        passwordLabel = "Turn on IMAP in GMX",
        passwordHint = "Your GMX password.",
        fieldLabel = "Password",
        note = "GMX keeps IMAP off until you turn it on in its settings.",
    )

    // https://hilfe.gmx.net/pop-imap/imap/imap-serverdaten.html
    val gmxNet = EmailPreset(
        "gmx-net", "GMX", setOf("gmx.net", "gmx.de", "gmx.at", "gmx.ch"),
        ssl("imap.gmx.net", 993), starttls("mail.gmx.net", 587),
        passwordUrl = "https://hilfe.gmx.net/pop-imap/einschalten.html",
        passwordLabel = "Turn on IMAP in GMX",
        passwordHint = "Your GMX password.",
        fieldLabel = "Password",
        note = "GMX keeps IMAP off until you turn it on in its settings.",
    )

    // https://kb.mailbox.org/en/business/e-mail/e-mail-configuration
    val mailboxOrg = EmailPreset(
        "mailbox-org", "mailbox.org", setOf("mailbox.org"),
        ssl("imap.mailbox.org", 993), ssl("smtp.mailbox.org", 465),
        passwordUrl = "https://kb.mailbox.org/en/business/e-mail/e-mail-configuration",
        passwordLabel = "How to make a mailbox.org app password",
        passwordHint = "Your password, or an application password if two-factor sign-in is on.",
    )

    val all = listOf(gmail, fastmail, icloud, yahoo, zoho, gmxCom, gmxNet, mailboxOrg)

    /** The lowercase domain of [address], or null until it has one (something after the last @ with a dot). */
    fun domainOf(address: String): String? =
        address.trim().substringAfterLast('@', "").lowercase().trimEnd('.').takeIf { '.' in it && Regex("^[a-z0-9.-]+$").matches(it) }

    fun forAddress(address: String): EmailPreset? = domainOf(address)?.let { d -> all.firstOrNull { d in it.domains } }

    /** Why [address]'s provider can't work with a password at all, or null. */
    fun unsupported(address: String): String? {
        val domain = domainOf(address) ?: return null
        // outlook.com, hotmail.co.uk, live.fr…: the brand, then a short country or generic ending.
        val microsoft = domain.substringBefore('.') in setOf("outlook", "hotmail", "live", "msn") && domain.substringAfter('.').length <= 6
        return when {
            // https://support.microsoft.com/office/f4202ebf-89c6-4a8a-bec3-3d60cf7deaef
            microsoft ->
                "Outlook.com isn't supported: Microsoft only allows OAuth sign-in for it, not app passwords. Make the agent's mailbox with another provider."
            // Bridge runs on a computer (https://proton.me/mail/bridge), so a phone can't reach it.
            domain in setOf("proton.me", "protonmail.com", "protonmail.ch", "pm.me") ->
                "Proton Mail only works over IMAP through Proton Mail Bridge, which runs on a computer, not on Android. Make the agent's mailbox with another provider."
            else -> null
        }
    }
}

enum class MailKind(val label: String, val protocol: String) {
    IMAP("IMAP", "imap"),
    SMTP("SMTP", "smtp");

    fun defaultPort(security: MailSecurity): Int = when (this) {
        IMAP -> if (security == MailSecurity.SSL) 993 else 143
        SMTP -> when (security) {
            MailSecurity.SSL -> 465
            MailSecurity.STARTTLS -> 587
            MailSecurity.NONE -> 25
        }
    }

    /** The URL scheme [EmailConfig.parse] reads for [security]. */
    fun scheme(security: MailSecurity): String = when (security) {
        MailSecurity.SSL -> protocol + "s"
        MailSecurity.STARTTLS -> protocol
        MailSecurity.NONE -> "$protocol+insecure"
    }
}

/** One server's fields as typed. [port] is text so a half-typed or wrong value can be shown and explained. */
data class ServerFields(
    val host: String = "",
    val port: String = "",
    val security: MailSecurity = MailSecurity.SSL,
    /** Whether the user typed the port; until then a security change moves it to that security's default. */
    val portEdited: Boolean = false,
) {
    fun sameAs(server: MailServer) = host.trim().equals(server.host, ignoreCase = true) && port.trim() == server.port.toString() && security == server.security

    companion object {
        fun empty(kind: MailKind) = ServerFields(port = kind.defaultPort(MailSecurity.SSL).toString())
        fun of(kind: MailKind, server: MailServer) = ServerFields(
            host = server.host,
            port = server.port.toString(),
            security = server.security,
            portEdited = server.port != kind.defaultPort(server.security),
        )
    }
}

enum class EmailField { ADDRESS, PASSWORD, NAME, USERNAME, IMAP_HOST, IMAP_PORT, IMAP_SECURITY, SMTP_HOST, SMTP_PORT, SMTP_SECURITY }

/**
 * The email form. Every change goes through a `with…` function so the automatic parts stay consistent:
 * the servers follow the address's preset until the user edits them, the username follows the address
 * until the user types one, and a port follows its security setting until the user types one.
 * [password] is only what was typed here; a saved password is never read back into it.
 */
data class EmailForm(
    val address: String = "",
    val password: String = "",
    val name: String = "",
    val username: String = "",
    val usernameEdited: Boolean = false,
    val imap: ServerFields = ServerFields.empty(MailKind.IMAP),
    val smtp: ServerFields = ServerFields.empty(MailKind.SMTP),
) {
    fun server(kind: MailKind) = if (kind == MailKind.IMAP) imap else smtp

    /** The preset for the address's domain. */
    val preset: EmailPreset? get() = EmailPresets.forAddress(address)

    /** Whether the servers are exactly [preset]'s. */
    val usesPreset: Boolean get() = preset?.let { imap.sameAs(it.imap) && smtp.sameAs(it.smtp) } ?: false

    private val serversBlank: Boolean get() = imap.host.isBlank() && smtp.host.isBlank()

    fun withAddress(value: String): EmailForm {
        val follow = serversBlank || usesPreset
        val next = EmailPresets.forAddress(value)
        var form = copy(address = value, username = if (usernameEdited) username else value.trim())
        if (follow) {
            form = when {
                next != null -> form.copy(imap = ServerFields.of(MailKind.IMAP, next.imap), smtp = ServerFields.of(MailKind.SMTP, next.smtp))
                // The servers came from the old domain's preset, which doesn't apply any more.
                !serversBlank -> form.copy(imap = ServerFields.empty(MailKind.IMAP), smtp = ServerFields.empty(MailKind.SMTP))
                else -> form
            }
        }
        return form
    }

    fun withUsername(value: String) = copy(username = value, usernameEdited = value.isNotBlank() && value.trim() != address.trim())

    fun withHost(kind: MailKind, value: String) = withServer(kind) { it.copy(host = value) }

    fun withPort(kind: MailKind, value: String) = withServer(kind) {
        val digits = value.filter(Char::isDigit).take(5)
        it.copy(port = digits, portEdited = digits.isNotEmpty() && digits != kind.defaultPort(it.security).toString())
    }

    fun withSecurity(kind: MailKind, security: MailSecurity) = withServer(kind) {
        val port = if (it.portEdited && it.port.isNotBlank()) it.port else kind.defaultPort(security).toString()
        it.copy(security = security, port = port)
    }

    /** Fills both servers (and the username, unless typed) from a lookup or preset. */
    fun withServers(imap: MailServer?, smtp: MailServer?, username: String? = null): EmailForm {
        var form = this
        if (imap != null) form = form.copy(imap = ServerFields.of(MailKind.IMAP, imap))
        if (smtp != null) form = form.copy(smtp = ServerFields.of(MailKind.SMTP, smtp))
        if (username != null && !usernameEdited && username != address.trim()) form = form.copy(username = username, usernameEdited = true)
        return form
    }

    private fun withServer(kind: MailKind, change: (ServerFields) -> ServerFields) =
        if (kind == MailKind.IMAP) copy(imap = change(imap)) else copy(smtp = change(smtp))

    /** The password as it will be used: typed text, minus a Gmail app password's display spaces. */
    fun cleanPassword(text: String = password): String =
        if (preset?.stripSpaces == true) text.filterNot(Char::isWhitespace) else text

    /**
     * What's wrong with what has been typed, by field. Empty fields are not problems here (see
     * [missing]); a half-typed address doesn't turn red before the user is done with it.
     */
    fun problems(): Map<EmailField, String> = buildMap {
        val a = address.trim()
        if (a.isNotEmpty() && !isAddress(a)) put(EmailField.ADDRESS, "Enter a whole address, like agent@example.com")
        if (name.any { it == '\n' || it == '\r' }) put(EmailField.NAME, "Keep the name on one line")
        if (username.any(Char::isWhitespace)) put(EmailField.USERNAME, "A username has no spaces")
        for (kind in MailKind.entries) {
            val s = server(kind)
            val host = s.host.trim()
            val hostField = if (kind == MailKind.IMAP) EmailField.IMAP_HOST else EmailField.SMTP_HOST
            val portField = if (kind == MailKind.IMAP) EmailField.IMAP_PORT else EmailField.SMTP_PORT
            val secField = if (kind == MailKind.IMAP) EmailField.IMAP_SECURITY else EmailField.SMTP_SECURITY
            if (host.isNotEmpty() && !isHost(host)) put(hostField, "Enter a host name like ${kind.protocol}.example.com, without ${kind.protocol}:// or a port")
            if (host.isNotEmpty() || kind == MailKind.SMTP) {
                val port = s.port.trim().toIntOrNull()
                if (s.port.isNotBlank() && (port == null || port !in 1..65535)) put(portField, "1 to 65535")
            }
            if (s.security == MailSecurity.NONE && host.isNotEmpty() && !EmailConfig.isLoopback(host.lowercase().removeSurrounding("[", "]"))) {
                put(secField, "No encryption is only allowed for a server on this phone. Use SSL/TLS or STARTTLS.")
            }
        }
    }

    /** Required fields still empty. IMAP may be left out entirely (a send-only mailbox). */
    fun missing(hasSavedPassword: Boolean): List<EmailField> = buildList {
        if (address.isBlank()) add(EmailField.ADDRESS)
        if (password.isBlank() && !hasSavedPassword) add(EmailField.PASSWORD)
        if (smtp.host.isBlank()) add(EmailField.SMTP_HOST)
        if (smtp.port.isBlank()) add(EmailField.SMTP_PORT)
        if (imap.host.isNotBlank() && imap.port.isBlank()) add(EmailField.IMAP_PORT)
    }

    fun isComplete(hasSavedPassword: Boolean) = problems().isEmpty() && missing(hasSavedPassword).isEmpty()

    /**
     * The vault value for this form with [password]: the JSON form of [EmailConfig.parse], which keeps
     * any password character as is (no URL encoding to get wrong). Call [EmailConfig.parse] on it to
     * check it the way the email tools will.
     */
    fun toConfig(password: String): String = buildJsonObject {
        put("smtp", url(MailKind.SMTP, smtp))
        if (imap.host.isNotBlank()) put("imap", url(MailKind.IMAP, imap))
        put("username", username.trim().ifEmpty { address.trim() })
        put("password", password)
        name.trim().takeIf { it.isNotEmpty() }?.let { put("name", it) }
        put("address", address.trim())
    }.toString()

    private fun url(kind: MailKind, s: ServerFields): String {
        val host = s.host.trim().let { if (':' in it && !it.startsWith("[")) "[$it]" else it }
        return "${kind.scheme(s.security)}://$host:${s.port.trim().ifEmpty { kind.defaultPort(s.security).toString() }}"
    }

    companion object {
        /** A saved account as form fields. The password stays out: the form only ever holds what is typed. */
        fun of(account: EmailAccount) = EmailForm(
            address = account.address,
            name = account.fromName.orEmpty(),
            username = account.username,
            usernameEdited = account.username != account.address,
            imap = account.imap?.let { ServerFields.of(MailKind.IMAP, it) } ?: ServerFields.empty(MailKind.IMAP),
            smtp = ServerFields.of(MailKind.SMTP, account.smtp),
        )

        fun isAddress(text: String): Boolean =
            EmailPresets.domainOf(text) != null && text.count { it == '@' } == 1 && !text.any(Char::isWhitespace) &&
                runCatching { InternetAddress(text, true) }.isSuccess

        fun isHost(text: String): Boolean =
            Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$").matches(text) ||
                Regex("^\\[?[0-9A-Fa-f:]*:[0-9A-Fa-f:]*]?$").matches(text)
    }
}

/**
 * Thunderbird's public settings list (ISPDB, https://github.com/thunderbird/autoconfig): only asked
 * when the user taps Look up settings, and only with the domain, never the address or password.
 * The provider's own autoconfig host is not asked, because that request carries the whole address.
 */
object EmailAutoconfig {
    fun url(domain: String) = "https://autoconfig.thunderbird.net/v1.1/$domain"

    data class Found(val imap: MailServer?, val smtp: MailServer?, val username: String?)

    /**
     * Reads a clientConfig v1.1 document: the first IMAP and the first SMTP server with TLS (SSL
     * preferred over STARTTLS). Plaintext servers are skipped, since the tools refuse them. Null when
     * the document has neither. A document with a DOCTYPE is refused outright (no entity tricks).
     */
    fun parse(xml: String, address: String): Found? {
        if ("<!DOCTYPE" in xml.uppercase()) return null
        val doc = runCatching {
            val f = DocumentBuilderFactory.newInstance()
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            f.isExpandEntityReferences = false
            f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        }.getOrNull() ?: return null
        fun servers(tag: String, type: String) = doc.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }
            .filter { it.getAttribute("type") == type }
        fun Element.text(tag: String) = getElementsByTagName(tag).item(0)?.textContent?.trim()
        fun best(tag: String, type: String): Pair<MailServer, String?>? = servers(tag, type).mapNotNull { e ->
            val security = when (e.text("socketType")?.uppercase()) {
                "SSL" -> MailSecurity.SSL
                "STARTTLS" -> MailSecurity.STARTTLS
                else -> return@mapNotNull null
            }
            val host = e.text("hostname")?.lowercase()?.takeIf(EmailForm::isHost) ?: return@mapNotNull null
            val port = e.text("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null
            MailServer(host, port, security) to e.text("username")
        }.minByOrNull { if (it.first.security == MailSecurity.SSL) 0 else 1 }
        val imap = best("incomingServer", "imap")
        val smtp = best("outgoingServer", "smtp")
        if (imap == null && smtp == null) return null
        val local = address.trim().substringBeforeLast('@')
        val user = (imap?.second ?: smtp?.second)
            ?.replace("%EMAILADDRESS%", address.trim())?.replace("%EMAILLOCALPART%", local)
            ?.takeIf { '%' !in it && it.isNotBlank() }
        return Found(imap?.first, smtp?.first, user)
    }
}

/** How one server did in a connection test. */
sealed interface ServerCheck {
    data object Passed : ServerCheck

    data class Failed(val problem: Problem, val message: String) : ServerCheck

    enum class Problem { SIGN_IN, HOST_NOT_FOUND, UNREACHABLE, TIMEOUT, TLS, OTHER }
}

/** Both servers' results; [imap] is null for a send-only mailbox. */
data class EmailCheck(val imap: ServerCheck?, val smtp: ServerCheck) {
    val passed get() = smtp == ServerCheck.Passed && (imap == null || imap == ServerCheck.Passed)
}

/** Signs in to one server and disconnects; throws on failure. Blocking. Tests swap in a fake. */
fun interface MailLogin {
    fun login(kind: MailKind, server: MailServer, account: EmailAccount)
}

/**
 * Settings' Test connection: signs in to IMAP and to SMTP (both at once, on the IO dispatcher) with the
 * same Jakarta Mail session settings the email tools use, then disconnects. It never sends or reads
 * mail. Messages say what to change in plain words and never contain the password.
 */
object EmailConnectionTest {
    suspend fun run(account: EmailAccount, login: MailLogin = JakartaMailLogin): EmailCheck = coroutineScope {
        val imap = account.imap?.let { s -> async { check(MailKind.IMAP, s, account, login) } }
        val smtp = async { check(MailKind.SMTP, account.smtp, account, login) }
        EmailCheck(imap?.await(), smtp.await())
    }

    private suspend fun check(kind: MailKind, server: MailServer, account: EmailAccount, login: MailLogin): ServerCheck =
        runInterruptible(Dispatchers.IO) {
            try {
                login.login(kind, server, account)
                ServerCheck.Passed
            } catch (e: Exception) {
                explain(kind, server, e, account)
            }
        }

    fun explain(kind: MailKind, server: MailServer, e: Throwable, account: EmailAccount): ServerCheck.Failed {
        val chain = generateSequence(e) { it.cause }.take(6).toList()
        val where = "${server.host}:${server.port}"
        val other = when (kind) {
            MailKind.IMAP -> if (server.security == MailSecurity.SSL) "STARTTLS (port 143)" else "SSL/TLS (port 993)"
            MailKind.SMTP -> if (server.security == MailSecurity.SSL) "STARTTLS (port 587)" else "SSL/TLS (port 465)"
        }
        val text = chain.mapNotNull { it.message }.joinToString(" ").lowercase()
        return when {
            chain.any { it is AuthenticationFailedException } -> ServerCheck.Failed(
                ServerCheck.Problem.SIGN_IN,
                "${server.host} turned down the username or password. Most providers need an app password here, not the one you sign in with.",
            )
            chain.any { it is UnknownHostException } -> ServerCheck.Failed(
                ServerCheck.Problem.HOST_NOT_FOUND,
                "Couldn't find ${server.host}. Check the host name, and that the phone is online.",
            )
            chain.any { it is SSLException || it is CertificateException } || "starttls" in text -> ServerCheck.Failed(
                ServerCheck.Problem.TLS,
                "Couldn't make a secure connection to $where. Check the security setting; this server may want $other.",
            )
            chain.any { it is SocketTimeoutException } -> ServerCheck.Failed(
                ServerCheck.Problem.TIMEOUT,
                "$where didn't answer in time. Check the port and security setting (it may want $other), or try another network.",
            )
            chain.any { it is ConnectException || it is NoRouteToHostException } -> ServerCheck.Failed(
                ServerCheck.Problem.UNREACHABLE,
                "Couldn't connect to $where. Check the port, and that the phone is online.",
            )
            else -> ServerCheck.Failed(
                ServerCheck.Problem.OTHER,
                "${server.host} said: " + chain.mapNotNull { it.message }.distinct().joinToString(": ").replace(account.password, "***").take(240)
                    .ifBlank { "the connection failed." },
            )
        }
    }
}

/** The real sign-in, through [mailSession] like the email tools. */
internal object JakartaMailLogin : MailLogin {
    override fun login(kind: MailKind, server: MailServer, account: EmailAccount) {
        val session = mailSession(account, kind.protocol, server)
        val service = if (kind == MailKind.IMAP) session.getStore("imap") else session.getTransport("smtp")
        try {
            service.connect(server.host, server.port, account.username, account.password)
        } finally {
            runCatching { service.close() }
        }
    }
}

/**
 * The Jakarta Mail session for one server: TLS required (implicit, or STARTTLS that must succeed),
 * TLS 1.2+ with the host name checked, and timeouts. Shared by the email tools and the connection test.
 */
internal fun mailSession(account: EmailAccount, protocol: String, server: MailServer, connectMs: Int = 15_000, readMs: Int = 30_000): Session {
    val p = Properties()
    val pre = "mail.$protocol"
    p["$pre.host"] = server.host
    p["$pre.port"] = server.port.toString()
    p["$pre.connectiontimeout"] = connectMs.toString()
    p["$pre.timeout"] = readMs.toString()
    p["$pre.writetimeout"] = readMs.toString()
    p["$pre.ssl.enable"] = (server.security == MailSecurity.SSL).toString()
    p["$pre.starttls.enable"] = (server.security == MailSecurity.STARTTLS).toString()
    p["$pre.starttls.required"] = (server.security == MailSecurity.STARTTLS).toString()
    p["$pre.ssl.checkserveridentity"] = "true"
    p["$pre.ssl.protocols"] = "TLSv1.3 TLSv1.2"
    if (protocol == "smtp") {
        p["mail.smtp.auth"] = "true"
        p["mail.smtp.from"] = account.address
    } else {
        // Never set \Seen as a side effect of reading.
        p["mail.imap.peek"] = "true"
    }
    return Session.getInstance(p)
}
