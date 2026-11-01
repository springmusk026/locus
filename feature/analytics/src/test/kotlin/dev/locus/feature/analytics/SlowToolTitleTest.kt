package dev.locus.feature.analytics

import dev.locus.core.model.toolActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SlowToolTitleTest {
    @Test
    fun `tools of the same kind get their own titles`() {
        assertNotEquals(slowToolTitle("agent_spawn"), slowToolTitle("agent_wait"))
        assertEquals("Wait for helpers", slowToolTitle("agent_wait"))
    }

    @Test
    fun `wording that needs arguments falls back to the kind of work`() {
        assertEquals(toolActivity("write_file"), slowToolTitle("write_file"))
    }
}
