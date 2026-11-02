package dev.locus.core.agent

import dev.locus.core.agent.tools.CodeFiles
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.agent.tools.workspaceTools
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodingToolsTest {
    private val base: File = Files.createTempDirectory("locus").toFile()
    private val ws = Workspace(File(base, "workspace"), File(base, "trash"))
    private val tools = ToolRegistry(workspaceTools(ws))

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private fun json(v: Any): JsonElement = when (v) {
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    private suspend fun run(name: String, vararg args: Pair<String, Any>): ToolResult {
        val obj = JsonObject(args.associate { (k, v) -> k to json(v) })
        validateArguments(tools[name]!!.spec.inputSchema, obj)?.let { error("bad test args: $it") }
        return try {
            tools[name]!!.execute(obj)
        } catch (e: ToolException) {
            ToolResult.error(e.message.orEmpty())
        }
    }

    private fun write(path: String, text: String) = ws.resolve(path).apply { parentFile?.mkdirs(); writeText(text) }

    private fun edits(vararg e: Triple<String, String, Boolean>) = JsonArray(
        e.map { (old, new, all) -> JsonObject(mapOf("old_text" to JsonPrimitive(old), "new_text" to JsonPrimitive(new), "replace_all" to JsonPrimitive(all))) },
    )

    // edit_file

    @Test
    fun `edit shows the changed lines and keeps a backup`() = runTest {
        write("a.kt", "fun a() {\n    return 1\n}\n")
        val r = run("edit_file", "path" to "a.kt", "old_text" to "return 1", "new_text" to "return 2")
        assertFalse(r.isError, r.content)
        assertTrue("2\t    return 2" in r.content, r.content)
        assertEquals("fun a() {\n    return 2\n}\n", ws.resolve("a.kt").readText())
        assertEquals(1, ws.trashEntries().size)
    }

    @Test
    fun `several matches name their lines unless replace_all`() = runTest {
        write("a.txt", "foo\nbar\nfoo\n")
        val r = run("edit_file", "path" to "a.txt", "old_text" to "foo", "new_text" to "baz")
        assertTrue(r.isError)
        assertTrue("2 times" in r.content && "lines 1, 3" in r.content && "replace_all" in r.content, r.content)
        assertEquals("foo\nbar\nfoo\n", ws.resolve("a.txt").readText())
        val all = run("edit_file", "path" to "a.txt", "old_text" to "foo", "new_text" to "baz", "replace_all" to true)
        assertFalse(all.isError, all.content)
        assertTrue("2 replacements" in all.content)
        assertEquals("baz\nbar\nbaz\n", ws.resolve("a.txt").readText())
    }

    @Test
    fun `a miss caused by uneven indentation shows the real text`() = runTest {
        write("a.py", "def f():\n        x = 1\n      return x\n")
        val r = run("edit_file", "path" to "a.py", "old_text" to "    x = 1\n    return x", "new_text" to "    return 1")
        assertTrue(r.isError)
        assertTrue("whitespace is ignored" in r.content && "2\t        x = 1" in r.content, r.content)
        assertEquals("def f():\n        x = 1\n      return x\n", ws.resolve("a.py").readText())
    }

    @Test
    fun `a miss points at the closest line, and spots copied line numbers`() = runTest {
        write("a.kt", "val total = price * quantity\nval tax = 3\n")
        val close = run("edit_file", "path" to "a.kt", "old_text" to "val total = price * qty", "new_text" to "x")
        assertTrue(close.isError && "near line 1" in close.content, close.content)
        val numbered = run("edit_file", "path" to "a.kt", "old_text" to "2\tval tax = 3", "new_text" to "x")
        assertTrue(numbered.isError && "line-number prefixes" in numbered.content, numbered.content)
        val nothing = run("edit_file", "path" to "a.kt", "old_text" to "completely unrelated", "new_text" to "x")
        assertTrue(nothing.isError && "Read it again" in nothing.content, nothing.content)
    }

    @Test
    fun `edit refuses empty, no-op, binary and outside paths`() = runTest {
        write("a.txt", "x")
        assertTrue("empty" in run("edit_file", "path" to "a.txt", "old_text" to "", "new_text" to "y").content)
        assertTrue("same" in run("edit_file", "path" to "a.txt", "old_text" to "x", "new_text" to "x").content)
        ws.resolve("b.bin").writeBytes(byteArrayOf(1, 0, 2))
        assertTrue("binary" in run("edit_file", "path" to "b.bin", "old_text" to "a", "new_text" to "b").content)
        File(base, "outside.txt").writeText("x")
        val out = run("edit_file", "path" to "../outside.txt", "old_text" to "x", "new_text" to "y")
        assertTrue(out.isError && "outside the workspace" in out.content)
        assertEquals("x", File(base, "outside.txt").readText())
    }

    @Test
    fun `edit keeps windows line endings`() = runTest {
        write("a.txt", "one\r\ntwo\r\nthree\r\n")
        assertFalse(run("edit_file", "path" to "a.txt", "old_text" to "one\ntwo", "new_text" to "1\n2").isError)
        assertEquals("1\r\n2\r\nthree\r\n", ws.resolve("a.txt").readText())
    }

    @Test
    fun `edit handles dollar signs and backslashes literally`() = runTest {
        write("a.sh", "echo HOME\n")
        assertFalse(run("edit_file", "path" to "a.sh", "old_text" to "HOME", "new_text" to "\$HOME \\n \$1").isError)
        assertEquals("echo \$HOME \\n \$1\n", ws.resolve("a.sh").readText())
    }

    // multi_edit

    @Test
    fun `multi_edit applies in order`() = runTest {
        write("a.kt", "val a = 1\nval b = 2\n")
        val r = run("multi_edit", "path" to "a.kt", "edits" to edits(Triple("val a = 1", "val a = 10", false), Triple("a = 10", "a = 11", false), Triple("val", "var", true)))
        assertFalse(r.isError, r.content)
        assertEquals("var a = 11\nvar b = 2\n", ws.resolve("a.kt").readText())
        assertTrue("3 changes" in r.content)
    }

    @Test
    fun `multi_edit is all or nothing`() = runTest {
        write("a.kt", "val a = 1\nval b = 2\n")
        val r = run("multi_edit", "path" to "a.kt", "edits" to edits(Triple("val a = 1", "val a = 10", false), Triple("missing", "x", false)))
        assertTrue(r.isError)
        assertTrue("Edit 2 of 2" in r.content && "no changes were made" in r.content, r.content)
        assertEquals("val a = 1\nval b = 2\n", ws.resolve("a.kt").readText())
        assertTrue(ws.trashEntries().isEmpty())
        assertTrue(run("multi_edit", "path" to "../x", "edits" to edits(Triple("a", "b", false))).content.contains("outside the workspace"))
        assertTrue(run("multi_edit", "path" to "a.kt", "edits" to JsonArray(emptyList())).isError)
    }

    // read_file

    @Test
    fun `read cuts very long lines and reports the end`() = runTest {
        write("min.js", "a".repeat(5_000) + "\nshort\n")
        val r = run("read_file", "path" to "min.js")
        assertTrue("line cut: 5000 characters" in r.content)
        assertTrue("2\tshort" in r.content)
        val past = run("read_file", "path" to "min.js", "start_line" to 9)
        assertTrue(past.isError && "has 2 lines" in past.content, past.content)
    }

    @Test
    fun `large files are read in parts with a continue hint`() = runTest {
        write("big.txt", (1..10_000).joinToString("\n") { "line $it " + "x".repeat(60) })
        val r = run("read_file", "path" to "big.txt")
        assertTrue(r.content.endsWith("continue with start_line=2001]") || "continue with start_line=" in r.content, r.content.takeLast(200))
        assertTrue(r.content.length < 200_000)
        val part = run("read_file", "path" to "big.txt", "start_line" to 9_999, "max_lines" to 5)
        assertTrue("9999\tline 9999" in part.content && "10000\tline 10000" in part.content)
    }

    // find_files

    @Test
    fun `find matches globs and skips dependency folders`() = runTest {
        write("src/main/App.kt", "")
        write("src/test/AppTest.kt", "")
        write("build/gen/Gen.kt", "")
        write("node_modules/x/index.js", "")
        write("web/app.ts", "")
        write("web/app.tsx", "")
        val kt = run("find_files", "pattern" to "**/*.kt").content.lines().toSet()
        assertEquals(setOf("src/main/App.kt", "src/test/AppTest.kt"), kt)
        assertEquals(setOf("src/main/App.kt", "src/test/AppTest.kt"), run("find_files", "pattern" to "*.kt").content.lines().toSet())
        assertEquals(setOf("web/app.ts", "web/app.tsx"), run("find_files", "pattern" to "*.{ts,tsx}").content.lines().toSet())
        assertEquals("src/test/AppTest.kt", run("find_files", "pattern" to "test/*Test.kt", "path" to "src").content)
        assertTrue("build/gen/Gen.kt" in run("find_files", "pattern" to "*.kt", "include_ignored" to true).content)
        assertTrue("No files" in run("find_files", "pattern" to "*.rs").content)
        assertTrue(run("find_files", "pattern" to "*", "path" to "..").content.contains("outside the workspace"))
    }

    @Test
    fun `gitignored paths are skipped`() = runTest {
        write(".gitignore", "# comment\n*.log\n/secret/\ngenerated\n!keep.log\n")
        write("a.log", "x")
        write("keep.log", "x")
        write("secret/key.txt", "x")
        write("lib/generated/out.kt", "x")
        write("lib/real.kt", "x")
        val found = run("find_files", "pattern" to "**/*").content.lines().toSet()
        assertEquals(setOf(".gitignore", "keep.log", "lib/real.kt"), found)
    }

    @Test
    fun `symlinks out of the workspace are never followed`() = runTest {
        val outside = File(base, "outside").apply { mkdirs() }
        File(outside, "private.txt").writeText("TOPSECRET")
        write("real.txt", "hello")
        Files.createSymbolicLink(ws.resolve(".").toPath().resolve("link"), outside.toPath())
        Files.createSymbolicLink(ws.resolve(".").toPath().resolve("file-link.txt"), File(outside, "private.txt").toPath())
        assertFalse("private" in run("find_files", "pattern" to "**/*", "include_ignored" to true).content)
        assertEquals("No matches.", run("search_files", "pattern" to "TOPSECRET", "include_ignored" to true).content)
        assertTrue(run("read_file", "path" to "link/private.txt").content.contains("outside the workspace"))
    }

    // search_files

    @Test
    fun `search shows context, filters by glob, and counts`() = runTest {
        write("a.kt", "one\ntwo\nTARGET\nfour\nfive\nsix\nseven\nTARGET\nnine\n")
        write("b.py", "TARGET\n")
        write("node_modules/c.js", "TARGET\n")
        val ctx = run("search_files", "pattern" to "TARGET", "glob" to "*.kt", "context" to 1).content
        assertEquals("a.kt-2- two\na.kt:3: TARGET\na.kt-4- four\n--\na.kt-7- seven\na.kt:8: TARGET\na.kt-9- nine", ctx)
        assertEquals(setOf("a.kt", "b.py"), run("search_files", "pattern" to "TARGET", "output" to "files").content.lines().toSet())
        assertTrue("a.kt: 2" in run("search_files", "pattern" to "TARGET", "output" to "count").content)
        assertTrue("node_modules/c.js" in run("search_files", "pattern" to "TARGET", "include_ignored" to true).content)
        assertTrue("a.kt:3" in run("search_files", "pattern" to "target", "ignore_case" to true).content)
    }

    @Test
    fun `search can take literal text and explains bad patterns`() = runTest {
        write("a.kt", "foo(bar)\n")
        val bad = run("search_files", "pattern" to "foo(")
        assertTrue(bad.isError && "literal" in bad.content)
        assertEquals("a.kt:1: foo(bar)", run("search_files", "pattern" to "foo(", "literal" to true).content)
        assertTrue(run("search_files", "pattern" to "x", "output" to "lines").isError)
    }

    @Test
    fun `search stops at max_results`() = runTest {
        write("a.txt", (1..50).joinToString("\n") { "hit $it" })
        val r = run("search_files", "pattern" to "hit", "max_results" to 5).content
        assertEquals(5, r.lines().count { it.startsWith("a.txt:") })
        assertTrue("stopped at 5" in r)
    }

    // code_outline and project_info

    @Test
    fun `outline lists declarations with line numbers`() = runTest {
        write("src/Shop.kt", "package x\n\ndata class Item(val name: String)\n\nclass Cart {\n    fun add(item: Item) {\n        val local = 1\n    }\n}\n\nfun main() {}\n")
        write("tool.py", "import os\n\nclass Tool:\n    def run(self):\n        pass\n\ndef helper():\n    pass\n")
        write("web/app.ts", "export function start() {}\nexport const handler = async (req) => {}\nexport interface Props { a: string }\n")
        val kt = run("code_outline", "path" to "src/Shop.kt").content
        assertTrue("3: data class Item(val name: String)" in kt && "6: fun add(item: Item)" in kt && "11: fun main() {}" in kt, kt)
        assertFalse("val local" in kt)
        val all = run("code_outline").content
        assertTrue("3: class Tool:" in all && "4: def run(self):" in all && "7: def helper():" in all, all)
        assertTrue("1: export function start()" in all && "2: export const handler" in all && "3: export interface Props" in all, all)
        assertTrue("not a recognised source file" in run("code_outline", "path" to ".gitignore".also { write(it, "x") }).content)
    }

    @Test
    fun `project info finds scripts, toolchains and instruction files`() = runTest {
        write("app/package.json", """{"name":"x","scripts":{"dev":"vite","test":"vitest run"}}""")
        write("app/pnpm-lock.yaml", "")
        write("app/AGENTS.md", "rules")
        write("app/src/main.ts", "")
        val info = run("project_info", "path" to "app").content
        assertTrue("Read first: AGENTS.md" in info, info)
        assertTrue("Node.js (pnpm)" in info && "test: vitest run" in info && "cd /workspace/app && pnpm test" in info, info)
        assertTrue("linux_service_start" in info)
        write("py/pyproject.toml", "[project]\nname='x'\n[tool.pytest.ini_options]\n")
        val py = run("project_info", "path" to "py").content
        assertTrue("venv" in py && "pytest" in py, py)
        write("droid/build.gradle.kts", "plugins { id(\"com.android.application\") }")
        write("droid/gradlew", "")
        assertTrue("Android SDK" in run("project_info", "path" to "droid").content)
        assertTrue("no build system recognised" in run("project_info", "path" to "app/src").content)
        assertTrue(run("project_info", "path" to "..").content.contains("outside the workspace"))
    }

    @Test
    fun `glob regex follows ripgrep conventions`() {
        val g = { p: String -> CodeFiles.globRegex(p) }
        assertTrue(g("*.kt").matches("a/b/C.kt"))
        assertTrue(g("**/*.kt").matches("C.kt"))
        assertFalse(g("src/*.kt").matches("src/a/C.kt"))
        assertTrue(g("src/**/*.kt").matches("src/a/b/C.kt"))
        assertTrue(g("src/**/*.kt").matches("src/C.kt"))
        assertTrue(g("test_?.py").matches("test_1.py"))
        assertTrue(g("[ab].txt").matches("a.txt"))
        assertFalse(g("[!ab].txt").matches("a.txt"))
        assertTrue(g("file+name(1).txt").matches("file+name(1).txt"))
    }
}
