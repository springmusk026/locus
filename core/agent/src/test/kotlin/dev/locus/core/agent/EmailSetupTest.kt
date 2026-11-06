package dev.locus.core.agent

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.icegreen.greenmail.util.ServerSetupTest
import dev.locus.core.agent.integrations.EmailAccount
import dev.locus.core.agent.integrations.EmailAutoconfig
import dev.locus.core.agent.integrations.EmailConfig
import dev.locus.core.agent.integrations.EmailConnectionTest
import dev.locus.core.agent.integrations.EmailField
import dev.locus.core.agent.integrations.EmailForm
import dev.locus.core.agent.integrations.EmailPresets
import dev.locus.core.agent.integrations.MailKind
import dev.locus.core.agent.integrations.MailLogin
import dev.locus.core.agent.integrations.MailSecurity
import dev.locus.core.agent.integrations.MailServer
import dev.locus.core.agent.integrations.ServerCheck
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmailFormTest {

    // ---- Presets

    @Test
    fun `presets match on the address's domain, any case`() {
        assertEquals("gmail", EmailPresets.forAddress("Agent@GMail.com ")?.id)
        assertEquals("gmail", EmailPresets.forAddress("agent@googlemail.com")?.id)
        assertEquals("icloud", EmailPresets.forAddress("a@me.com")?.id)
        assertEquals("gmx-net", EmailPresets.forAddress("a@gmx.de")?.id)
        assertEquals("gmx", EmailPresets.forAddress("a@gmx.com")?.id)
        assertNull(EmailPresets.forAddress("a@example.com"))
        assertNull(EmailPresets.forAddress("a@gmail"))
        // Yahoo Japan is a different service with its own servers.
        assertNull(EmailPresets.forAddress("a@yahoo.co.jp"))
    }

    @Test
    fun `every preset uses TLS on the port its security implies, and its domains are unique`() {
        val domains = EmailPresets.all.flatMap { it.domains }
        assertEquals(domains.size, domains.toSet().size)
        for (p in EmailPresets.all) {
            assertTrue(p.imap.security != MailSecurity.NONE && p.smtp.security != MailSecurity.NONE, p.id)
            assertEquals(MailKind.IMAP.defaultPort(p.imap.security), p.imap.port, p.id)
            assertEquals(MailKind.SMTP.defaultPort(p.smtp.security), p.smtp.port, p.id)
            assertTrue(p.passwordUrl.startsWith("https://"), p.id)
        }
    }

    @Test
    fun `outlook and proton say why they can't work`() {
        for (a in listOf("a@outlook.com", "a@hotmail.co.uk", "a@live.com", "a@msn.com")) {
            assertTrue("OAuth" in EmailPresets.unsupported(a).orEmpty(), a)
        }
        assertTrue("Bridge" in EmailPresets.unsupported("a@proton.me").orEmpty())
        assertNull(EmailPresets.unsupported("a@gmail.com"))
        assertNull(EmailPresets.unsupported("a@outlookish.example"))
    }

    // ---- Automatic fields

    @Test
    fun `the address fills the preset's servers and the username`() {
        val f = EmailForm().withAddress("agent@gmail.com")
        assertEquals("imap.gmail.com", f.imap.host)
        assertEquals("993", f.imap.port)
        assertEquals("smtp.gmail.com", f.smtp.host)
        assertEquals("465", f.smtp.port)
        assertEquals("agent@gmail.com", f.username)
        assertTrue(f.usesPreset)
    }

    @Test
    fun `servers follow the address until they are edited`() {
        val gmail = EmailForm().withAddress("agent@gmail.com")
        val fastmail = gmail.withAddress("agent@fastmail.com")
        assertEquals("imap.fastmail.com", fastmail.imap.host)
        // A domain without a preset clears servers that came from the old preset.
        val custom = gmail.withAddress("agent@example.org")
        assertEquals("", custom.imap.host)
        assertEquals("993", custom.imap.port)
        // Once edited, the user's servers stay whatever the address becomes.
        val edited = gmail.withHost(MailKind.IMAP, "mail.example.org")
        assertFalse(edited.usesPreset)
        assertEquals("mail.example.org", edited.withAddress("agent@fastmail.com").imap.host)
    }

    @Test
    fun `a typed username stays when the address changes`() {
        val f = EmailForm().withAddress("a@example.org").withUsername("a-login")
        assertEquals("a-login", f.withAddress("b@example.org").username)
        assertEquals("b@example.org", EmailForm().withAddress("a@example.org").withAddress("b@example.org").username)
    }

    @Test
    fun `security moves the port to its default unless the port was typed`() {
        var f = EmailForm().withAddress("a@example.org")
        f = f.withSecurity(MailKind.SMTP, MailSecurity.STARTTLS)
        assertEquals("587", f.smtp.port)
        f = f.withSecurity(MailKind.IMAP, MailSecurity.STARTTLS)
        assertEquals("143", f.imap.port)
        f = f.withSecurity(MailKind.IMAP, MailSecurity.SSL)
        assertEquals("993", f.imap.port)
        f = f.withPort(MailKind.SMTP, "2525")
        assertTrue(f.smtp.portEdited)
        f = f.withSecurity(MailKind.SMTP, MailSecurity.SSL)
        assertEquals("2525", f.smtp.port)
        // Typing the default again counts as not edited.
        f = f.withPort(MailKind.SMTP, "465").withSecurity(MailKind.SMTP, MailSecurity.STARTTLS)
        assertEquals("587", f.smtp.port)
        // Only digits go in.
        assertEquals("99", EmailForm().withPort(MailKind.IMAP, "9a9").imap.port)
    }

    // ---- Validation

    @Test
    fun `problems name the field and say what to type`() {
        val f = EmailForm()
            .withAddress("agent@")
            .withHost(MailKind.IMAP, "imap://mail.example.org")
            .withHost(MailKind.SMTP, "smtp.example.org")
            .withPort(MailKind.SMTP, "70000")
        val p = f.problems()
        assertNotNull(p[EmailField.ADDRESS])
        assertTrue("without imap://" in p[EmailField.IMAP_HOST].orEmpty())
        assertNotNull(p[EmailField.SMTP_PORT])
        assertNull(p[EmailField.IMAP_PORT])
    }

    @Test
    fun `plaintext only for a server on this phone`() {
        val far = EmailForm().withHost(MailKind.SMTP, "smtp.example.org").withSecurity(MailKind.SMTP, MailSecurity.NONE)
        assertNotNull(far.problems()[EmailField.SMTP_SECURITY])
        val near = EmailForm().withHost(MailKind.SMTP, "127.0.0.1").withSecurity(MailKind.SMTP, MailSecurity.NONE)
        assertNull(near.problems()[EmailField.SMTP_SECURITY])
        assertEquals("25", near.smtp.port)
    }

    @Test
    fun `a saved password stands in for an empty field, and IMAP may be left out`() {
        val f = EmailForm().withAddress("agent@example.org").withHost(MailKind.SMTP, "smtp.example.org")
        assertEquals(listOf(EmailField.PASSWORD), f.missing(hasSavedPassword = false))
        assertTrue(f.isComplete(hasSavedPassword = true))
        assertFalse(EmailForm().isComplete(hasSavedPassword = true))
    }

    // ---- Stored value

    private fun assertSameAccount(a: EmailAccount, b: EmailAccount) {
        assertEquals(a.smtp, b.smtp)
        assertEquals(a.imap, b.imap)
        assertEquals(a.username, b.username)
        assertEquals(a.password, b.password)
        assertEquals(a.address, b.address)
        assertEquals(a.fromName, b.fromName)
    }

    /** parse → fields → serialize → parse gives the same account, the way the tools will read it. */
    private fun roundTrip(saved: String): EmailAccount {
        val account = EmailConfig.parse(saved)
        val form = EmailForm.of(account)
        val again = EmailConfig.parse(form.toConfig(account.password))
        assertSameAccount(account, again)
        // And once more from the new value, so the format is stable.
        assertEquals(form.toConfig(account.password), EmailForm.of(again).toConfig(again.password))
        return again
    }

    @Test
    fun `one-line values with URL-encoded passwords survive the form`() {
        val a = roundTrip("smtps://me@x.org:p%40ss%3Aw%2Frd%25%20x@smtp.x.org:465 imaps://imap.x.org:993 Locus Agent")
        assertEquals("p@ss:w/rd% x", a.password)
        // Unencoded @ and : also parse (the host starts after the last @).
        assertEquals("p@ss:w+rd x%", roundTrip("smtps://me@x.org:p@ss:w+rd%20x%25@smtp.x.org:465").password)
    }

    @Test
    fun `both security modes and odd ports survive the form`() {
        val a = roundTrip("smtp://u@x.org:pw@smtp.x.org imap://imap.x.org:1143")
        assertEquals(MailSecurity.STARTTLS, a.smtp.security)
        assertEquals(587, a.smtp.port)
        assertEquals(MailServer("imap.x.org", 1143, MailSecurity.STARTTLS), a.imap)
        val b = roundTrip("smtps://u@x.org:pw@smtp.x.org:2465 imaps://imap.x.org")
        assertEquals(MailServer("smtp.x.org", 2465, MailSecurity.SSL), b.smtp)
        assertTrue(EmailForm.of(b).smtp.portEdited)
        assertFalse(EmailForm.of(b).imap.portEdited)
    }

    @Test
    fun `send-only, loopback plaintext, separate login and JSON values survive the form`() {
        assertNull(roundTrip("smtps://u@x.org:pw@smtp.x.org").imap)
        roundTrip("smtp+insecure://u@x.org:pw@127.0.0.1:1025 imap+insecure://localhost:1143")
        val login = roundTrip("smtps://login-name:pw@smtp.x.org Agent <agent@x.org>")
        assertEquals("login-name", login.username)
        assertEquals("agent@x.org", login.address)
        assertTrue(EmailForm.of(login).usernameEdited)
        roundTrip("""{"smtp":"smtps://smtp.x.org:465","imap":"imaps://imap.x.org:993","username":"u@x.org","password":"pw","name":"Locus"}""")
    }

    @Test
    fun `any password character is stored as typed`() {
        val passwords = listOf("p@ss:w/rd", "100% sure", " lead and trail ", "quote\" back\\slash", "%41%42", "ünïcødé ✓", "a+b=c&d?e#f")
        for (pw in passwords) {
            val form = EmailForm().withAddress("agent@example.org").withHost(MailKind.IMAP, "imap.example.org").withHost(MailKind.SMTP, "smtp.example.org")
            val a = EmailConfig.parse(form.toConfig(pw))
            assertEquals(pw, a.password, pw)
            assertEquals(MailServer("imap.example.org", 993, MailSecurity.SSL), a.imap)
            assertEquals(MailServer("smtp.example.org", 465, MailSecurity.SSL), a.smtp)
            assertEquals("agent@example.org", a.username)
        }
    }

    @Test
    fun `the form never holds a saved password, and the name and address come back`() {
        val a = EmailConfig.parse("smtps://agent@gmail.com:abcdefghijklmnop@smtp.gmail.com:465 imaps://imap.gmail.com:993 Locus Agent")
        val f = EmailForm.of(a)
        assertEquals("", f.password)
        assertEquals("Locus Agent", f.name)
        assertEquals("agent@gmail.com", f.address)
        assertTrue(f.usesPreset)
        assertFalse("abcdefghijklmnop" in f.toString())
    }

    @Test
    fun `gmail app passwords lose their display spaces`() {
        val f = EmailForm().withAddress("agent@gmail.com")
        assertEquals("abcdefghijklmnop", f.cleanPassword("abcd efgh ijkl mnop"))
        assertEquals("ab cd", EmailForm().withAddress("agent@example.org").cleanPassword("ab cd"))
    }

    @Test
    fun `an IPv6 host is bracketed in the stored URL`() {
        val f = EmailForm().withAddress("a@x.org").withHost(MailKind.SMTP, "::1").withSecurity(MailKind.SMTP, MailSecurity.NONE)
        assertEquals("::1", EmailConfig.parse(f.toConfig("pw")).smtp.host)
    }
}

class EmailAutoconfigTest {
    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <clientConfig version="1.1">
          <emailProvider id="example.org">
            <domain>example.org</domain>
            <incomingServer type="pop3"><hostname>pop.example.org</hostname><port>995</port><socketType>SSL</socketType><username>%EMAILADDRESS%</username></incomingServer>
            <incomingServer type="imap"><hostname>imap.example.org</hostname><port>143</port><socketType>STARTTLS</socketType><username>%EMAILLOCALPART%</username></incomingServer>
            <incomingServer type="imap"><hostname>IMAP.example.org</hostname><port>993</port><socketType>SSL</socketType><username>%EMAILLOCALPART%</username></incomingServer>
            <outgoingServer type="smtp"><hostname>smtp.example.org</hostname><port>25</port><socketType>plain</socketType><username>%EMAILLOCALPART%</username></outgoingServer>
            <outgoingServer type="smtp"><hostname>smtp.example.org</hostname><port>587</port><socketType>STARTTLS</socketType><username>%EMAILLOCALPART%</username></outgoingServer>
          </emailProvider>
        </clientConfig>
    """.trimIndent()

    @Test
    fun `picks TLS servers, preferring SSL, and fills the username`() {
        val found = EmailAutoconfig.parse(xml, "agent@example.org")!!
        assertEquals(MailServer("imap.example.org", 993, MailSecurity.SSL), found.imap)
        assertEquals(MailServer("smtp.example.org", 587, MailSecurity.STARTTLS), found.smtp)
        assertEquals("agent", found.username)
        val form = EmailForm().withAddress("agent@example.org").withServers(found.imap, found.smtp, found.username)
        assertEquals("agent", form.username)
        assertEquals("imap.example.org", form.imap.host)
    }

    @Test
    fun `refuses doctypes, junk and plaintext-only lists`() {
        assertNull(EmailAutoconfig.parse("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><clientConfig/>", "a@x.org"))
        assertNull(EmailAutoconfig.parse("not xml", "a@x.org"))
        assertNull(
            EmailAutoconfig.parse(
                "<clientConfig><emailProvider><incomingServer type=\"imap\"><hostname>imap.x.org</hostname><port>143</port><socketType>plain</socketType></incomingServer></emailProvider></clientConfig>",
                "a@x.org",
            ),
        )
        assertEquals("https://autoconfig.thunderbird.net/v1.1/example.org", EmailAutoconfig.url("example.org"))
    }
}

class EmailConnectionTestTest {
    private val account = EmailConfig.parse("smtps://agent@x.org:hunter2-secret@smtp.x.org:465 imaps://imap.x.org:993")

    private fun failing(imap: Exception?, smtp: Exception?) = MailLogin { kind, _, _ ->
        (if (kind == MailKind.IMAP) imap else smtp)?.let { throw it }
    }

    @Test
    fun `both servers are tried and pass`() = runBlocking {
        val tried = java.util.Collections.synchronizedList(mutableListOf<String>())
        val check = EmailConnectionTest.run(account) { kind, server, a -> tried += "${kind.name} ${server.host} ${a.username}" }
        assertTrue(check.passed)
        assertEquals(setOf("IMAP imap.x.org agent@x.org", "SMTP smtp.x.org agent@x.org"), tried.toSet())
    }

    @Test
    fun `a send-only mailbox only tries SMTP`() = runBlocking {
        val check = EmailConnectionTest.run(EmailConfig.parse("smtps://agent@x.org:pw@smtp.x.org")) { kind, _, _ -> assertEquals(MailKind.SMTP, kind) }
        assertNull(check.imap)
        assertTrue(check.passed)
    }

    @Test
    fun `failures are explained per server in plain words`() = runBlocking {
        val check = EmailConnectionTest.run(account, failing(AuthenticationFailedException("535 bad credentials"), MessagingException("x", UnknownHostException("smtp.x.org"))))
        val imap = assertIs<ServerCheck.Failed>(check.imap)
        assertEquals(ServerCheck.Problem.SIGN_IN, imap.problem)
        assertTrue("app password" in imap.message)
        val smtp = assertIs<ServerCheck.Failed>(check.smtp)
        assertEquals(ServerCheck.Problem.HOST_NOT_FOUND, smtp.problem)
        assertFalse(check.passed)
    }

    @Test
    fun `tls, timeouts and refused connections each say what to check`() {
        val server = account.smtp
        fun problem(e: Exception) = EmailConnectionTest.explain(MailKind.SMTP, server, e, account)
        val tls = problem(MessagingException("Could not connect", SSLHandshakeException("Unsupported or unrecognized SSL message")))
        assertEquals(ServerCheck.Problem.TLS, tls.problem)
        assertTrue("STARTTLS (port 587)" in tls.message, tls.message)
        assertEquals(ServerCheck.Problem.TLS, problem(MessagingException("STARTTLS is required but host does not support STARTTLS")).problem)
        assertEquals(ServerCheck.Problem.TIMEOUT, problem(MessagingException("x", SocketTimeoutException("Read timed out"))).problem)
        assertEquals(ServerCheck.Problem.UNREACHABLE, problem(MessagingException("x", ConnectException("Connection refused"))).problem)
    }

    @Test
    fun `other errors never echo the password`() {
        val f = EmailConnectionTest.explain(MailKind.IMAP, account.imap!!, MessagingException("server said hunter2-secret is odd"), account)
        assertEquals(ServerCheck.Problem.OTHER, f.problem)
        assertFalse("hunter2-secret" in f.message)
    }
}

/** The real sign-in path against GreenMail, an in-process server on loopback; no outside mail server. */
class EmailConnectionLoopbackTest {
    private val green = GreenMail(arrayOf(ServerSetupTest.SMTP, ServerSetupTest.IMAP).map { it.dynamicPort() }.toTypedArray<ServerSetup>()).apply { start() }

    init {
        green.setUser("agent@locus.test", "agent@locus.test", "s3cret pw")
    }

    @AfterTest
    fun stop() = green.stop()

    private fun form() = EmailForm().withAddress("agent@locus.test")
        .withHost(MailKind.IMAP, "127.0.0.1").withSecurity(MailKind.IMAP, MailSecurity.NONE).withPort(MailKind.IMAP, green.imap.port.toString())
        .withHost(MailKind.SMTP, "127.0.0.1").withSecurity(MailKind.SMTP, MailSecurity.NONE).withPort(MailKind.SMTP, green.smtp.port.toString())

    @Test
    fun `signs in to both servers without sending anything`() = runBlocking {
        val check = EmailConnectionTest.run(EmailConfig.parse(form().toConfig("s3cret pw")))
        assertTrue(check.passed, check.toString())
        assertEquals(0, green.receivedMessages.size)
    }

    @Test
    fun `a wrong password is a sign-in problem`() = runBlocking {
        val check = EmailConnectionTest.run(EmailConfig.parse(form().toConfig("wrong")))
        assertEquals(ServerCheck.Problem.SIGN_IN, assertIs<ServerCheck.Failed>(check.imap).problem)
    }
}
