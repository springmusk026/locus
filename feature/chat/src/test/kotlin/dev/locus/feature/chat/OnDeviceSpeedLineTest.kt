package dev.locus.feature.chat

import dev.locus.core.ondevice.Accelerator
import dev.locus.core.ondevice.TurnSpeed
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class OnDeviceSpeedLineTest {
    private val speed = TurnSpeed(Accelerator.GPU, null, null, 40, 18.2, measured = false)

    @Test fun matchingReplyGetsTheSpeedLine() {
        val items = listOf<ChatItem>(ChatItem.Assistant("a", "Hello there.\n"), ChatItem.Assistant("b", "Other"))
        val out = items.withSpeeds(mapOf("Hello there." to speed))
        assertEquals("GPU · ≈18 tok/s", (out[0] as ChatItem.Assistant).speed)
        assertNull((out[1] as ChatItem.Assistant).speed)
    }

    @Test fun noSpeedsLeavesItemsUntouched() {
        val items = listOf<ChatItem>(ChatItem.Assistant("a", "Hi"))
        assertSame(items, items.withSpeeds(emptyMap()))
    }
}
