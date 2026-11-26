package dev.locus.core.agent

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Names and clean-up of saved tool output (docs/design/tool-output.md). */
class SavedOutputTest {
    private val shape = Regex("\\d{8}-\\d{6}-[0-9a-f]{3}-web_read\\.log")

    @Test
    fun `names are unique, even for many calls in one second`() {
        // 5,000 draws from 4,096 values collide thousands of times; past 4,096 in a second, the name moves to the next second.
        val names = List(5_000) { SavedOutput.name("web_read", 1_700_000_000_000) }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { shape.matches(it) })
    }

    @Test
    fun `the files a run saved survive the prune however many there are`() {
        val root = Files.createTempDirectory("out").toFile()
        try {
            val dir = File(root, ".locus/out").apply { mkdirs() }
            val old = (1..10).map { File(dir, "20200101-0000${"%02d".format(it)}-abc-old.log").apply { writeText("old") } }
            val saved = (1..40).map { File(dir, SavedOutput.name("web_read")).apply { writeText("new") } }
            SavedOutput.prune(dir, keep = 5)
            assertTrue(saved.all { it.exists() }, "files this run saved were deleted")
            assertTrue(old.none { it.exists() }, "older files past the newest 5 were kept")
            assertEquals("*\n", File(root, ".locus/.gitignore").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
