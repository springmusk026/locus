package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ToolLabelsTest {
    @Test
    fun pastTenseOnceDone() {
        assertEquals("Wrote notes/a.md", toolLabel("write_file", """{"path":"notes/a.md"}"""))
        assertEquals("Write notes/a.md", toolLabel("write_file", """{"path":"notes/a.md"}""", done = false))
    }

    @Test
    fun sshShowsCommandAndHost() {
        assertEquals("Ran `uptime` on aws", toolLabel("ssh_exec", """{"host":"aws","command":"uptime\nls"}"""))
    }

    @Test
    fun browserToolsReadNaturally() {
        assertEquals("Filled 3 fields", toolLabel("browser_fill_form", """{"fields":[{},{},{}]}"""))
        assertEquals("Wait for “Done”", toolLabel("browser_wait", """{"text":"Done"}""", done = false))
        assertEquals("Waited 5s", toolLabel("browser_wait", """{"seconds":5}"""))
        assertEquals("Switched to tab 2", toolLabel("browser_tabs", """{"action":"select","index":2}"""))
        assertEquals("Uploaded a.png, b.pdf", toolLabel("browser_upload", """{"ref":"e1","paths":["x/a.png","b.pdf"]}"""))
        assertEquals("Saved the page as a screenshot", toolLabel("browser_save", """{"format":"png"}"""))
        assertEquals("Looked for “Pricing” on the page", toolLabel("browser_snapshot", """{"find":"Pricing"}"""))
        assertEquals("Opened example.com in a new tab", toolLabel("browser_open", """{"url":"https://example.com","new_tab":true}"""))
        assertEquals("Answered OK to the page", toolLabel("browser_dialog", """{"accept":true}"""))
    }

    @Test
    fun unknownToolsAreHumanised() {
        assertEquals("Some new tool", toolLabel("some_new_tool", "{}"))
    }

    @Test
    fun invalidJsonDoesNotThrow() {
        assertEquals("Read …", toolLabel("read_file", "not json"))
    }

    @Test
    fun activityHasNoArguments() {
        assertEquals("Searching the web", toolActivity("web_search"))
        assertEquals("Browsing", toolActivity("browser_click"))
        assertEquals("Working on a remote machine", toolActivity("sftp_upload"))
        assertEquals("Working with files", toolActivity("write_file"))
    }
}
