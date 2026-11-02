package dev.locus.core.model

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for code that suspends: a failure becomes a [Result], but cancellation is rethrown,
 * so a cancelled coroutine (a `collectLatest` or `mapLatest` restarting, a scope closing) stops here
 * instead of carrying on with a fallback value. Inline, so [block] may call suspend functions.
 */
inline fun <R> runCatchingCancellable(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
