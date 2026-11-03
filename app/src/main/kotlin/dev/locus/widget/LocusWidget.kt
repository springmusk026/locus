package dev.locus.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
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

/**
 * The Locus home-screen widget (docs/design/android-bridge.md): the agent's status line, the latest
 * scheduled-task result in one cleaned line, and an Ask Locus button that opens home's ask bar.
 *
 * It only opens Locus. Tapping the status while an approval or question waits opens that chat in the
 * app; nothing is approved, denied or answered from the home screen. Locus's own palette, light and
 * dark; no bitmaps (only a vector dot), so it stays far below Android 17's widget bitmap memory limit.
 */
class LocusWidget : GlanceAppWidget() {
    // One layout for every size: Glance composes once per size bucket otherwise.
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val feed = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).widgetFeed()
        // Load before composing, so the widget never flashes an empty state.
        val initial = feed.state.first()
        val ask = askIntent(context)
        val askLabel = context.getString(R.string.widget_ask)
        provideContent {
            val model by feed.state.collectAsState(initial)
            GlanceTheme(colors = Colors) { Content(model, ask, askLabel, openIntent(context, model.status.chatId)) }
        }
    }

    @Composable
    private fun Content(model: WidgetModel, ask: Intent, askLabel: String, open: Intent) {
        val colors = GlanceTheme.colors
        Column(
            GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(WidgetSizes.corner).background(colors.widgetBackground).padding(WidgetSizes.padding),
        ) {
            Column(GlanceModifier.fillMaxWidth().defaultWeight().clickable(actionStartActivity(open))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_dot),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(
                            when (model.status.tone) {
                                WidgetTone.WAITING -> colors.tertiary
                                WidgetTone.WORKING -> colors.primary
                                WidgetTone.IDLE -> colors.outline
                            },
                        ),
                        modifier = GlanceModifier.size(WidgetSizes.dot),
                    )
                    Spacer(GlanceModifier.width(Spacing.sm))
                    Text(
                        model.status.text,
                        style = TextStyle(color = colors.onSurface, fontSize = WidgetSizes.statusText, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                }
                model.task?.let { line ->
                    Spacer(GlanceModifier.height(Spacing.xs))
                    Text(line, style = TextStyle(color = colors.onSurfaceVariant, fontSize = WidgetSizes.lineText), maxLines = 1)
                }
            }
            Button(
                text = askLabel,
                onClick = actionStartActivity(ask),
                modifier = GlanceModifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(backgroundColor = colors.primary, contentColor = colors.onPrimary),
            )
        }
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

/** Android starts this by name from the manifest (app/r8-kept-names.txt). */
class LocusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LocusWidget()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetEntryPoint {
    fun widgetFeed(): WidgetFeed
}
