package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Approval lines in English say exactly what the tools' `describe` said when it was a string template
 * (docs/design/localization.md): core/browser, core/linux and the SSH tools, whose tools can't be built
 * in a plain unit test, define `describe` as these lines' English.
 */
class ApprovalLineTest {

    private fun says(english: String, line: ApprovalLineText) = assertEquals(english, line.english)

    @Test
    fun browserLines() {
        says("Open https://a.com", ApprovalLineText(ApprovalPhrase.BROWSER_OPEN, "https://a.com"))
        says("Open https://a.com in a new tab", ApprovalLineText(ApprovalPhrase.BROWSER_OPEN_NEW_TAB, "https://a.com"))
        says("Click e12", ApprovalLineText(ApprovalPhrase.BROWSER_CLICK, "e12"))
        says("Type “hello” into e3", ApprovalLineText(ApprovalPhrase.BROWSER_TYPE, "hello", "e3"))
        says("Type “hello” into e3 and submit", ApprovalLineText(ApprovalPhrase.BROWSER_TYPE_SUBMIT, "hello", "e3"))
        says("Choose “Blue” in e4", ApprovalLineText(ApprovalPhrase.BROWSER_CHOOSE, "Blue", "e4"))
        says("Fill 1 fields", ApprovalLineText.counted(ApprovalPhrase.BROWSER_FILL_FIELDS, 1, 1))
        says("Fill 3 fields", ApprovalLineText.counted(ApprovalPhrase.BROWSER_FILL_FIELDS, 3, 3))
        says("Press Enter", ApprovalLineText(ApprovalPhrase.BROWSER_PRESS, "Enter"))
        says("Answer OK to the page's dialog", ApprovalLineText(ApprovalPhrase.BROWSER_DIALOG_ACCEPT))
        says("Cancel the page's dialog", ApprovalLineText(ApprovalPhrase.BROWSER_DIALOG_CANCEL))
        says("Upload a.png, b.pdf", ApprovalLineText(ApprovalPhrase.BROWSER_UPLOAD, "a.png, b.pdf"))
        says("Fill your saved login for github.com", ApprovalLineText(ApprovalPhrase.BROWSER_LOGIN, "github.com"))
        says("Fill your saved login for this page", ApprovalLineText(ApprovalPhrase.BROWSER_LOGIN_THIS_PAGE))
    }

    @Test
    fun commandLines() {
        says("Run: ls -la", ApprovalLineText(ApprovalPhrase.RUN_COMMAND, "ls -la"))
        says("Start “web”: npm start", ApprovalLineText(ApprovalPhrase.START_SERVICE, "web", "npm start"))
        says("Commit all changes: “Fix it”", ApprovalLineText(ApprovalPhrase.COMMIT, ApprovalLineText(ApprovalPhrase.COMMIT_ALL_CHANGES), "Fix it"))
        says("Commit a.kt, b.kt: “Fix it”", ApprovalLineText(ApprovalPhrase.COMMIT, "a.kt, b.kt", "Fix it"))
        says("On box: uptime", ApprovalLineText(ApprovalPhrase.SSH_RUN, "box", "uptime"))
        says("Start a background job on box: make", ApprovalLineText(ApprovalPhrase.SSH_JOB_START, "box", "make"))
        says("Stop job j1 on box", ApprovalLineText(ApprovalPhrase.SSH_JOB_STOP, "j1", "box"))
        says("Download box:/tmp/a → a", ApprovalLineText(ApprovalPhrase.SFTP_DOWNLOAD, "box", "/tmp/a", "a"))
        says("Upload a → box:/tmp/a", ApprovalLineText(ApprovalPhrase.SFTP_UPLOAD, "a", "box", "/tmp/a"))
        says("Cancel a reminder", ApprovalLineText(ApprovalPhrase.CANCEL_REMINDER))
    }

    @Test
    fun valuesAreNeverReadAsFormats() {
        // A value with a percent sign or a placeholder in it is put in as it is.
        says("Run: echo %1\$s 100%", ApprovalLineText(ApprovalPhrase.RUN_COMMAND, "echo %1\$s 100%"))
    }
}
