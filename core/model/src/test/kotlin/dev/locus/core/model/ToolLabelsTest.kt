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
    fun cloudflareStaticDeploy() {
        assertEquals("Publish dist to Cloudflare as blog", toolLabel("cloudflare_deploy_static", """{"path":"dist","name":"blog","spa":true}""", done = false))
        assertEquals("Published dist to Cloudflare as blog", toolLabel("cloudflare_deploy_static", """{"path":"dist","name":"blog"}"""))
    }

    @Test
    fun supabaseAndRender() {
        assertEquals("Run SQL on shop", toolLabel("supabase_sql", """{"project":"shop","sql":"drop table x"}""", done = false))
        assertEquals("Deployed Edge Function hello to shop", toolLabel("supabase_function_deploy", """{"project":"shop","slug":"hello","path":"fn"}"""))
        assertEquals("Set DB_URL on Render api", toolLabel("render_env_set", """{"service":"api","key":"DB_URL","value":"postgres://secret"}"""))
        assertEquals("Resume api on Render", toolLabel("render_suspend", """{"service":"api","action":"resume"}""", done = false))
        assertEquals("Read build logs of api", toolLabel("render_logs", """{"service":"api","type":"build"}"""))
        assertEquals("Working on Supabase", toolActivity("supabase_query"))
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
