package dev.locus.feature.chat

import dev.locus.core.agent.publish.PublishPage
import dev.locus.core.model.Part
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser

/** The words of a published chat page, in the app's language (from string resources). */
internal data class PageLabels(
    val you: String = "You",
    val locus: String = "Locus",
    /** "%1$s · Published from Locus", with the chat's date. */
    val subtitle: String = "%1\$s · Published from Locus",
    val noDate: String = "Published from Locus",
    val summarised: String = "Earlier messages were summarised here.",
    /** "Attached: %1$s". */
    val attached: String = "Attached: %1\$s",
    /** "Sent %1$s". */
    val sent: String = "Sent %1\$s",
    val result: String = "Result",
    val memoryHidden: String = "Memory isn't published.",
    /** "Proposed a plan: %1$s". */
    val plan: String = "Proposed a plan: %1\$s",
    /** "Changed %1$s". */
    val changed: String = "Changed %1\$s",
)

/** A built chat page: the whole document, how many secrets were taken out, and how many pictures it holds. */
internal data class BuiltPage(val html: String, val removed: Int, val images: Int)

/**
 * A chat as one static web page (docs/design/publish.md), built from the rows the chat shows: the
 * active branch, the user's and Locus's words, and each run of steps as a short list. Never in it:
 * reasoning, the system prompt, memory, helper transcripts, and (unless [includeToolResults]) any tool
 * output. Memory tools' results stay out even then.
 *
 * Safety, in order: every piece of text goes through [PublishPage.Scrubber] first; plain text is then
 * escaped ([PublishPage.escape]); Markdown is rendered with raw HTML escaped (it shows as text, as in the
 * chat) and the result is cut to an allowlist ([PublishPage.sanitize]); the page carries a CSP with no
 * scripts and no network ([PublishPage.CHAT_CSP]). Pictures go in as `data:` URLs from [image].
 */
internal object ChatPage {

    fun build(
        title: String,
        date: String?,
        items: List<ChatItem>,
        labels: PageLabels,
        includeToolResults: Boolean,
        scrubber: PublishPage.Scrubber,
        image: (Part.Attachment) -> String?,
        lang: String = "en",
    ): BuiltPage {
        var removed = 0
        var images = 0
        fun clean(text: String): String = scrubber.scrub(text).also { removed += it.removed }.text
        fun plain(text: String) = PublishPage.escape(clean(text))
        fun md(text: String) = PublishPage.sanitize(markdownToHtml(clean(text)))
        fun picture(a: Part.Attachment): String? {
            if (!a.mimeType.lowercase().startsWith("image/") || a.mimeType.contains("svg")) return null
            val data = image(a) ?: return null
            images++
            return "<img src=\"$data\" alt=\"${plain(a.name)}\">"
        }

        val body = StringBuilder()
        var speaker: String? = null
        var steps = StringBuilder()
        fun flushSteps() {
            if (steps.isNotEmpty()) body.append("<ul class=\"steps\">").append(steps).append("</ul>\n")
            steps = StringBuilder()
        }
        fun open(who: String, css: String) {
            if (speaker == who) return
            flushSteps()
            if (speaker != null) body.append("</div></section>\n")
            speaker = who
            body.append("<section class=\"turn ").append(css).append("\"><p class=\"who\">").append(PublishPage.escape(who)).append("</p><div class=\"body\">\n")
        }
        fun close() {
            flushSteps()
            if (speaker != null) body.append("</div></section>\n")
            speaker = null
        }
        fun block(html: String) {
            flushSteps()
            body.append(html).append('\n')
        }
        fun step(html: String) {
            steps.append("<li>").append(html).append("</li>")
        }

        items.forEach { item ->
            when (item) {
                is ChatItem.User -> {
                    close()
                    open(labels.you, "user")
                    if (item.text.isNotBlank()) block(md(item.text))
                    val pictures = item.attachments.mapNotNull { picture(it) }
                    if (pictures.isNotEmpty()) block("<p>" + pictures.joinToString(" ") + "</p>")
                    val others = item.attachments.filter { a -> !a.mimeType.lowercase().startsWith("image/") || a.mimeType.contains("svg") }
                    if (others.isNotEmpty()) block("<p class=\"files\">" + plain(labels.attached.format(others.joinToString(", ") { it.name })) + "</p>")
                }
                is ChatItem.Checkpoint -> {
                    close()
                    block("<p class=\"note\">" + PublishPage.escape(labels.summarised) + "</p>")
                }
                is ChatItem.Assistant -> if (item.text.isNotBlank()) {
                    open(labels.locus, "locus")
                    block(md(item.text))
                }
                is ChatItem.ToolCall -> {
                    open(labels.locus, "locus")
                    val label = plain(ChatExport.stepLabel(item))
                    val result = item.result
                    if (!includeToolResults || result == null) {
                        step(label)
                    } else {
                        flushSteps()
                        val content = if (isMemoryTool(item.name)) PublishPage.escape(labels.memoryHidden) else plain(result.content.take(MAX_RESULT_CHARS).let { if (result.content.length > MAX_RESULT_CHARS) "$it\n…" else it })
                        val pictures = if (isMemoryTool(item.name)) emptyList() else result.images.mapNotNull { picture(it) }
                        block(
                            "<details><summary>$label</summary><p class=\"who\">${PublishPage.escape(labels.result)}</p><pre><code>$content</code></pre>" +
                                (if (pictures.isEmpty()) "" else "<p>" + pictures.joinToString(" ") + "</p>") + "</details>",
                        )
                    }
                }
                is ChatItem.Note -> {
                    open(labels.locus, "locus")
                    step(plain(item.text.lineSequence().joinToString(" ").take(300)))
                }
                is ChatItem.Plan -> {
                    open(labels.locus, "locus")
                    block(
                        "<p>" + plain(labels.plan.format(item.plan.title)) + "</p><ol>" +
                            item.plan.steps.joinToString("") { "<li>" + plain(it) + "</li>" } + "</ol>",
                    )
                }
                is ChatItem.SentFile -> {
                    open(labels.locus, "locus")
                    step(plain(labels.sent.format(item.path.substringAfterLast('/'))))
                }
                is ChatItem.Verification -> {
                    open(labels.locus, "locus")
                    step(plain(ChatExport.stepLabel(item.call)))
                }
                is ChatItem.Changes -> if (item.files.isNotEmpty()) {
                    open(labels.locus, "locus")
                    item.files.forEach { f -> step(plain(labels.changed.format(f.path))) }
                }
                // Reasoning stays private; drafts, receipts and turn ends are the app's own bookkeeping.
                is ChatItem.Thought, is ChatItem.TurnEnd, is ChatItem.ProviderDraft, is ChatItem.Receipt -> Unit
            }
        }
        close()
        val heading = clean(title.lineSequence().firstOrNull().orEmpty().ifBlank { "Chat" })
        val subtitle = date?.let { labels.subtitle.format(it) } ?: labels.noDate
        return BuiltPage(PublishPage.chatDocument(heading, subtitle, body.toString(), lang), removed, images)
    }

    /** Memory is the user's private notes about themselves; it never goes on a page. */
    private fun isMemoryTool(name: String) = name.startsWith("memory_")

    /**
     * Markdown (GitHub flavour) to HTML, with raw HTML escaped so it shows as text: an `<script>` or
     * `<img onerror>` in a reply can't become markup. [PublishPage.sanitize] runs on the result too.
     */
    fun markdownToHtml(markdown: String): String {
        val flavour = GFMFlavourDescriptor()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
        val providers: MutableMap<IElementType, GeneratingProvider> = flavour.createHtmlGeneratingProviders(LinkMap.buildLinkMap(tree, markdown), null).toMutableMap()
        providers[MarkdownElementTypes.HTML_BLOCK] = EscapedHtml(block = true)
        providers[MarkdownTokenTypes.HTML_TAG] = EscapedHtml(block = false)
        return HtmlGenerator(markdown, tree, providers, true).generateHtml()
    }

    private class EscapedHtml(private val block: Boolean) : GeneratingProvider {
        override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
            val escaped = PublishPage.escape(node.getTextInNode(text).toString())
            visitor.consumeHtml(if (block) "<p>" + escaped.replace("\n", "<br>") + "</p>" else escaped)
        }
    }

    private const val MAX_RESULT_CHARS = 4_000
}
