package dev.locus.core.model

import dev.locus.core.model.Verification.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recognising build and test commands, and reading their output for the verification card. */
class VerificationTest {
    private fun kind(command: String) = Verification.detect(command)?.kind

    @Test
    fun `common build and test commands are recognised`() {
        assertEquals(Kind.TEST, kind("./gradlew test"))
        assertEquals(Kind.TEST, kind("./gradlew :app:testDebugUnitTest --console=plain"))
        assertEquals(Kind.TEST, kind("gradle check"))
        assertEquals(Kind.BUILD, kind("./gradlew assembleDebug"))
        assertEquals(Kind.BUILD, kind("./gradlew build"))
        assertEquals(Kind.TEST, kind("npm test"))
        assertEquals(Kind.TEST, kind("npm run test:unit"))
        assertEquals(Kind.BUILD, kind("pnpm build"))
        assertEquals(Kind.BUILD, kind("yarn run build"))
        assertEquals(Kind.CHECK, kind("npm run lint"))
        assertEquals(Kind.TEST, kind("npx vitest run"))
        assertEquals(Kind.CHECK, kind("npx tsc --noEmit"))
        assertEquals(Kind.TEST, kind("pytest -q tests/"))
        assertEquals(Kind.TEST, kind("python3 -m pytest -x"))
        assertEquals(Kind.TEST, kind("uv run pytest"))
        assertEquals(Kind.TEST, kind("python3 -m unittest discover"))
        assertEquals(Kind.TEST, kind("cargo test --all"))
        assertEquals(Kind.BUILD, kind("cargo build --release"))
        assertEquals(Kind.CHECK, kind("cargo clippy"))
        assertEquals(Kind.TEST, kind("go test ./..."))
        assertEquals(Kind.BUILD, kind("go build ./..."))
        assertEquals(Kind.CHECK, kind("go vet ./..."))
        assertEquals(Kind.TEST, kind("mvn -q test"))
        assertEquals(Kind.BUILD, kind("mvn -q package"))
        assertEquals(Kind.TEST, kind("make test"))
        assertEquals(Kind.BUILD, kind("make"))
        assertEquals(Kind.TEST, kind("ctest --test-dir build"))
        assertEquals(Kind.TEST, kind("dotnet test"))
        assertEquals(Kind.TEST, kind("bundle exec rspec"))
        assertEquals(Kind.TEST, kind("vendor/bin/phpunit"))
    }

    @Test
    fun `project_info's commands, with cd, venvs and env, are recognised`() {
        assertEquals(Kind.TEST, kind("cd api && . .venv/bin/activate && python -m pytest -q"))
        assertEquals(Kind.TEST, kind("cd app && ./gradlew test"))
        assertEquals(Kind.BUILD, kind("cmake -B build && cmake --build build"))
        assertEquals(Kind.TEST, kind("CI=1 timeout 600 npm test 2>&1 | tail -50"))
        assertEquals("pytest", Verification.detect("cd x; pytest")?.tool)
    }

    @Test
    fun `other commands aren't`() {
        assertNull(kind("ls -la"))
        assertNull(kind("npm install"))
        assertNull(kind("npm run dev"))
        assertNull(kind("python3 app.py"))
        assertNull(kind("git status"))
        assertNull(kind("cargo run"))
        assertNull(kind("./gradlew tasks"))
        assertNull(kind("apk add nodejs npm"))
        assertNull(kind("cat test.txt"))
    }

    @Test
    fun `gradle failures name the failing tests`() {
        val out = """
            Exit code 1 (84.2 s).
            > Task :app:testDebugUnitTest

            com.example.ParserTest > parsesDates FAILED
                java.lang.AssertionError at ParserTest.kt:42

            com.example.ParserTest > handlesEmpty FAILED
                java.lang.NullPointerException at Parser.kt:10

            12 tests completed, 2 failed

            FAILURE: Build failed with an exception.
        """.trimIndent()
        val o = Verification.parse(Verification.detect("./gradlew test")!!, out, isError = true)
        assertEquals(false, o.passed)
        assertEquals(1, o.exitCode)
        assertEquals(84_200L, o.durationMs)
        assertEquals("12 tests completed, 2 failed", o.summary)
        assertEquals(listOf("com.example.ParserTest > parsesDates", "com.example.ParserTest > handlesEmpty"), o.failures)
        assertEquals("Tests failed", Verification.title(o))
    }

    @Test
    fun `a gradle build error shows what went wrong`() {
        val out = """
            Exit code 1 (30.0 s).
            e: file:///workspace/app/src/Main.kt:3:5 Unresolved reference 'foo'.

            FAILURE: Build failed with an exception.

            * What went wrong:
            Execution failed for task ':app:compileKotlin'.
            > Compilation error. See log for more details

            * Try:
            > Run with --stacktrace option to get the stack trace.
        """.trimIndent()
        val o = Verification.parse(Verification.detect("./gradlew build")!!, out, isError = true)
        assertEquals("Build failed", Verification.title(o))
        assertEquals(listOf("Execution failed for task ':app:compileKotlin'.", "> Compilation error. See log for more details"), o.errors)
    }

    @Test
    fun `pytest names failures and keeps its tally`() {
        val out = """
            Exit code 1 (2.1 s).
            ..F.F
            =========================== short test summary info ============================
            FAILED tests/test_api.py::test_login - AssertionError: assert 401 == 200
            FAILED tests/test_api.py::test_logout
            ========================= 2 failed, 3 passed in 1.92s ==========================
        """.trimIndent()
        val o = Verification.parse(Verification.detect("pytest")!!, out, isError = true)
        assertEquals(listOf("tests/test_api.py::test_login", "tests/test_api.py::test_logout"), o.failures)
        assertEquals("2 failed, 3 passed", o.summary)
    }

    @Test
    fun `jest, cargo, go and dotnet failures`() {
        val jest = Verification.parse(
            Verification.detect("npm test")!!,
            "Exit code 1 (5.0 s).\nFAIL src/sum.test.js\n  ● sum › adds numbers\n\n    expect(received).toBe(expected)\n\nTests:       1 failed, 4 passed, 5 total",
            isError = true,
        )
        assertEquals(listOf("sum › adds numbers"), jest.failures)
        assertEquals("1 failed, 4 passed, 5 total", jest.summary)

        val cargo = Verification.parse(
            Verification.detect("cargo test")!!,
            "Exit code 101 (9.9 s).\ntest tests::it_works ... ok\ntest tests::it_fails ... FAILED\n\ntest result: FAILED. 1 passed; 1 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s",
            isError = true,
        )
        assertEquals(listOf("tests::it_fails"), cargo.failures)
        assertEquals("1 passed; 1 failed; 0 ignored; 0 measured; 0 filtered out", cargo.summary)

        val go = Verification.parse(
            Verification.detect("go test ./...")!!,
            "Exit code 1 (3.0 s).\n--- FAIL: TestAdd (0.00s)\n    add_test.go:9: got 3, want 4\nFAIL\nFAIL\texample.com/m\t0.002s",
            isError = true,
        )
        assertEquals(listOf("TestAdd"), go.failures)

        val dotnet = Verification.parse(
            Verification.detect("dotnet test")!!,
            "Exit code 1 (20.0 s).\n  Failed Calc.Tests.AddsTwo [12 ms]\nFailed!  - Failed:     1, Passed:     9, Skipped:     0, Total:    10",
            isError = true,
        )
        assertEquals(listOf("Calc.Tests.AddsTwo"), dotnet.failures)
    }

    @Test
    fun `a pass, a timeout, and errors without test names`() {
        val pass = Verification.parse(Verification.detect("go test ./...")!!, "Exit code 0 (1.2 s).\nok  \texample.com/m\t0.002s", isError = false)
        assertEquals(true, pass.passed)
        assertEquals("Tests passed", Verification.title(pass))
        assertTrue(pass.failures.isEmpty() && pass.errors.isEmpty())

        val slow = Verification.parse(Verification.detect("npm run build")!!, "Stopped after 120s (timeout). If it needs longer, raise timeout_seconds.\nbuilding…", isError = true)
        assertNull(slow.passed)
        assertEquals("Build timed out", Verification.title(slow))

        val tsc = Verification.parse(
            Verification.detect("npx tsc --noEmit")!!,
            "Exit code 2 (4.0 s).\nsrc/a.ts(3,7): error TS2322: Type 'string' is not assignable to type 'number'.\nFound 1 error.",
            isError = true,
        )
        assertEquals(listOf("src/a.ts(3,7): error TS2322: Type 'string' is not assignable to type 'number'."), tsc.errors)

        // An older result without a duration, and output with colour codes.
        val old = Verification.parse(Verification.detect("pytest")!!, "Exit code 1.\n\u001B[31mFAILED\u001B[0m t.py::x", isError = true)
        assertNull(old.durationMs)
        assertEquals(listOf("t.py::x"), old.failures)
    }

    @Test
    fun `long failure lists are cut, with the full count kept`() {
        val out = "Exit code 1 (1.0 s).\n" + (1..20).joinToString("\n") { "FAILED t.py::test_$it" }
        val o = Verification.parse(Verification.detect("pytest")!!, out, isError = true)
        assertEquals(Verification.MAX_ITEMS, o.failures.size)
        assertEquals(20, o.failureCount)
    }
}
