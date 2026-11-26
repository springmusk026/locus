package dev.locus.core.linux

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pid that lets Locus send proot its SIGQUIT (#312). */
class ProcessIdsTest {
    @Test
    fun `a real process's pid is found`() {
        // The JVM closes its process class's field to reflection, as a future Android might.
        val process = ProcessBuilder("/bin/sh", "-c", "echo \$\$; exec sleep 5").start()
        try {
            val reported = process.inputStream.bufferedReader().readLine().trim().toInt()
            assertEquals(reported, ProcessIds.of(process))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `reads ART's and OpenJDK's descriptions`() {
        assertEquals(4321, ProcessIds.fromDescription("Process[pid=4321, hasExited=false]"))
        assertEquals(77, ProcessIds.fromDescription("Process[pid=77, exitValue=\"not exited\"]"))
        assertNull(ProcessIds.fromDescription("java.lang.UNIXProcess@1a2b3c"))
        assertNull(ProcessIds.fromDescription("Process[ppid=5]"))
    }
}
