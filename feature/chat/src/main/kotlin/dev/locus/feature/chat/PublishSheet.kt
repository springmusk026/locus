package dev.locus.feature.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import dev.locus.core.agent.integrations.PublishHost
import dev.locus.core.data.publish.PublishedPage
import dev.locus.core.designsystem.ActionRow
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.CodeSurface
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.KeyValueRow
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusFeedback
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SettingsActionFor
import dev.locus.core.model.SettingsDestination
import dev.locus.core.designsystem.SettingsLink
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.hasSettingsAction
import dev.locus.core.designsystem.R as DesignR
import dev.locus.core.markdown.artifact.Artifact
import dev.locus.core.markdown.artifact.ArtifactEvents
import dev.locus.core.markdown.artifact.ArtifactKind
import dev.locus.core.markdown.artifact.ArtifactPreviews
import dev.locus.core.markdown.artifact.createArtifactWebView
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/** What the publish sheet can ask of its screen. [publish] asks for authentication first in a locked chat. */
@Immutable
internal class PublishActions(
    val selectHost: (PublishHost) -> Unit = {},
    val setIncludeToolResults: (Boolean) -> Unit = {},
    val setUpdate: (PublishedPage?) -> Unit = {},
    val build: () -> Unit = {},
    val toConfirm: () -> Unit = {},
    val back: () -> Unit = {},
    val publish: () -> Unit = {},
    val close: () -> Unit = {},
    val unpublish: (PublishedPage, (String?) -> Unit) -> Unit = { _, _ -> },
    val forget: (PublishedPage) -> Unit = {},
)

/**
 * Publish to your own host (docs/design/publish.md): pick one of the user's deploy integrations, see the
 * page in the no-network sandbox with what scrubbing removed, confirm that it will be public, then get
 * the address. [pages] are the earlier publishes, to open, update or unpublish.
 */
@Composable
internal fun PublishSheet(ui: PublishUi, pages: List<PublishedPage>, actions: PublishActions) {
    val source = ui.source ?: return
    var managing by remember { mutableStateOf<PublishedPage?>(null) }
    val hostName = ui.host?.integration?.name.orEmpty()
    val (title, subtitle) = when (ui.step) {
        PublishStep.Destination, PublishStep.Building -> stringResource(R.string.chat_publish_title) to stringResource(R.string.chat_publish_subtitle)
        PublishStep.Preview -> stringResource(R.string.chat_publish_preview_title) to stringResource(R.string.chat_publish_preview_subtitle)
        PublishStep.Confirm -> stringResource(R.string.chat_publish_confirm_title) to null
        PublishStep.Publishing -> stringResource(R.string.chat_publish_publishing, hostName) to null
        PublishStep.Done -> stringResource(R.string.chat_publish_done_title) to null
        PublishStep.Failed -> stringResource(R.string.chat_publish_failed_title) to null
    }
    val (action, onAction, actionEnabled) = when (ui.step) {
        PublishStep.Destination -> Triple(stringResource(R.string.chat_publish_next), actions.build, ui.host != null)
        PublishStep.Preview -> Triple(stringResource(R.string.chat_publish_next), actions.toConfirm, ui.built?.blocked == false)
        PublishStep.Confirm -> Triple(stringResource(R.string.chat_publish_go), actions.publish, true)
        PublishStep.Done -> Triple(stringResource(DesignR.string.common_done), actions.close, true)
        PublishStep.Failed -> Triple(stringResource(DesignR.string.common_try_again), actions.back, true)
        else -> Triple(null, null, false)
    }
    LocusSheet(
        title = title,
        subtitle = subtitle,
        onDismiss = actions.close,
        action = action.takeIf { ui.step != PublishStep.Destination || !ui.hosts.isNullOrEmpty() },
        actionEnabled = actionEnabled,
        onAction = onAction,
    ) {
        when (ui.step) {
            PublishStep.Destination -> Destination(ui, source, pages, actions, onManage = { managing = it })
            PublishStep.Building -> Working(stringResource(R.string.chat_publish_building))
            PublishStep.Preview -> Preview(ui)
            PublishStep.Confirm -> Confirm(ui, source)
            PublishStep.Publishing -> Working(stringResource(R.string.chat_publish_publishing, hostName))
            PublishStep.Done -> Done(ui)
            PublishStep.Failed -> Failed(ui, hostName)
        }
        if (ui.step == PublishStep.Preview || ui.step == PublishStep.Confirm) {
            ActionRow { TextButton(onClick = actions.back) { Text(stringResource(R.string.chat_publish_back)) } }
        }
    }
    managing?.let { page -> PublishedPageSheet(page, actions, onDismiss = { managing = null }) }
}

@Composable
private fun Destination(ui: PublishUi, source: PublishSource, pages: List<PublishedPage>, actions: PublishActions, onManage: (PublishedPage) -> Unit) {
    val hosts = ui.hosts
    when {
        hosts == null -> Working(null)
        hosts.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.chat_publish_none_title), style = LocusType.stateTitle)
            SupportingText(stringResource(R.string.chat_publish_none_body), style = LocusType.rowSupporting)
            SettingsLink(SettingsDestination.Integrations, hint = stringResource(R.string.chat_publish_none_hint), beforeOpen = actions.close)
        }
        else -> Column {
            SectionLabel(stringResource(R.string.chat_publish_where))
            CardGroup {
                hosts.forEachIndexed { i, host ->
                    if (i > 0) GroupDivider()
                    ListRow(
                        title = host.integration.name,
                        subtitle = stringResource(hostAddress(host)),
                        onClick = { actions.selectHost(host) },
                        leading = { Icon(LocusIcons.Globe, contentDescription = null, modifier = Modifier.padding(Spacing.xs)) },
                        trailing = { if (host == ui.host) Icon(LocusIcons.Check, contentDescription = stringResource(R.string.chat_publish_selected), tint = MaterialTheme.colorScheme.primary) },
                    )
                }
            }
            val earlier = pages.firstOrNull { it.source == source.source && it.host == ui.host?.id }
            if (earlier != null) {
                SectionLabel(stringResource(R.string.chat_publish_this_page))
                CardGroup {
                    ListRow(
                        title = stringResource(R.string.chat_publish_update, earlier.url ?: earlier.site),
                        subtitle = stringResource(R.string.chat_publish_update_note),
                        subtitleMaxLines = 2,
                        titleMaxLines = 2,
                        onClick = { actions.setUpdate(earlier) },
                        trailing = { if (ui.update?.id == earlier.id) Icon(LocusIcons.Check, contentDescription = stringResource(R.string.chat_publish_selected), tint = MaterialTheme.colorScheme.primary) },
                    )
                    GroupDivider()
                    ListRow(
                        title = stringResource(R.string.chat_publish_new),
                        subtitle = stringResource(R.string.chat_publish_new_note),
                        subtitleMaxLines = 2,
                        onClick = { actions.setUpdate(null) },
                        trailing = { if (ui.update == null) Icon(LocusIcons.Check, contentDescription = stringResource(R.string.chat_publish_selected), tint = MaterialTheme.colorScheme.primary) },
                    )
                }
            }
            if (source is PublishSource.Chat) {
                SectionLabel(stringResource(R.string.chat_publish_contents))
                CardGroup {
                    SwitchRow(
                        title = stringResource(R.string.chat_publish_tools),
                        subtitle = stringResource(R.string.chat_publish_tools_note),
                        checked = ui.includeToolResults,
                        onCheckedChange = actions.setIncludeToolResults,
                    )
                }
                SupportingText(stringResource(R.string.chat_publish_left_out), Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm))
            }
        }
    }
    if (pages.isNotEmpty()) {
        SectionLabel(stringResource(R.string.chat_publish_pages))
        CardGroup {
            pages.take(MAX_LISTED).forEachIndexed { i, page ->
                if (i > 0) GroupDivider()
                ListRow(
                    title = page.title,
                    subtitle = pageMeta(page),
                    onClick = { onManage(page) },
                    trailing = { Icon(LocusIcons.ChevronRight, contentDescription = null, modifier = Modifier.padding(start = Spacing.xs)) },
                )
            }
        }
    }
}

@Composable
private fun pageMeta(page: PublishedPage): String {
    val host = page.publishHost?.integration?.name ?: page.host
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(page.updatedAt))
    return stringResource(R.string.chat_publish_page_meta, host, date)
}

@Composable
private fun Working(text: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        InlineSpinner()
        if (text != null) SupportingText(text, style = LocusType.rowSupporting)
    }
}

@Composable
private fun Preview(ui: PublishUi) {
    val built = ui.built ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (built.blocked) {
            Banner(stringResource(R.string.chat_publish_blocked), Tone.Critical, title = stringResource(R.string.chat_publish_blocked_title))
        } else if (built.removed > 0) {
            Banner(
                stringResource(R.string.chat_publish_removed_note),
                Tone.Caution,
                title = pluralStringResource(R.plurals.chat_publish_removed, built.removed, built.removed),
            )
        }
        CodeSurface(Modifier.fillMaxWidth()) {
            Column {
                Box(Modifier.fillMaxWidth().height(Sizes.inlineScroll).padding(Spacing.xs)) { PagePreview(built.html, ui.source?.title.orEmpty()) }
                SupportingText(stringResource(R.string.chat_publish_sandboxed), Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs), style = LocusType.mono)
            }
        }
    }
}

/**
 * The page in the artifact sandbox (docs/design/artifacts.md): its own profile, no storage, and every
 * request but its own two documents refused, so a preview loads nothing from the network. It takes the
 * one live preview slot while it shows. Screenshot tests have no WebView, so they show the source.
 */
@Composable
private fun PagePreview(html: String, title: String) {
    if (LocalInspectionMode.current) {
        Text(
            html.take(PREVIEW_SOURCE_CHARS),
            style = LocusType.code,
            softWrap = false,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
        )
        return
    }
    val surface = MaterialTheme.colorScheme.surface.toArgb()
    val css = "#%06X".format(surface and 0xFFFFFF)
    val owner = remember { "publish:" + UUID.randomUUID() }
    val artifact = remember(html) { publishPreviewArtifact(html, title) }
    DisposableEffect(owner) {
        ArtifactPreviews.slot.claim(owner) {}
        onDispose { ArtifactPreviews.slot.release(owner) }
    }
    AndroidView(
        factory = { ctx -> createArtifactWebView(ctx, artifact, surface, css, object : ArtifactEvents {}) },
        onRelease = { view: WebView ->
            view.stopLoading()
            view.destroy()
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** The page as an artifact for the sandboxed preview: the same rules as any HTML artifact in the chat. */
internal fun publishPreviewArtifact(html: String, title: String) = Artifact(ArtifactKind.Html, html, title)

@Composable
private fun Confirm(ui: PublishUi, source: PublishSource) {
    val host = ui.host ?: return
    val built = ui.built ?: return
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Banner(stringResource(R.string.chat_publish_public), Tone.Caution, title = stringResource(R.string.chat_publish_public_title))
        CardGroup {
            KeyValueRow(stringResource(R.string.chat_publish_confirm_host), host.integration.name)
            GroupDivider()
            KeyValueRow(
                stringResource(R.string.chat_publish_confirm_address),
                ui.update?.let { it.url ?: it.site } ?: stringResource(R.string.chat_publish_confirm_new_address, stringResource(hostAddress(host))),
                mono = true,
            )
            GroupDivider()
            KeyValueRow(
                stringResource(R.string.chat_publish_confirm_what),
                when {
                    source is PublishSource.Page -> source.title
                    ui.includeToolResults -> stringResource(R.string.chat_publish_what_chat_tools)
                    else -> stringResource(R.string.chat_publish_what_chat)
                },
            )
            GroupDivider()
            KeyValueRow(stringResource(R.string.chat_publish_confirm_size), Formatter.formatShortFileSize(context, built.bytes))
        }
    }
}

@Composable
private fun Done(ui: PublishUi) {
    val page = ui.result ?: return
    val context = LocalContext.current
    val feedback = Locus.feedback
    val host = page.publishHost?.integration?.name ?: page.host
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        val url = page.url
        if (url == null) {
            Banner(stringResource(R.string.chat_publish_done_no_url, host), Tone.Caution)
        } else {
            CardGroup {
                KeyValueRow(stringResource(R.string.chat_publish_confirm_address), url, mono = true)
            }
            SupportingText(stringResource(R.string.chat_publish_done_note), Modifier.padding(horizontal = Spacing.lg))
            LinkActions(context, url, page.title, feedback)
        }
    }
}

@Composable
private fun LinkActions(context: Context, url: String, title: String, feedback: LocusFeedback) {
    val copied = stringResource(R.string.chat_publish_copied)
    val noBrowser = stringResource(R.string.chat_publish_no_browser)
    ActionRow {
        TextButton(onClick = {
            runCatching { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(title, url)) }
            feedback.show(copied)
        }) { Text(stringResource(R.string.chat_publish_copy)) }
        TextButton(onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).putExtra(Intent.EXTRA_SUBJECT, title)
            runCatching { context.startActivity(Intent.createChooser(send, null)) }
        }) { Text(stringResource(R.string.chat_publish_share)) }
        TextButton(onClick = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: ActivityNotFoundException) {
                feedback.show(noBrowser, Tone.Caution)
            }
        }) { Text(stringResource(R.string.chat_publish_open)) }
    }
}

@Composable
private fun Failed(ui: PublishUi, hostName: String) {
    val message = ui.error ?: if (ui.failedAt == PublishStep.Destination) stringResource(R.string.chat_publish_failed_build) else stringResource(R.string.chat_publish_failed_generic, hostName)
    Banner(message, Tone.Critical, actionsBelow = hasSettingsAction(ui.error), actions = if (ui.error != null) ({ SettingsActionFor(ui.error) }) else null)
}

/** One earlier page: its address, Copy, Share and Open, and Unpublish (or, failing that, how to remove it by hand). */
@Composable
private fun PublishedPageSheet(page: PublishedPage, actions: PublishActions, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val feedback = Locus.feedback
    val host = page.publishHost
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    LocusSheet(title = page.title, subtitle = pageMeta(page), onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            page.url?.let { url ->
                CardGroup { KeyValueRow(stringResource(R.string.chat_publish_confirm_address), url, mono = true) }
                LinkActions(context, url, page.title, feedback)
            }
            failure?.let { message ->
                Banner(
                    listOfNotNull(message.ifBlank { null }, host?.let { stringResource(manualRemoval(it), page.site) }).joinToString("\n\n"),
                    Tone.Critical,
                    title = stringResource(R.string.chat_publish_unpublish_failed),
                )
            }
            ActionRow {
                TextButton(onClick = {
                    actions.forget(page)
                    onDismiss()
                }) { Text(stringResource(R.string.chat_publish_forget)) }
                if (host?.canUnpublish == true) {
                    TextButton(onClick = { confirm = true }, enabled = !busy) { Text(stringResource(R.string.chat_publish_unpublish)) }
                }
            }
            SupportingText(stringResource(R.string.chat_publish_forget_note), Modifier.padding(horizontal = Spacing.lg))
        }
    }
    if (confirm && host != null) {
        ConfirmSheet(
            title = stringResource(R.string.chat_publish_unpublish_title),
            message = stringResource(unpublishMessage(host), page.url ?: page.site),
            confirmLabel = stringResource(R.string.chat_publish_unpublish),
            tone = Tone.Critical,
            busy = busy,
            onConfirm = {
                busy = true
                actions.unpublish(page) { error ->
                    busy = false
                    confirm = false
                    if (error == null) {
                        feedback.show(resources.getString(R.string.chat_publish_unpublished))
                        onDismiss()
                    } else {
                        failure = error
                    }
                }
            },
            onDismiss = { if (!busy) confirm = false },
        )
    }
}

private fun hostAddress(host: PublishHost) = when (host) {
    PublishHost.NETLIFY -> R.string.chat_publish_address_netlify
    PublishHost.CLOUDFLARE -> R.string.chat_publish_address_cloudflare
    PublishHost.VERCEL -> R.string.chat_publish_address_vercel
}

private fun unpublishMessage(host: PublishHost) = when (host) {
    PublishHost.NETLIFY -> R.string.chat_publish_unpublish_netlify
    PublishHost.CLOUDFLARE -> R.string.chat_publish_unpublish_cloudflare
    PublishHost.VERCEL -> R.string.chat_publish_unpublish_vercel
}

private fun manualRemoval(host: PublishHost) = when (host) {
    PublishHost.NETLIFY -> R.string.chat_publish_remove_netlify
    PublishHost.CLOUDFLARE -> R.string.chat_publish_remove_cloudflare
    PublishHost.VERCEL -> R.string.chat_publish_remove_vercel
}

private const val MAX_LISTED = 20
private const val PREVIEW_SOURCE_CHARS = 1_200
