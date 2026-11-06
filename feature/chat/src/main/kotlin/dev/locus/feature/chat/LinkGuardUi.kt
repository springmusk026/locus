package dev.locus.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import dev.locus.core.agent.LinkGuard
import dev.locus.core.designsystem.CodeSurface
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import kotlinx.coroutines.launch

/**
 * What a link tap in this part of the chat needs to know (docs/design/taint.md, "Links in replies"):
 * whether the text was written after the chat read outside content, and the hosts the user named or set
 * up. The default is the careful one, for text outside a known reply (helpers, side questions).
 */
data class LinkContext(val outsideContent: Boolean = true, val namedHosts: Set<String> = emptySet())

internal val LocalLinkContext = compositionLocalOf { LinkContext() }

/**
 * Links in [content] (Markdown replies) open through [LinkGuard]: a plain link opens at once, one that
 * could carry data out asks first in [LinkConfirmSheet].
 */
@Composable
internal fun GuardedLinks(content: @Composable () -> Unit) {
    val context by rememberUpdatedState(LocalLinkContext.current)
    val system = LocalUriHandler.current
    var confirm by remember { mutableStateOf<LinkGuard.Confirm?>(null) }
    val guarded = remember(system) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val check = LinkGuard.check(uri, context.outsideContent, context.namedHosts)
                if (check == null) runCatching { system.openUri(uri) } else confirm = check
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides guarded, content = content)
    confirm?.let { c ->
        LinkConfirmSheet(
            c,
            onOpen = { confirm = null; runCatching { system.openUri(c.url) } },
            onDismiss = { confirm = null },
        )
    }
}

/**
 * Asks before opening a link that could carry data out: the whole address with its host picked out, why
 * it asks, Copy link, and Open.
 */
@Composable
internal fun LinkConfirmSheet(confirm: LinkGuard.Confirm, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    ConfirmSheet(
        title = "Open this link?",
        message = linkWhy(confirm),
        confirmLabel = "Open",
        onConfirm = onOpen,
        onDismiss = onDismiss,
        tone = Tone.Caution,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            SupportingText(if (confirm.host != null) "Goes to" else "Opens")
            Text(confirm.host ?: confirm.url.substringBefore(':') + ":", style = LocusType.cardTitle, color = MaterialTheme.colorScheme.onSurface)
        }
        CodeSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                SupportingText("Full address")
                SelectionContainer { Text(highlightedUrl(confirm), style = LocusType.code) }
            }
        }
        TextButton(
            onClick = {
                scope.launch {
                    clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("Link", confirm.url)))
                    copied = true
                }
            },
            modifier = Modifier.heightIn(min = Sizes.touchTarget),
        ) {
            androidx.compose.material3.Icon(if (copied) LocusIcons.Check else LocusIcons.Copy, contentDescription = null)
            Text(if (copied) "Copied" else "Copy link", Modifier.padding(start = Spacing.sm), style = LocusType.label)
        }
    }
}

/** The sheet's explanation: why this link didn't open at once. */
internal fun linkWhy(confirm: LinkGuard.Confirm): String {
    val reasons = confirm.reasons
    val parts = buildList {
        if (LinkGuard.Reason.OUTSIDE_CONTENT in reasons) {
            add("This reply was written after Locus read outside content (a page, a mail, a tool's reply), and you haven't named this site in the chat.")
        }
        if (LinkGuard.Reason.LONG_DATA in reasons) {
            add("The address carries ${confirm.dataChars} characters after its “?” or “#”, which could hold your data.")
        }
        if (LinkGuard.Reason.NOT_WEB in reasons) add("It isn't a web link, so it may open another app.")
    }
    return (parts + "Check where it goes before you open it.").joinToString(" ")
}

/** [confirm]'s address with its host in bold, brand-coloured; the rest muted. */
@Composable
private fun highlightedUrl(confirm: LinkGuard.Confirm) = buildAnnotatedString {
    val url = confirm.url
    val host = confirm.host
    val at = host?.let { url.lowercase().indexOf(it, startIndex = url.indexOf(':').coerceAtLeast(0)) } ?: -1
    val muted = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant) // design-exception: a span inside one address, beside the highlighted host; SupportingText can't style part of a string
    if (host == null || at < 0) {
        withStyle(muted) { append(url) }
        return@buildAnnotatedString
    }
    withStyle(muted) { append(url.substring(0, at)) }
    withStyle(SpanStyle(color = Locus.colors.onCautionContainer, fontWeight = FontWeight.Bold)) { append(url.substring(at, at + host.length)) }
    withStyle(muted) { append(url.substring(at + host.length)) }
}
