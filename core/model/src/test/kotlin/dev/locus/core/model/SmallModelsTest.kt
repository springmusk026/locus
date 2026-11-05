package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The size line for tools: under 1B parameters an on-device model is chat only. */
class SmallModelsTest {
    @Test
    fun `size labels read as billions of parameters`() {
        assertEquals(0.8, SmallModels.billions("0.8B"))
        assertEquals(0.135, SmallModels.billions("135M")!!, 1e-9)
        assertEquals(1.2, SmallModels.billions("1.2B"))
        assertEquals(56.0, SmallModels.billions("8x7B"))
        // A mixture of experts: its total, the first number.
        assertEquals(30.0, SmallModels.billions("30B-A3B"))
        // LiteRT's Gemma names: E2B is 2B effective.
        assertEquals(2.0, SmallModels.billions("E2B"))
        assertEquals(2.0, SmallModels.billions("Qwen3.5 2B"))
        assertNull(SmallModels.billions(null))
        assertNull(SmallModels.billions("instruct"))
    }

    @Test
    fun `under 1B is chat only, while 1B and up and unknown sizes keep their tools`() {
        assertTrue(SmallModels.tooSmallForTools("0.8B"))
        assertTrue(SmallModels.tooSmallForTools("500M"))
        assertFalse(SmallModels.tooSmallForTools("1B"))
        assertFalse(SmallModels.tooSmallForTools("1.2B"))
        assertFalse(SmallModels.tooSmallForTools("E2B"))
        assertFalse(SmallModels.tooSmallForTools(null))

        assertFalse(SmallModels.callsTools(templateTools = true, sizeLabel = "0.8B"))
        assertTrue(SmallModels.callsTools(templateTools = true, sizeLabel = "2B"))
        assertTrue(SmallModels.callsTools(templateTools = true, sizeLabel = null))
        assertFalse(SmallModels.callsTools(templateTools = false, sizeLabel = "4B"))
    }
}
