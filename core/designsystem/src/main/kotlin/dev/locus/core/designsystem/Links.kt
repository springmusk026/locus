package dev.locus.core.designsystem

import android.content.res.Resources
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import dev.locus.core.model.SettingsDestination
import kotlinx.coroutines.delay

/**
 * Opens a page in Settings. The app root provides it ([ProvideSettingsLinks]) as "push the Settings
 * route on that page", so a hint deep inside a sheet links there without every screen in between
 * passing a callback down. Back from the page returns to where the link was.
 */
fun interface SettingsLinks {
    fun open(destination: SettingsDestination)
}

internal val LocalSettingsLinks = staticCompositionLocalOf<SettingsLinks?> { null }

/** Makes [links] what [SettingsLink] and [Locus.settings] open for everything in [content]. */
@Composable
fun ProvideSettingsLinks(links: SettingsLinks, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSettingsLinks provides links, content = content)
}

/**
 * A [DestinationLink] to a Settings page: "Settings → Models". [hint] is the sentence before it. In a
 * preview or a test without [ProvideSettingsLinks] the link shows but can't be tapped.
 */
@Composable
fun SettingsLink(destination: SettingsDestination, modifier: Modifier = Modifier, hint: String? = null, beforeOpen: () -> Unit = {}) {
    val links = LocalSettingsLinks.current
    DestinationLink(
        destination = settingsPath(LocalResources.current, destination),
        onNavigate = { beforeOpen(); links?.open(destination) },
        modifier = modifier,
        hint = hint,
        enabled = links != null,
    )
}

/**
 * A [SettingsLink] to [destination], or nothing when it's null. [destination] defaults to the page
 * [message] names in English ("… Details are in Settings → About & privacy → Diagnostics."), for text
 * that comes from elsewhere: an error, a notice from core/agent or core/data. Text from a string
 * resource passes the page itself, since a translation names it in its own words
 * (docs/design/localization.md). [beforeOpen] runs first, to close the sheet the message is in.
 */
@Composable
fun SettingsLinkIn(
    message: String?,
    modifier: Modifier = Modifier,
    destination: SettingsDestination? = SettingsDestination.inText(message),
    beforeOpen: () -> Unit = {},
) {
    destination ?: return
    SettingsLink(destination, modifier, beforeOpen = beforeOpen)
}

/**
 * A banner action for a message that names a Settings page ("OpenAI has no API key. Add it in Settings →
 * Models."): "Open Models", which opens [destination] over the screen; back returns to it. Nothing when
 * [destination] is null. It defaults to the page [message] names in English; text from a string resource
 * passes it, as for [SettingsLinkIn]. Put it in the banner's actions, with [hasSettingsAction] for
 * `actionsBelow`.
 */
@Composable
fun SettingsActionFor(message: String?, destination: SettingsDestination? = SettingsDestination.inText(message)) {
    val page = destination ?: return
    val links = LocalSettingsLinks.current ?: return
    TextButton(onClick = { links.open(page) }) { Text(settingsActionLabel(LocalResources.current, page)) }
}

/** Whether [message] gets a [SettingsActionFor] button: [destination], the same one passed to it, isn't null. */
fun hasSettingsAction(message: String?, destination: SettingsDestination? = SettingsDestination.inText(message)): Boolean = destination != null

/**
 * "Settings → About & privacy → Diagnostics" in English, the way messages from core/agent and core/data
 * name a page ([SettingsDestination.inText] reads it back). Screens show [settingsPath] with resources.
 */
fun settingsPath(destination: SettingsDestination): String = destinationPath("Settings", *destination.path.toTypedArray())

/** "Settings → About & privacy → Diagnostics" in the app's language, the way a link to [destination] is written. */
fun settingsPath(resources: Resources, destination: SettingsDestination): String =
    destinationPath(resources.getString(R.string.designsystem_settings), *destination.pathRes().map(resources::getString).toTypedArray())

/** What a button that opens [destination] says: "Open Models", "Open SSH machines". */
fun settingsActionLabel(resources: Resources, destination: SettingsDestination): String =
    resources.getString(R.string.designsystem_settings_open, resources.getString(destination.pathRes().last()))

/**
 * Whether the clock is still before [deadline] (epoch ms), updated the moment it passes, so "On until
 * 14:30" doesn't stay on screen after 14:30 when nothing else recomposes. Null [deadline] is false.
 */
@Composable
fun rememberBefore(deadline: Long?, now: () -> Long = System::currentTimeMillis): State<Boolean> =
    produceState(initialValue = deadline != null && now() < deadline, deadline) {
        if (deadline == null) {
            value = false
            return@produceState
        }
        while (true) {
            val left = deadline - now()
            value = left > 0
            if (left <= 0) break
            delay(left)
        }
    }
