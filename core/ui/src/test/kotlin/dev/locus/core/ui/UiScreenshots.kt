package dev.locus.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.locus.core.agent.integrations.Integrations
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.locus.core.agent.integrations.EmailCheck
import dev.locus.core.agent.integrations.EmailConfig
import dev.locus.core.agent.integrations.EmailConnectionTest
import dev.locus.core.agent.integrations.EmailForm
import dev.locus.core.agent.integrations.MailKind
import dev.locus.core.agent.integrations.MailSecurity
import dev.locus.core.agent.integrations.ServerCheck
import dev.locus.core.data.integrations.SavedEmail
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import javax.net.ssl.SSLHandshakeException
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.Spacing
import dev.locus.core.model.SavedWorkflow
import dev.locus.core.model.SshAuth
import dev.locus.core.model.TelegramRemoteSettings
import dev.locus.core.model.Workflow
import dev.locus.core.model.WorkflowCheck
import dev.locus.core.model.WorkflowParam
import dev.locus.core.model.WorkflowParamType
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.core.screenshot.Variant
import kotlinx.coroutines.flow.flowOf
import org.junit.Test

/** The sheets several features share: Telegram setup, workflows, and SSH sign-in fields. */
class UiScreenshots : ScreenshotTest() {

    // ---- Telegram

    @Test
    fun telegramNew() = snap("telegram-sheet", heightDp = 1000) {
        TelegramSheet(Integrations.telegram, hasKey = false, actions = TelegramActions.None, onDismiss = {})
    }

    @Test
    fun telegramConnected() = snap("telegram-sheet-connected", heightDp = 1500) {
        val actions = TelegramActions(
            savedChatId = { done -> done(48_213_907L) },
            findChats = { _, _ -> },
            test = { _, _, _ -> },
            save = { _, _, _ -> },
            remove = {},
            remote = flowOf(TelegramRemoteSettings(approvals = true)),
        )
        TelegramSheet(Integrations.telegram, hasKey = true, actions = actions, onDismiss = {})
    }

    @Test
    fun telegramGroupChat() = snap("telegram-sheet-group", heightDp = 1100) {
        // A saved group chat (negative id): no Remote section, and the group warning.
        val actions = TelegramActions(
            savedChatId = { done -> done(-1_002_174_335_901L) },
            findChats = { _, _ -> },
            test = { _, _, _ -> },
            save = { _, _, _ -> },
            remove = {},
            remote = flowOf(TelegramRemoteSettings()),
        )
        TelegramSheet(Integrations.telegram, hasKey = true, actions = actions, onDismiss = {})
    }

    // ---- Email

    private val gmail = EmailForm().withAddress("agent@gmail.com").copy(password = "abcdefghijklmnop", name = "Locus Agent")

    private val custom = EmailForm().withAddress("agent@example.org").copy(password = "pw")
        .withHost(MailKind.IMAP, "mail.example.org").withHost(MailKind.SMTP, "mail.example.org")
        .withSecurity(MailKind.SMTP, MailSecurity.STARTTLS)

    @Composable
    private fun Email(state: EmailSheetState, hasKey: Boolean = false, actions: EmailActions = EmailActions.None) {
        EmailSheet(Integrations.email, hasKey = hasKey, actions = actions, onDismiss = {}, state = state)
    }

    @Test
    fun emailEmpty() = snap("email-sheet", heightDp = 1000) { Email(remember { EmailSheetState() }) }

    @Test
    fun emailGmail() = snap("email-sheet-gmail", heightDp = 1050) { Email(remember { EmailSheetState(gmail) }) }

    @Test
    fun emailServers() = snap("email-sheet-servers", heightDp = 1500) { Email(remember { EmailSheetState(gmail, serversOpen = true) }) }

    @Test
    fun emailCustomDomain() = snap("email-sheet-custom", heightDp = 1600) { Email(remember { EmailSheetState(custom) }) }

    @Test
    fun emailInvalid() = snap("email-sheet-invalid", heightDp = 1600) {
        Email(remember { EmailSheetState(custom.withHost(MailKind.IMAP, "imaps://mail.example.org").withPort(MailKind.SMTP, "99999")) })
    }

    @Test
    fun emailOutlook() = snap("email-sheet-outlook", heightDp = 1100) {
        Email(remember { EmailSheetState(EmailForm().withAddress("agent@outlook.com")) })
    }

    @Test
    fun emailTesting() = snap("email-sheet-testing", heightDp = 1250) {
        Email(remember { EmailSheetState(gmail).apply { running = EmailRun.TEST } })
    }

    @Test
    fun emailTestPassed() = snap("email-sheet-test-passed", heightDp = 1250) {
        Email(remember { EmailSheetState(gmail).apply { check = EmailCheck(ServerCheck.Passed, ServerCheck.Passed) } })
    }

    @Test
    fun emailTestFailed() = snap("email-sheet-test-failed", heightDp = 1350) {
        val account = EmailConfig.parse(gmail.toConfig("abcdefghijklmnop"))
        val signIn = EmailConnectionTest.explain(MailKind.IMAP, account.imap!!, AuthenticationFailedException("535"), account)
        val tls = EmailConnectionTest.explain(MailKind.SMTP, account.smtp, MessagingException("x", SSLHandshakeException("handshake")), account)
        Email(remember { EmailSheetState(gmail).apply { check = EmailCheck(signIn, tls) } })
    }

    /** At 200% the long states run past a phone's height; these show their whole length. */
    @Test
    fun emailLongAtLargeFont() {
        val large = listOf(Variant.Font200)
        snap("email-sheet-servers-tall", heightDp = 2900, variants = large) { Email(remember { EmailSheetState(gmail, serversOpen = true) }) }
        snap("email-sheet-invalid-tall", heightDp = 3400, variants = large) {
            Email(remember { EmailSheetState(custom.withHost(MailKind.IMAP, "imaps://mail.example.org").withPort(MailKind.SMTP, "99999")) })
        }
        snap("email-sheet-test-failed-tall", heightDp = 2600, variants = large) {
            val account = EmailConfig.parse(gmail.toConfig("abcdefghijklmnop"))
            val signIn = EmailConnectionTest.explain(MailKind.IMAP, account.imap!!, AuthenticationFailedException("535"), account)
            val tls = EmailConnectionTest.explain(MailKind.SMTP, account.smtp, MessagingException("x", SSLHandshakeException("handshake")), account)
            Email(remember { EmailSheetState(gmail).apply { check = EmailCheck(signIn, tls) } })
        }
    }

    @Test
    fun emailSaved() = snap("email-sheet-saved", heightDp = 1050) {
        val saved = EmailForm.of(EmailConfig.parse("smtps://agent@fastmail.com:pw@smtp.fastmail.com:465 imaps://imap.fastmail.com:993 Locus Agent"))
        Email(remember { EmailSheetState() }, hasKey = true, actions = EmailActions({ it(SavedEmail(saved, null)) }, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}))
    }

    @Test
    fun emailSavedUnreadable() = snap("email-sheet-unreadable", heightDp = 1150) {
        Email(
            remember { EmailSheetState() },
            hasKey = true,
            actions = EmailActions({ it(SavedEmail(null, "SMTP URL must start with smtps:// or smtp://.")) }, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}),
        )
    }

    // ---- Workflows

    private val invoices = Workflow(
        id = "monthly-invoices",
        name = "Monthly invoice summary",
        description = "Totals last month's invoices by vendor and flags anything over budget.",
        goal = "Read every PDF invoice in the folder, total them by vendor and write a summary with anything over the budget flagged.",
        parameters = listOf(
            WorkflowParam("folder", "Invoices folder", WorkflowParamType.PATH, default = "documents/invoices/2026-10"),
            WorkflowParam("budget", "Monthly budget (EUR)", WorkflowParamType.NUMBER, default = "2500"),
            WorkflowParam("currency", "Report currency", WorkflowParamType.CHOICE, default = "EUR", options = listOf("EUR", "USD", "GBP")),
            WorkflowParam("email_accountant", "Email the accountant", WorkflowParamType.BOOLEAN, default = "false", required = false),
        ),
        steps = listOf(
            "List the PDFs in the invoices folder",
            "Extract vendor, date and totals from each with pdf-tools",
            "Total by vendor and compare with the budget",
            "Write summary.md next to the invoices",
        ),
        tools = listOf("list_files", "read_file", "run_command", "write_file"),
        approvals = listOf("write_file"),
        checks = listOf(
            WorkflowCheck(WorkflowCheck.CheckKind.FILE_CHANGED, "Summary written", "documents/invoices/2026-10/summary.md"),
            WorkflowCheck(WorkflowCheck.CheckKind.AGENT, "Every invoice is counted once"),
        ),
        expectedOutput = "summary.md with a table of vendors and totals, and a line for each vendor over budget.",
    )

    private val deploy = Workflow(
        id = "deploy-preview",
        name = "Deploy a preview",
        goal = "Build the site and deploy a Netlify preview for the current branch, then post the link.",
        parameters = listOf(WorkflowParam("branch", "Branch", WorkflowParamType.TEXT, default = "main")),
        steps = listOf("Run npm ci and npm run build", "Deploy dist/ as a Netlify preview", "Reply with the preview URL"),
        tools = listOf("run_command", "netlify_deploy"),
        approvals = listOf("netlify_deploy"),
        checks = listOf(WorkflowCheck(WorkflowCheck.CheckKind.BUILD_PASS, "Build passes", "npm run build")),
    )

    @Test
    fun editorDrafting() = snap("workflow-editor-drafting", heightDp = 500) {
        WorkflowEditorSheet(WorkflowDraftState.Drafting, projectName = null, onSave = { _, _, _ -> }, onDismiss = {})
    }

    @Test
    fun editor() = snap("workflow-editor", heightDp = 2600) {
        WorkflowEditorSheet(WorkflowDraftState.Ready(invoices, projectId = null), projectName = "Bookkeeping", onSave = { _, _, _ -> }, onDismiss = {})
    }

    @Test
    fun editorError() = snap("workflow-editor-error", heightDp = 2000) {
        WorkflowEditorSheet(
            WorkflowDraftState.Ready(deploy, projectId = "p1", replacing = "deploy-preview", error = "A workflow named “Deploy a preview” already exists in this project."),
            projectName = "Portfolio site",
            onSave = { _, _, _ -> },
            onDismiss = {},
        )
    }

    @Test
    fun run() = snap("workflow-run", heightDp = 1000) {
        WorkflowRunSheet(invoices, error = null, onRun = {}, onDismiss = {})
    }

    @Test
    fun runError() = snap("workflow-run-error", heightDp = 800) {
        WorkflowRunSheet(deploy, error = "Branch: there's no branch “feature/new-hero” in projects/portfolio.", onRun = {}, onDismiss = {}, actionLabel = "Run in a new chat")
    }

    @Test
    fun picker() = snap("workflow-picker", heightDp = 700) {
        WorkflowPickerSheet(
            listOf(SavedWorkflow(invoices), SavedWorkflow(deploy, projectId = "p1"), SavedWorkflow(invoices.copy(id = "weekly", name = "Weekly time sheet", description = ""))),
            projectName = "Portfolio site",
            onPick = {},
            onDismiss = {},
        )
    }

    @Test
    fun pickerEmpty() = snap("workflow-picker-empty", heightDp = 500) {
        WorkflowPickerSheet(emptyList(), projectName = null, onPick = {}, onDismiss = {})
    }

    // ---- SSH sign-in fields (inside Settings' and onboarding's machine forms)

    @Test
    fun sshAuth() = snap("ssh-auth-fields", heightDp = 1100) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column(
            Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            LocusCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    SshAuthFields(SshAuth.KEY, {}, "", {}, "", {}, "", {}, needsSecret = true)
                }
            }
            LocusCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    SshAuthFields(SshAuth.PASSWORD, {}, "", {}, "correct-horse-battery", {}, "", {}, needsSecret = false, secretNote = "Saved encrypted on this phone.")
                }
            }
            LocusCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    SshAuthFields(SshAuth.NONE, {}, "", {}, "", {}, "", {}, needsSecret = false)
                }
            }
        } }
    }
}
