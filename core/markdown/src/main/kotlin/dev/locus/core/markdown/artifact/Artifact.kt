package dev.locus.core.markdown.artifact

import java.security.MessageDigest

/** What an artifact is written in. Both render in the same sandbox (docs/design/artifacts.md). */
enum class ArtifactKind(val label: String, val extension: String, val mimeType: String) {
    Html("HTML", "html", "text/html"),
    Svg("SVG", "svg", "image/svg+xml"),
}

/**
 * A piece of model output to run in the artifact sandbox: an ```` ```html artifact ```` or
 * ```` ```svg artifact ```` fence in a reply, or an .html / .svg file the agent sent with `send_file`.
 * Its [content] is untrusted.
 */
class Artifact(val kind: ArtifactKind, val content: String, val title: String? = null) {
    /** Identifies the content (not where it appeared), for snapshots. */
    val key: String = artifactKey(kind, content)

    /** UTF-8 size, which is what the size cap counts. */
    val bytes: Int get() = content.toByteArray(Charsets.UTF_8).size

    /** A file name for saving it: the title made safe, or "artifact". */
    fun fileName(): String {
        val stem = title?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-")?.trim('-')?.take(48)?.ifBlank { null } ?: "artifact"
        return "$stem.${kind.extension}"
    }
}

/** The fence info of an artifact block: its kind and optional title. */
data class ArtifactFence(val kind: ArtifactKind, val title: String?)

/**
 * The convention (docs/design/artifacts.md): the fence's first word is `html` or `svg` and a later
 * word is `artifact`, optionally with `title="…"`. A plain ```` ```html ```` fence stays code, so HTML
 * the model shows as an example is never run, and the rule depends on the reply's text alone (no state
 * that could make a saved reply render differently later).
 */
fun parseArtifactFence(info: String?): ArtifactFence? {
    val text = info?.trim().orEmpty()
    if (text.isEmpty()) return null
    val words = text.split(Regex("\\s+"))
    val kind = when (words.first().lowercase()) {
        "html", "htm" -> ArtifactKind.Html
        "svg" -> ArtifactKind.Svg
        else -> return null
    }
    if (words.drop(1).none { it.equals("artifact", ignoreCase = true) }) return null
    val title = TITLE.find(text)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }?.trim()?.take(80)?.ifBlank { null }
    return ArtifactFence(kind, title)
}

private val TITLE = Regex("""title=(?:"([^"]*)"|(\S+))""", RegexOption.IGNORE_CASE)

/** An .html, .htm or .svg file (by name) runs as an artifact; anything else stays a file card. */
fun artifactKindForFile(name: String): ArtifactKind? = when (name.substringAfterLast('.', "").lowercase()) {
    "html", "htm" -> ArtifactKind.Html
    "svg" -> ArtifactKind.Svg
    else -> null
}

/**
 * The artifact fences of a reply, in order: only closed fences (a fence still streaming isn't run)
 * that follow the convention of [parseArtifactFence]. Fences follow CommonMark: up to three spaces of
 * indent, three or more backticks or tildes, closed by the same character at least as long.
 */
fun findArtifacts(markdown: String): List<Artifact> {
    val out = mutableListOf<Artifact>()
    val lines = markdown.replace("\r\n", "\n").split('\n')
    var i = 0
    while (i < lines.size) {
        val open = FENCE_OPEN.matchEntire(lines[i])
        if (open == null) {
            i++
            continue
        }
        val fence = open.groupValues[1]
        val info = open.groupValues[2]
        // A backtick fence's info string can't contain backticks.
        if (fence[0] == '`' && '`' in info) {
            i++
            continue
        }
        val body = mutableListOf<String>()
        var j = i + 1
        var closed = false
        while (j < lines.size) {
            val close = FENCE_CLOSE.matchEntire(lines[j])
            if (close != null && close.groupValues[1][0] == fence[0] && close.groupValues[1].length >= fence.length) {
                closed = true
                break
            }
            body += lines[j]
            j++
        }
        if (!closed) break
        parseArtifactFence(info)?.let { out += Artifact(it.kind, body.joinToString("\n"), it.title) }
        i = j + 1
    }
    return out
}

private val FENCE_OPEN = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
private val FENCE_CLOSE = Regex("^ {0,3}(`{3,}|~{3,})\\s*$")

/** A SHA-256 over the format version, the kind and the content with line endings normalised. */
fun artifactKey(kind: ArtifactKind, content: String): String {
    val normalized = content.replace("\r\n", "\n")
    val digest = MessageDigest.getInstance("SHA-256").digest("artifact|$ARTIFACT_FORMAT|${kind.name}|$normalized".toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

/** Bumped when the wrapping document changes, so old snapshots aren't reused. */
const val ARTIFACT_FORMAT = 1

/**
 * The largest artifact rendered: 2 MiB of UTF-8. Enough for a page with an inlined chart library
 * (Chart.js or D3 are a few hundred KB minified) and its data; anything bigger is shown as a file
 * with its source, not run.
 */
const val MAX_ARTIFACT_BYTES = 2 * 1024 * 1024

/** Whether [bytes] of content may be rendered. Empty content isn't. */
fun withinArtifactCap(bytes: Long): Boolean = bytes in 1..MAX_ARTIFACT_BYTES.toLong()
