package dev.locus.core.linux

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceRecordsTest {
    private val dir: File = Files.createTempDirectory("linux").toFile()
    private val file = File(dir, "services.json")

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `records outlive the process that wrote them`() {
        ServiceRecords(file).apply {
            add(ServiceRecords.Record("web", "npx serve -l 3000", "/workspace/site", 2))
            add(ServiceRecords.Record("api", "python3 app.py", "/workspace", 1))
            add(ServiceRecords.Record("web", "npx serve -l 4000", "/workspace/site", 3)) // a restart replaces it
        }
        // A new app process reads the same file.
        val after = ServiceRecords(file).all()
        assertEquals(listOf("api", "web"), after.map { it.name })
        assertEquals("npx serve -l 4000", after.last().command)
        assertEquals("/workspace/site", after.last().cwd)
    }

    @Test
    fun `stopping forgets a record, and a broken file reads as empty`() {
        val records = ServiceRecords(file)
        records.add(ServiceRecords.Record("web", "x", "/workspace", 1))
        assertTrue(records.remove("web"))
        assertFalse(records.remove("web"))
        assertTrue(records.all().isEmpty())
        file.writeText("{not json")
        assertTrue(ServiceRecords(file).all().isEmpty())
    }

    @Test
    fun `a service a restart ended is reported as stopped with what to do`() {
        val lost = describeService(LinuxEnvironment.ServiceStatus("web", "npx serve", running = false, lostOnRestart = true, startedAt = 1))
        assertTrue(lost.startsWith("web (stopped: Locus restarted") && "linux_service_start" in lost)
        assertEquals("web (running): npx serve", describeService(LinuxEnvironment.ServiceStatus("web", "npx serve", running = true, lostOnRestart = false, startedAt = 1)))
        assertEquals("web (stopped): npx serve", describeService(LinuxEnvironment.ServiceStatus("web", "npx serve", running = false, lostOnRestart = false, startedAt = 1)))
    }
}
