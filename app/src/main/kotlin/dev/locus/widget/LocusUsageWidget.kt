package dev.locus.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dagger.hilt.android.EntryPointAccessors
import dev.locus.LaunchActions
import dev.locus.MainActivity
import dev.locus.core.data.widget.UsageWidgetModel
import dev.locus.core.designsystem.LocusDarkColors
import dev.locus.core.designsystem.LocusLightColors
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.WidgetSizes
import kotlinx.coroutines.flow.first

/**
 * The usage widget (docs/design/android-bridge.md, "The usage widget"): today's estimated spend (or tokens)
 * across every provider, as Insights counts it, and the running task's use of its budget. A tap opens
 * Insights. Text only (no bitmaps, nothing for Android 17's widget memory limit to count), one layout, no
 * periodic updates: [LocusWidgetUpdater] pushes it when the figures change, while one is placed.
 */
class LocusUsageWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val feed = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).widgetFeed()
        // Load before composing, so the widget never flashes an empty state.
        val initial = feed.usage.first()
        val open = insightsIntent(context)
        provideContent {
            val model by feed.usage.collectAsState(initial)
            GlanceTheme(colors = Colors) { Content(model, open) }
        }
    }

    @Composable
    private fun Content(model: UsageWidgetModel, open: Intent) {
        val colors = GlanceTheme.colors
        Column(
            GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(WidgetSizes.corner).background(colors.widgetBackground)
                .padding(WidgetSizes.padding).clickable(actionStartActivity(open)),
        ) {
            // Glance's TextStyle takes Glance colour providers, from Locus's palettes through GlanceTheme.
            Text(
                model.detail,
                style = TextStyle(color = colors.onSurfaceVariant, fontSize = WidgetSizes.lineText),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(Spacing.xxs))
            Text(
                model.headline,
                style = TextStyle(color = colors.onSurface, fontSize = WidgetSizes.headlineText, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            model.task?.let { line ->
                Spacer(GlanceModifier.height(Spacing.sm))
                Text(
                    line,
                    style = TextStyle(color = colors.primary, fontSize = WidgetSizes.lineText),
                    maxLines = 2,
                )
            }
        }
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
