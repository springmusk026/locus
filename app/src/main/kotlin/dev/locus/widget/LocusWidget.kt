package dev.locus.widget

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.LocalSize
import androidx.glance.ImageProvider
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
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.runtime.collectAsState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.locus.LaunchActions
import dev.locus.MainActivity
import dev.locus.R
import dev.locus.core.data.agent.OpenChatLink
import dev.locus.core.data.widget.WidgetFeed
import dev.locus.core.data.widget.WidgetModel
import dev.locus.core.data.widget.WidgetTone
import dev.locus.core.designsystem.LocusDarkColors
import dev.locus.core.designsystem.LocusLightColors
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.WidgetSizes
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/**
 * The Locus home-screen widget (docs/design/android-bridge.md): the agent's status line, the latest
 * scheduled run (its title and time, then as much of its answer as fits), the next run while idle, and
 * an Ask Locus button that opens home's ask bar.
 *
 * It only opens Locus. Tapping the status while an approval or question waits opens that chat in the
 * app; nothing is approved, denied or answered from the home screen. Locus's own palette, light and
 * dark; no bitmaps (only a vector dot), so it stays far below Android 17's widget bitmap memory limit.
 */
class LocusWidget : GlanceAppWidget() {
    // Composed for each size the launcher reports (usually portrait and landscape), so the answer gets as
    // many lines as fit ([WidgetLayout.status]); text and a vector only, so each composition is cheap.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val feed = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).widgetFeed()
        // Load before composing, so the widget never flashes an empty state.
        val initial = feed.state.first()
        val ask = askIntent(context)
        provideContent {
            val model by feed.state.collectAsState(initial)
            GlanceTheme(colors = Colors) { Content(context, model, ask, openIntent(context, model.status.chatId)) }
        }
    }

    @Composable
    private fun Content(context: Context, model: WidgetModel, ask: Intent, open: Intent) {
        val colors = GlanceTheme.colors
        val res = context.resources
        val plan = WidgetLayout.status(LocalSize.current.height.value, res.configuration.fontScale, model.run != null, model.next != null)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val is24Hour = DateFormat.is24HourFormat(context)
        Column(
            GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(WidgetSizes.corner).background(colors.widgetBackground).padding(WidgetSizes.padding),
        ) {
            Column(GlanceModifier.fillMaxWidth().defaultWeight().clickable(actionStartActivity(open))) {
                StatusRow(WidgetWords.status(res, model.status.says), model.status.tone)
                model.next?.takeIf { plan.next }?.let { next ->
                    Spacer(GlanceModifier.height(Spacing.xxs))
                    Line(WidgetWords.next(res, next, WidgetWords.time(res, next.at, now, zone, is24Hour)), colors.primary, maxLines = 1)
                }
                model.run?.takeIf { plan.runLines > 0 }?.let { run ->
                    Spacer(GlanceModifier.height(Spacing.xs))
                    if (plan.runLabel) Line(WidgetWords.runLabel(res, run, WidgetWords.time(res, run.at, now, zone, is24Hour)), colors.onSurfaceVariant, maxLines = 1)
                    Line(WidgetWords.runText(res, run), colors.onSurface, maxLines = plan.runLines)
                }
            }
            Spacer(GlanceModifier.height(Spacing.sm))
            Button(
                text = res.getString(R.string.widget_ask),
                onClick = actionStartActivity(ask),
                modifier = GlanceModifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(backgroundColor = colors.primary, contentColor = colors.onPrimary),
            )
        }
    }

    @Composable
    private fun StatusRow(text: String, tone: WidgetTone) {
        val colors = GlanceTheme.colors
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_dot),
                contentDescription = null,
                colorFilter = ColorFilter.tint(
                    when (tone) {
                        WidgetTone.WAITING -> colors.tertiary
                        WidgetTone.WORKING -> colors.primary
                        WidgetTone.IDLE -> colors.outline
                    },
                ),
                modifier = GlanceModifier.size(WidgetSizes.dot),
            )
            Spacer(GlanceModifier.width(Spacing.sm))
            Text(text, style = TextStyle(color = colors.onSurface, fontSize = WidgetSizes.statusText, fontWeight = FontWeight.Medium), maxLines = 1)
        }
    }

    @Composable
    private fun Line(text: String, color: ColorProvider, maxLines: Int) {
        Text(text, style = TextStyle(color = color, fontSize = WidgetSizes.lineText), maxLines = maxLines) // design-exception: Glance text takes a GlanceTheme colour provider (a Locus palette role), not Compose colours
    }

    private companion object {
        val Colors = ColorProviders(light = LocusLightColors, dark = LocusDarkColors)

        /** Home with the ask bar focused, as the quick settings tile does. */
        fun askIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .setAction(LaunchActions.ASK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        /** Locus, on [chatId] when one is waiting or working (with the token only Locus's own intents carry). */
        fun openIntent(context: Context, chatId: String?): Intent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return if (chatId != null) OpenChatLink.attach(context, intent, chatId) else intent
        }
    }
}

/**
 * Android starts this by name from the manifest (app/r8-kept-names.txt). Placing the first status widget
 * or removing the last one turns the updater's work for it on or off, so nothing is computed for a widget
 * that isn't there.
 */
class LocusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LocusWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).widgetUpdater().statusPlaced(true)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).widgetUpdater().statusPlaced(false)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetEntryPoint {
    fun widgetFeed(): WidgetFeed

    fun widgetUpdater(): LocusWidgetUpdater
}
