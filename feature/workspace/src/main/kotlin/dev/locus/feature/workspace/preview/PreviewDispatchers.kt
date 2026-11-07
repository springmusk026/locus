package dev.locus.feature.workspace.preview

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Where the file viewers read and decode files ([io]) and parse and highlight text ([compute]): off the
 * main thread in the app. A screenshot test provides a dispatcher that runs the work in place, so a
 * capture never waits on a worker thread it can't see (what @IoDispatcher is for view models).
 */
internal class PreviewDispatchers(val io: CoroutineDispatcher, val compute: CoroutineDispatcher)

internal val LocalPreviewDispatchers = staticCompositionLocalOf { PreviewDispatchers(io = Dispatchers.IO, compute = Dispatchers.Default) }
