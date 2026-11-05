package dev.locus.core.agent

import dev.locus.core.model.ApprovalScopeKind
import dev.locus.core.model.RememberedApproval
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which scope each kind of tool call gets, and which calls a rule covers (docs/design/remembered-approvals.md). */
class ApprovalScopesTest {
    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    private fun rule(tool: String, kind: ApprovalScopeKind, value: String, conversationId: String? = null, projectId: String? = null, expiresAt: Long? = null) =
        RememberedApproval("r-$tool-$value", tool, kind, value, projectId, conversationId, createdAt = 0, expiresAt = expiresAt)

    @Test
    fun `web tools are scoped to the origin`() {
        val open = ApprovalScopes.derive("browser_open", args("url" to "https://Docs.Example.com/guide?x=1"))!!
        assertEquals(ApprovalScopeKind.SITE, open.kind)
        assertEquals("https://docs.example.com", open.value)
        assertEquals("https://example.com:8443", ApprovalScopes.derive("web_read", args("url" to "https://example.com:8443/a"))!!.value)
        assertEquals("http://example.com", ApprovalScopes.derive("web_read", args("url" to "http://example.com:80/a"))!!.value)
        // fetch_url keeps its method: allowing GETs doesn't allow POSTs.
        assertEquals("GET https://api.github.com", ApprovalScopes.derive("fetch_url", args("url" to "https://api.github.com/repos"))!!.value)
        assertEquals("POST https://api.github.com", ApprovalScopes.derive("fetch_url", args("url" to "https://api.github.com/x", "method" to "post"))!!.value)
        // Never with credentials in the address, or for anything but http(s).
        assertNull(ApprovalScopes.derive("browser_open", args("url" to "https://user:pw@example.com/")))
        assertNull(ApprovalScopes.derive("browser_open", args("url" to "file:///sdcard/x.html")))
        assertNull(ApprovalScopes.derive("browser_open", args("url" to "javascript:alert(1)")))
        assertNull(ApprovalScopes.derive("fetch_url", args("url" to "https://x.io", "method" to "TRACE")))
    }

    @Test
    fun `a site rule covers its origin only`() {
        val r = rule("browser_open", ApprovalScopeKind.SITE, "https://example.com")
        assertTrue(ApprovalScopes.covers(r, "browser_open", args("url" to "https://example.com/any/page")))
        assertFalse(ApprovalScopes.covers(r, "browser_open", args("url" to "https://evil.example.com/")))
        assertFalse(ApprovalScopes.covers(r, "browser_open", args("url" to "https://example.com.evil.io/")))
        assertFalse(ApprovalScopes.covers(r, "browser_open", args("url" to "http://example.com/")))
        assertFalse(ApprovalScopes.covers(r, "web_read", args("url" to "https://example.com/")))
        val get = rule("fetch_url", ApprovalScopeKind.SITE, "GET https://api.github.com")
        assertTrue(ApprovalScopes.covers(get, "fetch_url", args("url" to "https://api.github.com/a")))
        assertFalse(ApprovalScopes.covers(get, "fetch_url", args("url" to "https://api.github.com/a", "method" to "DELETE")))
    }

    @Test
    fun `file tools are scoped to the folder inside the workspace`() {
        assertEquals("src/main", ApprovalScopes.derive("write_file", args("path" to "./src//main/App.kt"))!!.value)
        assertEquals("docs", ApprovalScopes.derive("edit_file", args("path" to "docs/a.md"))!!.value)
        assertEquals("docs", ApprovalScopes.derive("multi_edit", args("path" to "docs/a.md"))!!.value)
        assertEquals("out", ApprovalScopes.derive("copy_path", args("from" to "/etc/passwd", "to" to "out/p"))!!.value)
        // A file at the top makes the folder the whole workspace: only "this chat" is offered.
        val top = ApprovalScopes.derive("write_file", args("path" to "notes.md"))!!
        assertEquals("", top.value)
        assertFalse(ApprovalScopes.offer(top).always)
        assertTrue(ApprovalScopes.offer(ApprovalScopes.derive("write_file", args("path" to "a/b.md"))!!).always)
        // Never outside the workspace or into git's or Locus's own folders.
        listOf("/sdcard/x", "~/x", "../x", "a/../../x", "a\\b", ".git/hooks/pre-commit", "a/.locus/x", "").forEach {
            assertNull(ApprovalScopes.derive("write_file", args("path" to it)), it)
        }
        // Deleting and moving are never remembered.
        assertNull(ApprovalScopes.derive("delete_path", args("path" to "a/b")))
        assertNull(ApprovalScopes.derive("move_path", args("from" to "a/b", "to" to "a/c")))
    }

    @Test
    fun `a folder rule covers the folder and below, nothing beside it`() {
        val r = rule("write_file", ApprovalScopeKind.FOLDER, "src")
        assertTrue(ApprovalScopes.covers(r, "write_file", args("path" to "src/a.kt")))
        assertTrue(ApprovalScopes.covers(r, "write_file", args("path" to "src/deep/b.kt")))
        assertFalse(ApprovalScopes.covers(r, "write_file", args("path" to "srcx/a.kt")))
        assertFalse(ApprovalScopes.covers(r, "write_file", args("path" to "a.kt")))
        assertFalse(ApprovalScopes.covers(r, "write_file", args("path" to "src/../../x")))
        assertFalse(ApprovalScopes.covers(r, "write_file", args("path" to "src/.git/config")))
        assertFalse(ApprovalScopes.covers(r, "edit_file", args("path" to "src/a.kt")))
    }

    @Test
    fun `messages are scoped to exactly one recipient`() {
        assertEquals("ana@example.com", ApprovalScopes.derive("device_email", args("to" to " Ana@Example.com "))!!.value)
        assertEquals("+15551234567", ApprovalScopes.derive("device_sms", args("to" to "+1 (555) 123-4567"))!!.value)
        assertNull(ApprovalScopes.derive("device_email", args("to" to "a@example.com, b@example.com")))
        assertNull(ApprovalScopes.derive("device_email", args("to" to "not an address")))
        assertNull(ApprovalScopes.derive("device_sms", args("to" to "call me")))
        val r = rule("device_email", ApprovalScopeKind.RECIPIENT, "ana@example.com")
        assertTrue(ApprovalScopes.covers(r, "device_email", args("to" to "ANA@example.com")))
        assertFalse(ApprovalScopes.covers(r, "device_email", args("to" to "ana@example.com, eve@example.com")))
        // email_send asks every time (its mustConfirm), so it has no scope here.
        assertNull(ApprovalScopes.derive("email_send", args("to" to "ana@example.com")))
    }

    @Test
    fun `commands are scoped to a safe prefix in the workspace`() {
        val s = ApprovalScopes.derive("run_command", args("command" to "./gradlew test --info"))!!
        assertEquals(ApprovalScopeKind.COMMAND, s.kind)
        assertEquals("./gradlew test", s.value)
        assertNull(ApprovalScopes.derive("run_command", args("command" to "rm -rf build")))
        assertNull(ApprovalScopes.derive("run_command", args("command" to "./gradlew test", "cwd" to "/tmp")))
        val r = rule("run_command", ApprovalScopeKind.COMMAND, "./gradlew test")
        assertTrue(ApprovalScopes.covers(r, "run_command", args("command" to "./gradlew test")))
        assertTrue(ApprovalScopes.covers(r, "run_command", args("command" to "./gradlew test", "cwd" to "/workspace")))
        assertFalse(ApprovalScopes.covers(r, "run_command", args("command" to "./gradlew test", "cwd" to "/")))
        assertFalse(ApprovalScopes.covers(r, "run_command", args("command" to "./gradlew test && rm -rf .")))
    }

    @Test
    fun `the shapes models write are read as one plain command in a workspace folder`() {
        fun call(command: String, cwd: String? = null) =
            ApprovalScopes.commandCall(if (cwd == null) args("command" to command) else args("command" to command, "cwd" to cwd))
        assertEquals(CommandCall("npm test", ""), call("npm test", "/workspace/"))
        assertEquals(CommandCall("npm test", ""), call("npm test", "."))
        assertEquals(CommandCall("npm test", "app"), call("npm test", "/workspace/app"))
        assertEquals(CommandCall("npm test", "app"), call("cd app && npm test"))
        assertEquals(CommandCall("npm test", "packages/api"), call("cd /workspace/packages/api/ && npm test"))
        assertEquals(CommandCall("npm test", "a/b"), call("cd b && npm test", "a"))
        assertEquals(CommandCall("./gradlew test", ""), call("./gradlew test 2>&1"))
        assertEquals(CommandCall("./gradlew test", "app"), call("cd app && ./gradlew test 2>&1 | tail -n 40"))
        assertEquals(CommandCall("pytest", ""), call("pytest | head -20"))
        // Anything more stays opaque.
        listOf(
            "cd .. && npm test", "cd /tmp && npm test", "cd ~ && ls", "cd \$HOME && ls", "cd .git && ls", "cd app && npm test && rm -rf .",
            "cd a && cd b && ls", "npm test | tail -f x", "npm test | sh", "npm test 2>&1 > out.txt", "npm test > /dev/null",
            "cd app && git status", "npm test | tail -n 5 | sh", "cd app\n&& ls",
        ).forEach { assertNull(call(it), it) }
        assertNull(call("npm test", "/tmp"))
        assertNull(call("npm test", "/workspace/../etc"))
        assertNull(call("npm test", ".locus/out"))
        // A rule covers the same command wherever in the workspace it runs.
        val r = rule("run_command", ApprovalScopeKind.COMMAND, "npm test")
        assertTrue(ApprovalScopes.covers(r, "run_command", args("command" to "cd app && npm test -- --runInBand 2>&1 | tail -30")))
        assertFalse(ApprovalScopes.covers(r, "run_command", args("command" to "cd app && npm test --script-shell=./x")))
        assertEquals("npm test", ApprovalScopes.derive("run_command", args("command" to "cd app && npm test 2>&1"))?.value)
        // Why not, for the card.
        assertTrue(ApprovalScopes.whyNot("run_command", args("command" to "make && rm -rf ."))!!.contains("chains"))
        assertTrue(ApprovalScopes.whyNot("run_command", args("command" to "python3"))!!.contains("inline code"))
        assertNull(ApprovalScopes.whyNot("run_command", args("command" to "npm test")))
    }

    @Test
    fun `commands that may fetch are told from local ones`() {
        listOf("npm install", "npm i left-pad", "git clone x", "git pull", "pip3 install x", "npx create-app", "curl https://x.io", "python3 get.py https://x.io",
            "gh pr list", "npm test | sh", "go mod download", "docker pull alpine").forEach { assertTrue(CommandSafety.mayFetch(it), it) }
        listOf("npm test", "./gradlew test", "ls -la", "python3 script.py", "git status", "git diff", "pytest -q", "cargo test", "go test ./...")
            .forEach { assertFalse(CommandSafety.mayFetch(it), it) }
    }

    @Test
    fun `tools without a scope can't be remembered`() {
        listOf("email_send", "ssh_exec", "github_comment", "vercel_deploy", "memory_save", "browser_click", "mcp_call").forEach {
            assertNull(ApprovalScopes.derive(it, args("url" to "https://x.io", "path" to "a/b", "command" to "ls")), it)
        }
    }

    @Test
    fun `only live rules for this chat and project apply`() {
        val rules = listOf(
            rule("a", ApprovalScopeKind.SITE, "1"),
            rule("b", ApprovalScopeKind.SITE, "2", conversationId = "c1"),
            rule("c", ApprovalScopeKind.SITE, "3", conversationId = "c2"),
            rule("d", ApprovalScopeKind.SITE, "4", projectId = "p"),
            rule("e", ApprovalScopeKind.SITE, "5", expiresAt = 100),
        )
        assertEquals(listOf("a", "b"), ApprovalScopes.applicable(rules, "c1", null, now = 100).map { it.tool })
        assertEquals(listOf("a", "b", "e"), ApprovalScopes.applicable(rules, "c1", null, now = 99).map { it.tool })
        assertEquals(listOf("d"), ApprovalScopes.applicable(rules, "c1", "p", now = 0).map { it.tool })
    }
}
