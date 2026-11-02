package dev.locus.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.locus.core.agent.SentBlock
import dev.locus.core.agent.SentMessage
import dev.locus.core.agent.SentTool
import dev.locus.core.agent.SentView
import dev.locus.core.designsystem.CodeTextStyle
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.cardSegment
import dev.locus.core.designsystem.compactCount
import dev.locus.core.model.ApiFormat
import kotlinx.coroutines.launch

/** The "What was sent" sheet's state. */
sealed interface SentState {
    data object Loading : SentState

    /** Nothing kept for that reply: older than the last few requests, or from before the app started. */
    data object Missing : SentState

    data class Ready(val view: SentView) : SentState
}

/**
 * What Locus sent for one model request: the request settings, system prompt, tools and messages,
 * scrubbed of secrets and attachment bytes. Long content is a lazy list, in chunks.
 */
@Composable
internal fun WhatWasSentSheet(state: SentState, onDismiss: () -> Unit) {
    when (state) {
        SentState.Loading -> LocusSheet(title = "What was sent", onDismiss = onDismiss) {
            Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SentState.Missing -> LocusSheet(title = "What was sent", onDismiss = onDismiss) {
            Text(
                "Not kept for this reply. Locus keeps the last ${dev.locus.core.agent.SentRequestLog.PER_CHAT} requests of recent chats in " +
                    "memory only, never on disk, so older replies and replies from before the app last started can't be shown.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is SentState.Ready -> SentContent(state.view, onDismiss)
    }
}

@Composable
private fun SentContent(view: SentView, onDismiss: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val request = remember(view) { requestRows(view) }
    val systemChunks = remember(view) { SentView.chunks(view.system) }
    LocusSheet(
        title = "What was sent",
        subtitle = "${view.provider} · ${view.model}",
        onDismiss = onDismiss,
        action = if (copied) "Copied" else if (view.onDevice) "Copy text" else "Copy JSON",
        onAction = {
            scope.launch {
                clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("Locus request", view.json)))
                copied = true
            }
        },
        scrollable = false,
    ) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = Spacing.xxl)) {
            item(key = "note") {
                Text(
                    if (view.onDevice) {
                        "The on-device model gets this as a conversation, not an HTTP request. Secrets and file contents are left out."
                    } else {
                        "The request body, as the ${formatLabel(view.format)} adapter builds it. API keys and auth headers are never part of it; " +
                            "saved secrets are shown as [secret] and attachments by size."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item(key = "request-label") { Label("Request", view.reportedInputTokens?.let { "${compactCount(it.toLong())} tokens reported" }) }
            itemsIndexed(request, key = { i, _ -> "request-$i" }) { i, (label, value) ->
                Row(
                    Modifier.cardSegment(i, request.size).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(label, Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        value, Modifier.weight(0.6f), style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            item(key = "system-label") { Label("System prompt", estimate(view.estimate.system)) }
            if (view.system.isBlank()) {
                item(key = "system-empty") { Quiet("None") }
            } else {
                itemsIndexed(systemChunks, key = { i, _ -> "system-$i" }) { i, chunk ->
                    SelectionContainer {
                        Text(
                            chunk.trimEnd('\n'),
                            Modifier.cardSegment(i, systemChunks.size).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item(key = "tools-label") { Label("Tools · ${view.tools.size}", estimate(view.estimate.tools)) }
            if (view.tools.isEmpty()) item(key = "tools-empty") { Quiet("None") }
            itemsIndexed(view.tools, key = { i, t -> "tool-$i-${t.name}" }) { i, tool ->
                ToolRow(tool, Modifier.cardSegment(i, view.tools.size))
            }

            item(key = "messages-label") { Label("Messages · ${view.messages.size}", estimate(view.estimate.messages)) }
            itemsIndexed(view.messages, key = { i, _ -> "message-$i" }) { i, message ->
                MessageRow(message, Modifier.cardSegment(i, view.messages.size))
            }
        }
    }
}

@Composable
private fun Label(text: String, trailing: String?) {
    SectionLabel(if (trailing != null) "$text · $trailing" else text)
}

@Composable
private fun Quiet(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.xs))
}

@Composable
private fun ToolRow(tool: SentTool, modifier: Modifier) {
    var open by rememberSaveable(tool.name) { mutableStateOf(false) }
    Column(modifier.clickable(onClickLabel = if (open) "Hide schema" else "Show schema") { open = !open }.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tool.name, Modifier.weight(1f), style = MonoCaption.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Chevron(open)
        }
        Text(
            tool.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (open) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        AnimatedVisibility(open) { Code(SentView.capped(tool.schema, SentView.MAX_BLOCK_CHARS), Modifier.padding(top = Spacing.xs)) }
    }
}

@Composable
private fun MessageRow(message: SentMessage, modifier: Modifier) {
    Column(modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(message.role, style = MonoCaption, color = MaterialTheme.colorScheme.primary)
        message.blocks.forEachIndexed { i, block ->
            when (block) {
                is SentBlock.Text -> if (block.text.isNotEmpty()) SelectionContainer {
                    Text(SentView.capped(block.text, SentView.MAX_BLOCK_CHARS), style = MaterialTheme.typography.bodyMedium)
                }
                is SentBlock.Attachment -> Text(block.label.take(300), style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is SentBlock.ToolCall -> Collapsible("$i-call", "Tool call · ${block.name}", block.arguments)
                is SentBlock.ToolResult -> Collapsible(
                    "$i-result",
                    "Tool result${if (block.isError) " · error" else ""} · ${compactCount(block.content.length.toLong())} chars",
                    block.content,
                )
                is SentBlock.Other -> Collapsible("$i-other", block.type, block.json)
            }
        }
    }
}

/** A tool call, result or provider block: one line until tapped. */
@Composable
private fun Collapsible(key: String, title: String, body: String) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.chip)).clickable { open = !open }.padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Chevron(open)
        }
        AnimatedVisibility(open) { Code(SentView.capped(body, SentView.MAX_BLOCK_CHARS)) }
    }
}

@Composable
private fun Chevron(open: Boolean) {
    Icon(
        LocusIcons.ChevronRight,
        contentDescription = null,
        modifier = Modifier.size(16.dp).rotate(if (open) 90f else 0f),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Code(text: String, modifier: Modifier = Modifier) {
    SelectionContainer {
        Text(
            text,
            modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(Radius.chip)).padding(Spacing.sm),
            style = CodeTextStyle,
        )
    }
}

private fun estimate(tokens: Int) = if (tokens <= 0) null else "≈${compactCount(tokens.toLong())} tokens"

private fun formatLabel(format: ApiFormat) = when (format) {
    ApiFormat.ANTHROPIC_MESSAGES -> "Anthropic Messages"
    ApiFormat.OPENAI_CHAT -> "Chat Completions"
    ApiFormat.OPENAI_RESPONSES -> "Responses"
    ApiFormat.CHATGPT -> "Responses (ChatGPT plan)"
    ApiFormat.ON_DEVICE -> "LiteRT-LM"
}

/** The request card's rows: who it went to, then every setting sent besides the content. */
internal fun requestRows(view: SentView): List<Pair<String, String>> = buildList {
    add("Provider" to view.provider)
    add("Model" to view.model)
    add("Endpoint" to (view.host ?: "On this phone"))
    add("Format" to formatLabel(view.format))
    view.params.filter { it.first != "model" }.forEach { (k, v) -> add(k to v.take(400)) }
    view.reportedInputTokens?.let { add("Prompt tokens" to "%,d reported".format(it)) }
}
