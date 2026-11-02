package dev.locus.core.designsystem

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
        destination = settingsPath(destination),
        onNavigate = { beforeOpen(); links?.open(destination) },
        modifier = modifier,
        hint = hint,
        enabled = links != null,
    )
}

/**
 * A [SettingsLink] to the page [message] names ("… Details are in Settings → About & privacy →
 * Diagnostics."), or nothing when it names none. For text that comes from elsewhere: an error, a
 * notice. [beforeOpen] runs first, to close the sheet the message is in.
 */
@Composable
fun SettingsLinkIn(message: String?, modifier: Modifier = Modifier, beforeOpen: () -> Unit = {}) {
    val destination = SettingsDestination.inText(message) ?: return
    SettingsLink(destination, modifier, beforeOpen = beforeOpen)
}

/**
 * A banner action for a message that names a Settings page ("OpenAI has no API key. Add it in Settings →
 * Models."): "Open Models", which opens that page over the screen; back returns to it. Nothing when the
 * message names no page. Put it in the banner's actions, with [hasSettingsAction] for `actionsBelow`.
 */
@Composable
fun SettingsActionFor(message: String?) {
    val page = SettingsDestination.inText(message) ?: return
    val links = LocalSettingsLinks.current ?: return
    TextButton(onClick = { links.open(page) }) { Text(settingsActionLabel(page)) }
}

/** Whether [message] gets a [SettingsActionFor] button. */
fun hasSettingsAction(message: String?): Boolean = SettingsDestination.inText(message) != null

/** "Settings → About & privacy → Diagnostics", the way a link to [destination] is written. */
fun settingsPath(destination: SettingsDestination): String = destinationPath("Settings", *destination.path.toTypedArray())

/** What a button that opens [destination] says: "Open Models", "Open SSH machines". */
fun settingsActionLabel(destination: SettingsDestination): String = "Open ${destination.title}"

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
