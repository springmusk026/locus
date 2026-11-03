package dev.locus.core.markdown

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import dev.locus.core.designsystem.LocusType

/**
 * The one type scale for rendered Markdown, from the design system's roles: a document's headings
 * step down from a screen title to a label, and its prose is [LocusType.body] (or [body], for a
 * card that wants smaller text). Chat messages and Workspace's Markdown previews share it, so a
 * heading is the same size wherever it's read.
 *
 * [compact] is for Markdown inside a card (a helper's answer, a note), where `#` can't be bigger than
 * the card's own title: every heading is a card title or a label.
 */
@Composable
fun documentTypography(body: TextStyle = LocusType.body, compact: Boolean = false): MarkdownTypography = markdownTypography(
    h1 = if (compact) LocusType.cardTitle else LocusType.screenTitle,
    h2 = if (compact) LocusType.cardTitle else LocusType.stateTitle,
    h3 = if (compact) LocusType.label else LocusType.cardTitle,
    h4 = if (compact) LocusType.label else LocusType.cardTitle,
    h5 = LocusType.label,
    h6 = LocusType.label,
    text = body,
    paragraph = body,
    code = LocusType.code,
    inlineCode = LocusType.code,
)
