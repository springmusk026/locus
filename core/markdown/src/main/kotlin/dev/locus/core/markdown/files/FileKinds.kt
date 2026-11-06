package dev.locus.core.markdown.files

/**
 * How Locus's file viewer (the Workspace's, feature/workspace `preview/`) shows a file, chosen from
 * the name. It lives here, below both features, so the chat's file cards route Open the same way the
 * Workspace shows the file. Text formats are confirmed by sniffing when the file is read.
 */
enum class PreviewKind { Pdf, Image, Markdown, Web, Table, Json, Code, Text, Audio, Video, Binary }

private val imageExts = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif", "ico", "wbmp")
private val audioExts = setOf("mp3", "wav", "ogg", "oga", "opus", "flac", "m4a", "aac", "amr", "mid", "midi", "3ga")
private val videoExts = setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "3g2")
private val binaryExts = setOf(
    "zip", "tar", "gz", "tgz", "bz2", "xz", "zst", "7z", "rar", "lz4", "jar", "aar",
    "apk", "aab", "apks", "xapk", "exe", "msi", "dll", "so", "bin", "elf", "o", "a", "class", "dex",
    "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "epub",
    "ttf", "otf", "woff", "woff2", "db", "sqlite", "sqlite3", "db3", "tif", "tiff", "psd",
    "avi", "wmv", "p12", "pfx", "jks", "keystore", "der", "pyc",
)

/** The lower-case extension of [name]'s last segment, or "" (none, a dotfile, or a trailing dot). */
fun extensionOf(name: String): String {
    val base = name.substringAfterLast('/').lowercase()
    val dot = base.lastIndexOf('.')
    return if (dot <= 0 || dot == base.length - 1) "" else base.substring(dot + 1)
}

/**
 * The viewer for [name] by its extension. Anything unlisted is [PreviewKind.Text]; the Workspace
 * refines that to Code when it has a highlighter for it, and to Binary when the bytes say so.
 */
fun basePreviewKind(name: String): PreviewKind = when (extensionOf(name)) {
    "pdf" -> PreviewKind.Pdf
    in imageExts -> PreviewKind.Image
    "md", "markdown", "mdown", "mkd" -> PreviewKind.Markdown
    "html", "htm", "xhtml", "svg" -> PreviewKind.Web
    "csv", "tsv" -> PreviewKind.Table
    "json", "jsonc", "json5", "geojson", "ipynb", "webmanifest" -> PreviewKind.Json
    in audioExts -> PreviewKind.Audio
    in videoExts -> PreviewKind.Video
    in binaryExts -> PreviewKind.Binary
    else -> PreviewKind.Text
}

/** Where Open on a file sent in chat takes the user. */
enum class OpenTarget {
    /** Locus's own viewer (the Workspace's, opened on that file). */
    InApp,

    /** Another app, through Android's chooser: Locus has no viewer for it. */
    OtherApp,
}

/**
 * Where Open goes for a file named [name] with type [mimeType] (docs/design/artifacts.md, "Opening
 * a sent file"). A listed extension decides: everything but [PreviewKind.Binary] has a viewer. An
 * unlisted one (code, logs, no extension) opens in Locus as text, which shows a binary file's details
 * and "Open with…" if the bytes turn out not to be text, unless its type already says it is media,
 * a font or a non-text document, which the viewer would only show as bytes.
 */
fun openTargetFor(name: String, mimeType: String?): OpenTarget {
    val kind = basePreviewKind(name)
    if (kind == PreviewKind.Binary) return OpenTarget.OtherApp
    // Every listed extension maps to something other than the Text fallback.
    if (kind != PreviewKind.Text) return OpenTarget.InApp
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    val family = mime.substringBefore('/')
    val subtype = mime.substringAfter('/', "")
    return when {
        mime.isEmpty() || mime == "application/octet-stream" -> OpenTarget.InApp
        family == "text" -> OpenTarget.InApp
        family == "image" || family == "audio" || family == "video" || family == "font" || family == "model" -> OpenTarget.OtherApp
        family == "application" && textApplicationTypes.any { subtype == it || subtype.endsWith("+$it") } -> OpenTarget.InApp
        family == "application" && (subtype.startsWith("x-sh") || subtype.contains("script")) -> OpenTarget.InApp
        else -> OpenTarget.OtherApp
    }
}

private val textApplicationTypes = setOf("json", "xml", "yaml", "x-yaml", "toml", "sql", "x-sql", "x-httpd-php", "x-perl", "x-python", "x-ruby", "x-tex", "x-latex")
