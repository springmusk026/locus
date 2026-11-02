package dev.locus.feature.chat

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/** How a line of the PDF looks: its size, face and the space above its paragraph (all in points). */
internal enum class PdfStyle(val size: Float, val mono: Boolean, val bold: Boolean, val spaceBefore: Float) {
    Title(18f, false, true, 0f),
    Meta(9f, false, false, 4f),
    Speaker(11f, false, true, 14f),
    Heading(11f, false, true, 8f),
    Body(10.5f, false, false, 6f),
    Bullet(10f, false, false, 2f),
    Code(8.5f, true, false, 0f),
    ;

    val lineHeight: Float get() = size * 1.4f
}

/** One paragraph of the export, before wrapping. */
internal data class PdfBlock(val text: String, val style: PdfStyle, val indent: Float = 0f)

/** A wrapped line placed on a page; [y] is its baseline from the top of the content area. */
internal data class PdfLine(val text: String, val style: PdfStyle, val x: Float, val y: Float)

/**
 * Lays the chat's Markdown export out as PDF pages: pure, so the wrapping and page breaks are unit
 * tested with a fake [measure]. [ChatPdf.write] draws the result with Android's [PdfDocument].
 */
internal object PdfLayout {

    /** The export Markdown as styled paragraphs: code inside fences, speakers, list items and text. */
    fun blocks(markdown: String): List<PdfBlock> {
        val out = mutableListOf<PdfBlock>()
        var fence: String? = null
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) out += PdfBlock(inline(paragraph.toString().trim()), PdfStyle.Body)
            paragraph.clear()
        }
        markdown.lines().forEach { raw ->
            val line = raw.trimEnd()
            val trimmed = line.trim()
            val open = fence
            when {
                open != null -> if (trimmed.startsWith(open)) fence = null else out += PdfBlock(line.replace("\t", "    "), PdfStyle.Code)
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    flush()
                    fence = trimmed.takeWhile { it == trimmed[0] }
                }
                trimmed.isEmpty() -> flush()
                trimmed.startsWith("# ") && out.isEmpty() -> out += PdfBlock(trimmed.removePrefix("# "), PdfStyle.Title)
                out.size == 1 && out[0].style == PdfStyle.Title && trimmed.startsWith("_") -> out += PdfBlock(trimmed.trim('_'), PdfStyle.Meta)
                SPEAKER.matches(trimmed) -> {
                    flush()
                    out += PdfBlock(trimmed.trim('*'), PdfStyle.Speaker)
                }
                HEADING.containsMatchIn(trimmed) -> {
                    flush()
                    out += PdfBlock(inline(trimmed.replace(HEADING, "")), PdfStyle.Heading)
                }
                BULLET.containsMatchIn(trimmed) -> {
                    flush()
                    val marker = BULLET.find(trimmed)!!.value.trim()
                    val bullet = if (marker.first().isDigit()) marker else "•"
                    out += PdfBlock("$bullet " + inline(trimmed.replace(BULLET, "")), PdfStyle.Bullet, indent = 10f)
                }
                else -> paragraph.append(if (paragraph.isEmpty()) "" else " ").append(trimmed)
            }
        }
        flush()
        return out
    }

    /**
     * Wraps [text] to [width] at spaces. Words wider than a line are broken by character; code keeps
     * its leading spaces and breaks by character only.
     */
    fun wrap(text: String, width: Float, mono: Boolean, measure: (String) -> Float): List<String> {
        if (text.isEmpty()) return listOf("")
        if (mono) return breakChars(text, width, measure)
        val lines = mutableListOf<String>()
        var current = ""
        text.split(' ').filter { it.isNotEmpty() }.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            when {
                measure(candidate) <= width -> current = candidate
                measure(word) <= width -> {
                    if (current.isNotEmpty()) lines += current
                    current = word
                }
                else -> {
                    if (current.isNotEmpty()) lines += current
                    val parts = breakChars(word, width, measure)
                    lines += parts.dropLast(1)
                    current = parts.last()
                }
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines.ifEmpty { listOf("") }
    }

    /**
     * Places [blocks] on pages [height] points tall and [width] wide. A speaker or heading never ends a
     * page: when the line after it doesn't fit, both move to the next page.
     */
    fun paginate(blocks: List<PdfBlock>, width: Float, height: Float, measure: (String, PdfStyle) -> Float): List<List<PdfLine>> {
        val pages = mutableListOf<MutableList<PdfLine>>(mutableListOf())
        var y = 0f
        fun newPage() {
            pages += mutableListOf<PdfLine>()
            y = 0f
        }
        blocks.forEachIndexed { index, block ->
            val lines = wrap(block.text, width - block.indent, block.style.mono) { measure(it, block.style) }
            val gap = if (y == 0f) 0f else block.style.spaceBefore
            val keepWith = if (block.style == PdfStyle.Speaker || block.style == PdfStyle.Heading) {
                blocks.getOrNull(index + 1)?.let { it.style.spaceBefore + it.style.lineHeight } ?: 0f
            } else 0f
            if (y > 0f && y + gap + block.style.lineHeight + keepWith > height) newPage() else y += gap
            lines.forEach { line ->
                if (y + block.style.lineHeight > height && y > 0f) newPage()
                y += block.style.lineHeight
                pages.last() += PdfLine(line, block.style, block.indent, y - block.style.lineHeight * 0.28f)
            }
        }
        return pages.filter { it.isNotEmpty() }.ifEmpty { listOf(emptyList()) }
    }

    private fun breakChars(text: String, width: Float, measure: (String) -> Float): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = start + 1
            while (end < text.length && measure(text.substring(start, end + 1)) <= width) end++
            out += text.substring(start, end)
            start = end
        }
        return out.ifEmpty { listOf("") }
    }

    /** Inline Markdown made readable as plain text: emphasis and backticks dropped, links as "text (url)". */
    private fun inline(text: String) = text
        .replace(LINK) { m -> "${m.groupValues[1]} (${m.groupValues[2]})" }
        .replace("**", "")
        .replace("`", "")

    private val SPEAKER = Regex("^\\*\\*[^*]+:\\*\\*$")
    private val HEADING = Regex("^#{1,6}\\s+")
    private val BULLET = Regex("^(?:[-*+]|\\d+[.)])\\s+")
    private val LINK = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")
}

/** Draws a chat export as an A4 PDF with Android's [PdfDocument]; code in a monospace face. */
internal object ChatPdf {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48f
    private const val FOOTER = 24f

    fun write(markdown: String, out: OutputStream) {
        val paints = PdfStyle.entries.associateWith { style ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = style.size
                color = if (style == PdfStyle.Meta) Color.DKGRAY else Color.BLACK
                typeface = when {
                    style.mono -> Typeface.MONOSPACE
                    style.bold -> Typeface.DEFAULT_BOLD
                    else -> Typeface.DEFAULT
                }
            }
        }
        val codeBackground = Paint().apply { color = Color.rgb(242, 242, 242) }
        val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.GRAY }
        val width = PAGE_WIDTH - 2 * MARGIN
        val height = PAGE_HEIGHT - 2 * MARGIN - FOOTER
        val pages = PdfLayout.paginate(PdfLayout.blocks(markdown), width, height) { text, style -> paints.getValue(style).measureText(text) }
        val document = PdfDocument()
        try {
            pages.forEachIndexed { i, lines ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, i + 1).create())
                val canvas = page.canvas
                lines.forEach { line ->
                    val x = MARGIN + line.x
                    val y = MARGIN + line.y
                    if (line.style == PdfStyle.Code) {
                        val top = y - line.style.lineHeight * 0.72f
                        canvas.drawRect(MARGIN - 4f, top, MARGIN + width + 4f, top + line.style.lineHeight, codeBackground)
                    }
                    canvas.drawText(line.text, x, y, paints.getValue(line.style))
                }
                val label = "${i + 1} / ${pages.size}"
                canvas.drawText(label, PAGE_WIDTH - MARGIN - footer.measureText(label), PAGE_HEIGHT - MARGIN, footer)
                document.finishPage(page)
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
    }
}
