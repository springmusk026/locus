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
