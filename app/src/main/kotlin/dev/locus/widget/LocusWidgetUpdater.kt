package dev.locus.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.data.widget.WidgetFeed
import dev.locus.core.model.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes the widgets a new state whenever [WidgetFeed] changes: the agent's status (the flow the status
 * notification uses) or the latest task result for [LocusWidget], today's usage and the running task's
 * budget for [LocusUsageWidget]. No polling and no periodic updates. At most one update per [MIN_GAP_MS]
 * per widget, latest state wins, and none while that widget isn't placed; its figures aren't even
 * computed then. The first emission after the process starts also replaces a state left from before
 * Android killed Locus mid-run.
 */
@Singleton
class LocusWidgetUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val feed: WidgetFeed,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    /** Whether a usage widget is on the home screen: read at start, then kept by its receiver's onEnabled and onDisabled. */
    private val usageOn = MutableStateFlow(false)

    /** The same for the status widget. */
    private val statusOn = MutableStateFlow(false)

    /** A usage widget was placed (the first one) or removed (the last one). */
    fun usagePlaced(placed: Boolean) {
        usageOn.value = placed
    }

    /** A status widget was placed (the first one) or removed (the last one). */
    fun statusPlaced(placed: Boolean) {
        statusOn.value = placed
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        scope.launch {
            if (placed(LocusWidget::class.java)) statusOn.value = true
            statusOn.flatMapLatest { on -> if (on) feed.state else emptyFlow() }.distinctUntilChanged().conflate().collect {
                push(LocusWidget::class.java) { LocusWidget() }
                delay(MIN_GAP_MS)
            }
        }
        scope.launch {
            if (placed(LocusUsageWidget::class.java)) usageOn.value = true
            usageOn.flatMapLatest { on -> if (on) feed.usage else emptyFlow() }.distinctUntilChanged().conflate().collect {
                push(LocusUsageWidget::class.java) { LocusUsageWidget() }
                delay(MIN_GAP_MS)
            }
        }
    }

    private suspend fun placed(kind: Class<out GlanceAppWidget>): Boolean =
        runCatchingCancellable { GlanceAppWidgetManager(context).getGlanceIds(kind).isNotEmpty() }.getOrDefault(false)

    private suspend fun push(kind: Class<out GlanceAppWidget>, widget: () -> GlanceAppWidget) {
        try {
            if (GlanceAppWidgetManager(context).getGlanceIds(kind).isNotEmpty()) widget().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Locus", "Couldn't update the widget", e)
        }
    }

    private companion object {
        const val MIN_GAP_MS = 1_000L
    }
}
