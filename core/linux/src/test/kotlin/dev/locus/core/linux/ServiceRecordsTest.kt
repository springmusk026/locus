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

    @Test
    fun `a service Android killed in the background says so, with the fix`() {
        val killed = describeService(LinuxEnvironment.ServiceStatus("web", "npx serve", running = false, lostOnRestart = false, startedAt = 1, killedByAndroid = true))
        assertTrue(killed.startsWith("web (stopped: Killed by Android (phantom process limit)"), killed)
        assertTrue("Disable child process restrictions" in killed && AndroidKill.HELP_URL in killed && "linux_service_start" in killed)
        assertTrue(AndroidKill.applies(137, inBackground = true))
        assertFalse(AndroidKill.applies(137, inBackground = false))
        assertFalse(AndroidKill.applies(1, inBackground = true))
        assertFalse(AndroidKill.applies(null, inBackground = true))
    }

    @Test
    fun `a project's service keeps its folder, so a restart mounts only that folder again`() {
        ServiceRecords(file).apply {
            add(ServiceRecords.Record("web", "npx serve", "/workspace", 1, folder = "projects/site"))
            add(ServiceRecords.Record("api", "python3 app.py", "/workspace", 2))
        }
        val read = ServiceRecords(file).all()
        assertEquals("projects/site", read.single { it.name == "web" }.folder)
        assertEquals(null, read.single { it.name == "api" }.folder)
        // Records written before the folder was kept read as the whole workspace, as they ran.
        file.writeText("""[{"name":"old","command":"sh","cwd":"/workspace","startedAt":3}]""")
        assertEquals(null, ServiceRecords(file).all().single().folder)
    }
}
