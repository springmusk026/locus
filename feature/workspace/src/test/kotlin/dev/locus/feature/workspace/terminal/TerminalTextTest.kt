package dev.locus.feature.workspace.terminal

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.linux.EngineText
import dev.locus.core.linux.ProbeException
import dev.locus.core.model.LINUX_INSTALL_PATH
import dev.locus.core.terminal.TabRegistry
import dev.locus.core.terminal.TerminalTab
import dev.locus.feature.workspace.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The terminal's and the packages screen's text from core/terminal and core/linux, rendered from resources
 * in English, reads exactly as the English those modules keep (docs/design/localization.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TerminalTextTest {
    private val res: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `a tab's name and the tab limit read as the English`() {
        assertEquals(TerminalTab(1, 2).label, res.getString(R.string.workspace_terminal_tab_shell, 2))
        assertEquals(
            "At most ${TabRegistry.MAX_TABS} terminals can be open. Close one first.",
            res.getQuantityString(dev.locus.core.terminal.R.plurals.terminal_too_many_tabs, TabRegistry.MAX_TABS, TabRegistry.MAX_TABS),
        )
        assertEquals(LINUX_INSTALL_PATH, res.getString(dev.locus.core.linux.R.string.linux_install_path))
    }

    @Test
    fun `the packages screen's errors read as the English`() {
        fun en(text: EngineText) = text.asString(res)
        listOf(
            ProbeException(EngineText.Res(dev.locus.core.linux.R.string.linux_packages_remove_timed_out, "htop"), "Removing htop took longer than 2 minutes."),
            ProbeException(EngineText.Res(dev.locus.core.linux.R.string.linux_packages_updates_timed_out), "Checking for updates took longer than 2 minutes."),
            ProbeException(EngineText.Res(dev.locus.core.linux.R.string.linux_packages_index_failed, "ERROR: x"), "Couldn't refresh the package index: ERROR: x"),
            ProbeException(EngineText.Res(dev.locus.core.linux.R.string.linux_packages_index_failed_plain), "Couldn't refresh the package index:"),
        ).forEach { assertEquals(it.message, en(it.text)) }
    }
}
