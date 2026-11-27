package dev.locus.widget

import org.junit.Test
import kotlin.test.assertEquals

/** What each widget shows at each size (docs/design/android-bridge.md, "Sizes"). Heights in dp. */
class WidgetLayoutTest {

    // The status widget: 2 cells tall is 110dp at its smallest; the phone it was reported on gives ~188dp.

    @Test
    fun `at its smallest the status widget is the status line and Ask Locus`() {
        assertEquals(WidgetLayout.Status(next = false, runLabel = false, runLines = 0), WidgetLayout.status(110f, 1f, hasRun = true, hasNext = true))
    }

    @Test
    fun `a little taller adds the answer's first line, then the next run`() {
        assertEquals(WidgetLayout.Status(next = true, runLabel = false, runLines = 1), WidgetLayout.status(140f, 1f, hasRun = true, hasNext = true))
        // Idle with no run yet: the next run alone.
        assertEquals(WidgetLayout.Status(next = true, runLabel = false, runLines = 0), WidgetLayout.status(140f, 1f, hasRun = false, hasNext = true))
    }

    @Test
    fun `a 4x2 widget shows the next run, the run's title and time, and two lines`() {
        assertEquals(WidgetLayout.Status(next = true, runLabel = true, runLines = 2), WidgetLayout.status(188f, 1f, hasRun = true, hasNext = true))
        // Not idle (no next line): one more line of the answer.
        assertEquals(WidgetLayout.Status(next = false, runLabel = true, runLines = 3), WidgetLayout.status(188f, 1f, hasRun = true, hasNext = false))
    }

    @Test
    fun `a tall widget gives the answer up to its most lines`() {
        assertEquals(WidgetLayout.MAX_RUN_LINES, WidgetLayout.status(400f, 1f, hasRun = true, hasNext = true).runLines)
    }

    @Test
    fun `larger text gets fewer lines rather than cut ones`() {
        assertEquals(WidgetLayout.Status(next = true, runLabel = true, runLines = 1), WidgetLayout.status(188f, 1.3f, hasRun = true, hasNext = true))
    }

    // The usage widget: 2x2 is about 110x110dp; the phone's was ~167x188dp.

    @Test
    fun `the smallest usage widget shows the figure and what it is`() {
        assertEquals(WidgetLayout.Usage(note = true, tokens = false, strip = false, requests = false, taskLines = 0), WidgetLayout.usage(80f, 1f, hasTokens = true, hasWeek = true, hasTask = false))
    }

    @Test
    fun `a 2x2 usage widget adds today's tokens`() {
        assertEquals(WidgetLayout.Usage(note = true, tokens = true, strip = false, requests = false, taskLines = 0), WidgetLayout.usage(110f, 1f, hasTokens = true, hasWeek = true, hasTask = false))
    }

    @Test
    fun `a taller usage widget adds the week and today's requests`() {
        assertEquals(WidgetLayout.Usage(note = true, tokens = true, strip = true, requests = true, taskLines = 0), WidgetLayout.usage(188f, 1f, hasTokens = true, hasWeek = true, hasTask = false))
        // A running task's budget comes before the week; the requests give way.
        assertEquals(WidgetLayout.Usage(note = true, tokens = true, strip = true, requests = false, taskLines = 1), WidgetLayout.usage(188f, 1f, hasTokens = true, hasWeek = true, hasTask = true))
        // No week without use.
        assertEquals(false, WidgetLayout.usage(188f, 1f, hasTokens = true, hasWeek = false, hasTask = false).strip)
    }
}
