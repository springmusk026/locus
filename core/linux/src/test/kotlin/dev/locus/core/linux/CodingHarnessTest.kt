package dev.locus.core.linux

import dev.locus.core.agent.ToolException
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shell side of the coding harness: syntax checks, git_commit and saved command output. Where the
 * program is installed on the machine running the tests, the generated commands are also run for real
 * (with /workspace pointed at a temporary folder).
 */
class CodingHarnessTest {
    private fun sh(command: String, dir: File): Pair<Int, String> {
        val p = ProcessBuilder("/bin/sh", "-c", command).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    private fun has(program: String) = sh("command -v $program", File("/")).first == 0

    // Syntax checks

    @Test
    fun `each kind of file gets its checker, quoted, and unknown kinds none`() {
        val py = syntaxCheckFor("src/it's.py", null, typeScriptProject = false)!!
        assertTrue("python3 -I -c" in py.command && "'/workspace/src/it'\\''s.py'" in py.command, py.command)
        assertTrue("exit $NO_CHECKER" in py.command)
        assertTrue("node --check '/workspace/a.mjs'" in syntaxCheckFor("a.mjs", null, false)!!.command)
        assertEquals("gofmt -e", syntaxCheckFor("main.go", null, false)!!.name)
        assertEquals("sh -n", syntaxCheckFor("run.sh", "#!/bin/sh", false)!!.name)
        assertEquals("bash -n", syntaxCheckFor("run.sh", "#!/usr/bin/env bash", false)!!.name)
        assertEquals("ruby -c", syntaxCheckFor("a.rb", null, false)!!.name)
        assertEquals("php -l", syntaxCheckFor("a.php", null, false)!!.name)
        // TypeScript only inside a project, and only from the system PATH.
        assertNull(syntaxCheckFor("a.ts", null, typeScriptProject = false))
        val ts = syntaxCheckFor("a.tsx", null, typeScriptProject = true)!!
        assertTrue("command -v tsc" in ts.command && "node_modules" !in ts.command && "TS1[0-9]{3}" in ts.command, ts.command)
        assertNull(syntaxCheckFor("Main.kt", null, false))
        assertNull(syntaxCheckFor("data.json", null, false))
    }

    @Test
    fun `problems are shown with workspace paths, briefly`() {
        val many = (1..40).joinToString("\n") { "/workspace/a.py:$it:1: bad" }
        val shown = describeProblems("python3", many)!!
        assertTrue(shown.startsWith("Syntax check (python3) found problems") && "\na.py:1:1: bad" in shown && "/workspace" !in shown, shown)
        assertTrue("…25 more lines" in shown, shown)
        assertNull(describeProblems("python3", "  \n"))
    }

    @Test
    fun `the python check finds a syntax error without running the file`() {
        assumeTrue(has("python3"))
        val dir = Files.createTempDirectory("ws").toFile()
        try {
            File(dir, "bad.py").writeText("open('ran', 'w')\ndef f(:\n    pass\n")
            File(dir, "good.py").writeText("open('ran', 'w')\n")
            fun check(name: String) = sh(syntaxCheckFor(name, null, false)!!.command.replace("/workspace/", "${dir.path}/"), dir)
            val (code, out) = check("bad.py")
            assertEquals(1, code, out)
            assertTrue("bad.py:2:" in out, out)
            assertEquals(0, check("good.py").first)
            assertFalse(File(dir, "ran").exists(), "the check must not run the file")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the node check reports errors but not JSX`() {
        assumeTrue(has("node"))
        val dir = Files.createTempDirectory("ws").toFile()
        try {
            File(dir, "bad.js").writeText("function f( {\n")
            File(dir, "jsx.js").writeText("const a = <div/>;\n")
            fun check(name: String) = sh(syntaxCheckFor(name, null, false)!!.command.replace("/workspace/", "${dir.path}/"), dir)
            assertEquals(1, check("bad.js").first)
            assertEquals(0, check("jsx.js").first)
        } finally {
            dir.deleteRecursively()
        }
    }

    // git_commit

    @Test
    fun `commit commands switch off hooks and signing, quote everything and never push`() {
        val all = gitCommitCommand(null, "Fix it's bug\n\nDetails")
        assertTrue("add -A &&" in all && "core.hooksPath=/dev/null" in all && "commit.gpgSign=false" in all && "--no-verify" in all, all)
        assertTrue("-m 'Fix it'\\''s bug\n\nDetails'" in all, all)
        assertTrue("safe.directory" in all && "core.fsmonitor=false" in all)
        assertFalse("push" in all)
        assertTrue("add -A -- 'src/a b.kt' 'docs'" in gitCommitCommand(listOf("src/a b.kt", "docs"), "m"))
        assertFailsWith<ToolException> { gitCommitCommand(null, "  ") }
        assertFailsWith<ToolException> { gitCommitCommand(listOf("--all"), "m") }
        assertFailsWith<ToolException> { gitCommitCommand(null, "x".repeat(10_001)) }
        // The same guard against filter programs applies to git_inspect.
        assertTrue(gitCommand("status", null, null, staged = false, maxCount = null).startsWith(GIT_PRELUDE))
    }

    @Test
    fun `commit stages and commits for real, and refuses a repository that names a filter program`() {
        assumeTrue(has("git"))
        val dir = Files.createTempDirectory("repo").toFile()
        try {
            sh("git init -q . && git config user.name T && git config user.email t@example.com", dir)
            File(dir, "a.txt").writeText("a\n")
            File(dir, "b.txt").writeText("b\n")
            File(dir, ".git/hooks/pre-commit").apply { writeText("#!/bin/sh\ntouch hook-ran\nexit 1\n"); setExecutable(true) }
            val (code, out) = sh(gitCommitCommand(listOf("a.txt"), "Add a"), dir)
            assertEquals(0, code, out)
            assertTrue("Committed" in out && "a.txt" in out && "Add a" in out, out)
            assertFalse(File(dir, "hook-ran").exists(), "hooks must not run")
            assertTrue("?? b.txt" in sh("git status --short", dir).second)

            sh("git config filter.evil.clean 'touch filter-ran; cat'", dir)
            File(dir, ".gitattributes").writeText("* filter=evil\n")
            val (refused, why) = sh(gitCommitCommand(null, "All"), dir)
            assertEquals(3, refused, why)
            assertTrue("filter program" in why && "filter.evil.clean" in why, why)
            assertFalse(File(dir, "filter-ran").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    // Saved command output

    @Test
    fun `saved output is pruned to the newest and kept out of git`() {
        val dir = Files.createTempDirectory("ws").toFile()
        try {
            val out = File(dir, ".locus/out").apply { mkdirs() }
            (1..25).forEach { File(out, "cmd-20260101-0000${"%02d".format(it)}-abc.log").writeText("$it") }
            File(out, "keep.txt").writeText("not ours")
            pruneSavedOutput(out, keep = 20)
            val left = out.listFiles()!!.map { it.name }.sorted()
            assertEquals(21, left.size)
            assertFalse("cmd-20260101-000001-abc.log" in left)
            assertTrue("cmd-20260101-000025-abc.log" in left && "keep.txt" in left)
            assertEquals("*\n", File(dir, ".locus/.gitignore").readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `saved output names sort by time and the note says where to look`() {
        val name = savedOutputName(0)
        assertTrue(Regex("cmd-\\d{8}-\\d{6}-[0-9a-f]{3}\\.log").matches(name), name)
        val note = savedOutputNote(".locus/out/$name", 3 * 1024 * 1024, capped = false)
        assertTrue("3072 KB" in note && ".locus/out/$name" in note && "read_file" in note && "search_files" in note, note)
        assertNotNull(Regex("saving stopped at 8 MB").find(savedOutputNote("x", 1, capped = true)))
    }
}
