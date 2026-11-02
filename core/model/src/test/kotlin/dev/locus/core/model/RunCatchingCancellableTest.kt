package dev.locus.core.model

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RunCatchingCancellableTest {
    @Test
    fun `failures become results, cancellation is rethrown`() {
        assertEquals(3, runCatchingCancellable { 1 + 2 }.getOrNull())
        assertTrue(runCatchingCancellable<Int> { error("boom") }.isFailure)
        assertFailsWith<CancellationException> { runCatchingCancellable<Int> { throw CancellationException("cancelled") } }
    }
}
