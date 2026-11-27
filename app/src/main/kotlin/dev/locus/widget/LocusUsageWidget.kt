package dev.locus.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.android.EntryPointAccessors
import dev.locus.LaunchActions
import dev.locus.MainActivity
import dev.locus.R
import dev.locus.core.data.widget.UsageWidgetModel
import dev.locus.core.designsystem.LocusDarkColors
import dev.locus.core.designsystem.LocusLightColors
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.WidgetSizes
import kotlinx.coroutines.flow.first

/**
 * The usage widget (docs/design/android-bridge.md, "The usage widget"): today's estimated spend (or tokens)
 * across every provider, as Insights counts it, then, as the widget grows, today's tokens and requests, the
 * last seven days as bars, and the running task's use of its budget. A tap opens Insights. Text and plain
 * coloured boxes only (no bitmaps, nothing for Android 17's widget memory limit to count), no periodic
 * updates: [LocusWidgetUpdater] pushes it when the figures change, while one is placed.
 */
class LocusUsageWidget : GlanceAppWidget() {
    // Composed for each size the launcher reports, so a bigger widget shows more ([WidgetLayout.usage]).
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val feed = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).widgetFeed()
        // Load before composing, so the widget never flashes an empty state.
        val initial = feed.usage.first()
        val open = insightsIntent(context)
        provideContent {
            val model by feed.usage.collectAsState(initial)
            GlanceTheme(colors = Colors) { Content(context, model, open) }
        }
    }

    @Composable
    private fun Content(context: Context, model: UsageWidgetModel, open: Intent) {
        val colors = GlanceTheme.colors
        val res = context.resources
        val plan = WidgetLayout.usage(LocalSize.current.height.value, res.configuration.fontScale, model.tokens != null, model.bars.isNotEmpty(), model.task != null)
        Column(
            GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(WidgetSizes.corner).background(colors.widgetBackground)
                .padding(WidgetSizes.padding).clickable(actionStartActivity(open)),
        ) {
            // Glance's TextStyle takes Glance colour providers, from Locus's palettes through GlanceTheme.
            if (plan.note) Line(UsageWords.note(res, model.note), colors.onSurfaceVariant)
            Text(
                UsageWords.figure(res, model.figure),
                style = TextStyle(color = colors.onSurface, fontSize = WidgetSizes.headlineText, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            val tokens = model.tokens?.takeIf { plan.tokens }?.let { UsageWords.tokens(res, it) }
            val requests = model.requests.takeIf { plan.requests && it > 0 }?.let { UsageWords.requests(res, it) }
            // Tokens and requests, a line each, in one text: Glance allows at most ten children in a column.
            listOfNotNull(tokens, requests).takeIf { it.isNotEmpty() }?.let { Line(it.joinToString("\n"), colors.onSurfaceVariant, maxLines = it.size) }
            model.task?.takeIf { plan.taskLines > 0 }?.let { task ->
                Spacer(GlanceModifier.height(Spacing.sm))
                Line(UsageWords.task(res, task), colors.primary, maxLines = plan.taskLines)
            }
            if (plan.strip) {
                Spacer(GlanceModifier.defaultWeight())
                Week(model.bars, plan.stripHeight.dp, res.getString(R.string.widget_usage_week_description))
                Spacer(GlanceModifier.height(Spacing.xxs))
                Line(UsageWords.week(res, model.week), colors.onSurfaceVariant)
            }
        }
    }

    /** The last seven days as bars, today last and in the accent colour: plain coloured boxes, no bitmaps. */
    @Composable
    private fun Week(bars: List<Float>, height: Dp, description: String) {
        val colors = GlanceTheme.colors
        val span = height - WidgetSizes.barMin
        Row(
            GlanceModifier.fillMaxWidth().height(height).semantics { contentDescription = description },
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEachIndexed { i, fraction ->
                Box(GlanceModifier.defaultWeight().fillMaxHeight().padding(horizontal = WidgetSizes.barGap / 2), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        GlanceModifier.fillMaxWidth().height(WidgetSizes.barMin + span * fraction.coerceIn(0f, 1f)).cornerRadius(WidgetSizes.barCorner)
                            .background(if (i == bars.lastIndex) colors.primary else colors.primaryContainer),
                    ) {}
                }
            }
        }
    }

    @Composable
    private fun Line(text: String, color: ColorProvider, maxLines: Int = 1) {
        Text(text, style = TextStyle(color = color, fontSize = WidgetSizes.lineText), maxLines = maxLines) // design-exception: Glance text takes a GlanceTheme colour provider (a Locus palette role), not Compose colours
    }

    private companion object {
        val Colors = ColorProviders(light = LocusLightColors, dark = LocusDarkColors)

        /** Insights, where these figures come from. */
        fun insightsIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .setAction(LaunchActions.INSIGHTS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

/**
 * Android starts this by name from the manifest (app/r8-kept-names.txt). Placing the first usage widget
 * or removing the last one turns the updater's figures on or off, so nothing is computed for a widget
 * that isn't there.
 */
class LocusUsageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LocusUsageWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).widgetUpdater().usagePlaced(true)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).widgetUpdater().usagePlaced(false)
    }
}
