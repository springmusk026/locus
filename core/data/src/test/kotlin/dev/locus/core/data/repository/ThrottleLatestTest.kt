package dev.locus.core.data.repository

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/** The Activity text search is shown at most every half second while chats are written (docs/design/search.md, "Activity's queries"). */
class ThrottleLatestTest {
    @Test
    fun `a burst shows its first and last result, not each in turn`() = runTest {
        val shown = flow { (1..50).forEach { emit(it) } }.throttleLatest(500).toList()
        assertEquals(listOf(1, 50), shown)
    }

    @Test
    fun `a result after the pause is shown at once, the first one without waiting`() = runTest {
        val at = mutableListOf<Pair<Int, Long>>()
        flow {
            emit(1)
            delay(2_000)
            emit(2)
        }.throttleLatest(500).collect { at += it to currentTime }
        assertEquals(listOf(1 to 0L, 2 to 2_000L), at)
    }
}
