package dev.locus.core.markdown

import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import java.security.MessageDigest

/** What a fenced block renders as. */
enum class FenceKind { Math, Mermaid, Code }

/** ```` ```math ```` (GitHub's display maths fence) and ```` ```mermaid ```` get rendered; anything else is code. */
fun fenceKind(language: String?): FenceKind {
    val word = language?.trim()?.split(' ', '\t', '{', ',')?.firstOrNull()?.lowercase()
    return when (word) {
        "math" -> FenceKind.Math
        "mermaid" -> FenceKind.Mermaid
        else -> FenceKind.Code
    }
}

/**
 * Whether a CODE_FENCE node has its closing fence. While a reply streams, the last fence is still
 * open; it shows as source until it closes.
 */
fun isFenceClosed(node: ASTNode): Boolean = node.children.any { it.type == MarkdownTokenTypes.CODE_FENCE_END }

/**
 * The cache key for a rendered diagram: a SHA-256 over everything that changes the picture (the
 * renderer version, the theme and the source with its line endings normalised).
 */
fun diagramCacheKey(source: String, dark: Boolean, rendererVersion: String = MERMAID_VERSION): String {
    val normalized = source.replace("\r\n", "\n").trim()
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("mermaid|$rendererVersion|${if (dark) "dark" else "light"}|$normalized".toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

/** The bundled mermaid.tiny.js (assets/mermaid). Part of the cache key, so an upgrade redraws. */
const val MERMAID_VERSION = "12.2.0"

/** [s] as a JavaScript string literal, safe to splice into evaluateJavascript. */
fun jsStringLiteral(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            ' ' -> sb.append("\\u2028")
            ' ' -> sb.append("\\u2029")
            else -> if (c < ' ' || c == '<' || c == '>') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
