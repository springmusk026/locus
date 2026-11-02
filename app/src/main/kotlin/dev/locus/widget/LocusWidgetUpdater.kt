package dev.locus.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.data.widget.WidgetFeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes the widget a new state whenever [WidgetFeed] changes: the agent's status (the flow the status
 * notification uses) or the latest task result. No polling and no periodic updates. At most one update
 * per [MIN_GAP_MS], latest state wins, and none while no widget is placed. The first emission after
 * the process starts also replaces a state left from before Android killed Locus mid-run.
 */
@Singleton
class LocusWidgetUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val feed: WidgetFeed,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            feed.state.distinctUntilChanged().conflate().collect {
                push()
                delay(MIN_GAP_MS)
            }
        }
    }

    private suspend fun push() {
        try {
            if (GlanceAppWidgetManager(context).getGlanceIds(LocusWidget::class.java).isNotEmpty()) LocusWidget().updateAll(context)
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
