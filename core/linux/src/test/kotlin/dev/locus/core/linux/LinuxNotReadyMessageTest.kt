package dev.locus.core.linux

import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxNotReadyMessageTest {
    @Test
    fun readyHasNoMessage() {
        assertNull(linuxNotReadyMessage(LinuxStatus.Ready))
    }

    @Test
    fun notInstalledTellsTheAgentToAskTheUser() {
        for (status in listOf(LinuxStatus.NotInstalled, LinuxStatus.Failed("network down"))) {
            val message = assertNotNull(linuxNotReadyMessage(status))
            assertTrue("Ask the user" in message, message)
            assertTrue("Settings" in message, message)
            assertTrue("alpinelinux.org" in message, message)
            assertTrue("F-Droid" in message, message)
        }
    }

    @Test
    fun installingAndUnavailableExplainThemselves() {
        assertTrue("Unpacking" in linuxNotReadyMessage(LinuxStatus.Installing("Unpacking"))!!)
        assertTrue("no proot" in linuxNotReadyMessage(LinuxStatus.Unavailable("no proot"))!!)
    }
}
