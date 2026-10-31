package dev.locus.feature.workspace.preview

import android.webkit.MimeTypeMap
import dev.snipme.highlights.model.SyntaxLanguage

/** How the Workspace viewer shows a file. Chosen from the name; text formats are confirmed by sniffing. */
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

fun extensionOf(name: String): String {
    val base = name.substringAfterLast('/').lowercase()
    val dot = base.lastIndexOf('.')
    return if (dot <= 0 || dot == base.length - 1) "" else base.substring(dot + 1)
}

fun previewKindFor(name: String): PreviewKind = when (extensionOf(name)) {
    "pdf" -> PreviewKind.Pdf
    in imageExts -> PreviewKind.Image
    "md", "markdown", "mdown", "mkd" -> PreviewKind.Markdown
    "html", "htm", "xhtml", "svg" -> PreviewKind.Web
    "csv", "tsv" -> PreviewKind.Table
    "json", "jsonc", "json5", "geojson", "ipynb", "webmanifest" -> PreviewKind.Json
    in audioExts -> PreviewKind.Audio
    in videoExts -> PreviewKind.Video
    in binaryExts -> PreviewKind.Binary
    else -> if (syntaxFor(name) != null) PreviewKind.Code else PreviewKind.Text
}

/**
 * The highlighter's language for a file, or null to show it plain. dev.snipme highlights 1.1.0 knows
 * C, C++, C#, CoffeeScript, Dart, Go, Java, JavaScript, Kotlin, Perl, PHP, Python, Ruby, Rust, Shell,
 * Swift and TypeScript; JSON is highlighted as JavaScript (it is a JS literal), and config/markup
 * formats get the library's generic DEFAULT rules (strings, numbers, comments).
 */
fun syntaxFor(name: String): SyntaxLanguage? {
    val base = name.substringAfterLast('/').lowercase()
    when {
        base == "dockerfile" || base.startsWith("dockerfile.") || base == "makefile" || base == "gnumakefile" -> return SyntaxLanguage.SHELL
        base == ".bashrc" || base == ".zshrc" || base == ".profile" || base == ".bash_profile" -> return SyntaxLanguage.SHELL
        base == "gemfile" || base == "rakefile" -> return SyntaxLanguage.RUBY
        base == "build" || base == "workspace" -> return SyntaxLanguage.PYTHON // Bazel/Starlark
    }
    return when (extensionOf(name)) {
        "py", "pyw", "pyi", "bzl", "star" -> SyntaxLanguage.PYTHON
        "kt", "kts" -> SyntaxLanguage.KOTLIN
        "java", "gradle", "groovy", "scala", "sc" -> SyntaxLanguage.JAVA
        "js", "mjs", "cjs", "jsx", "vue", "svelte" -> SyntaxLanguage.JAVASCRIPT
        "json", "jsonc", "json5", "geojson", "ipynb", "webmanifest" -> SyntaxLanguage.JAVASCRIPT
        "ts", "tsx", "mts", "cts" -> SyntaxLanguage.TYPESCRIPT
        "c", "h" -> SyntaxLanguage.C
        "cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "ino", "m", "mm" -> SyntaxLanguage.CPP
        "cs", "csx" -> SyntaxLanguage.CSHARP
        "rs" -> SyntaxLanguage.RUST
        "go" -> SyntaxLanguage.GO
        "rb", "erb", "rake", "gemspec" -> SyntaxLanguage.RUBY
        "php" -> SyntaxLanguage.PHP
        "swift" -> SyntaxLanguage.SWIFT
        "dart" -> SyntaxLanguage.DART
        "pl", "pm", "t" -> SyntaxLanguage.PERL
        "coffee" -> SyntaxLanguage.COFFEESCRIPT
        "sh", "bash", "zsh", "ksh", "fish", "command", "mk", "mak" -> SyntaxLanguage.SHELL
        "css", "scss", "sass", "less", "sql", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties",
        "xml", "xsd", "xsl", "plist", "html", "htm", "xhtml", "svg", "lua", "r", "hs", "ex", "exs",
        "clj", "zig", "nim", "jl", "proto", "graphql", "gql", "tf", "hcl", "env", "diff", "patch",
        "bat", "cmd", "ps1", "vb", "f90", "asm", "s", "elm", "ml", "fs", "sol", "cmake",
        -> SyntaxLanguage.DEFAULT
        else -> null
    }
}

/** A readable language name for the status line. */
fun languageLabel(name: String): String {
    val ext = extensionOf(name)
    return when (syntaxFor(name)) {
        SyntaxLanguage.PYTHON -> "Python"
        SyntaxLanguage.KOTLIN -> "Kotlin"
        SyntaxLanguage.JAVA -> if (ext == "java") "Java" else ext.replaceFirstChar { it.uppercase() }
        SyntaxLanguage.JAVASCRIPT -> if (previewKindFor(name) == PreviewKind.Json) "JSON" else "JavaScript"
        SyntaxLanguage.TYPESCRIPT -> "TypeScript"
        SyntaxLanguage.C -> "C"
        SyntaxLanguage.CPP -> "C++"
        SyntaxLanguage.CSHARP -> "C#"
        SyntaxLanguage.RUST -> "Rust"
        SyntaxLanguage.GO -> "Go"
        SyntaxLanguage.RUBY -> "Ruby"
        SyntaxLanguage.PHP -> "PHP"
        SyntaxLanguage.SWIFT -> "Swift"
        SyntaxLanguage.DART -> "Dart"
        SyntaxLanguage.PERL -> "Perl"
        SyntaxLanguage.COFFEESCRIPT -> "CoffeeScript"
        SyntaxLanguage.SHELL -> "Shell"
        else -> when (ext) {
            "" -> "Text"
            "yml" -> "YAML"
            "md", "markdown" -> "Markdown"
            "txt", "text" -> "Text"
            else -> ext.uppercase()
        }
    }
}

/** MIME type for Share / Open with. Falls back to text/plain for text kinds and octet-stream otherwise. */
fun mimeTypeFor(name: String, kind: PreviewKind = previewKindFor(name)): String {
    val ext = extensionOf(name)
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
    return when (kind) {
        PreviewKind.Markdown -> "text/markdown"
        PreviewKind.Json -> "application/json"
        PreviewKind.Table -> if (ext == "tsv") "text/tab-separated-values" else "text/csv"
        PreviewKind.Web -> "text/html"
        PreviewKind.Code, PreviewKind.Text -> "text/plain"
        PreviewKind.Pdf -> "application/pdf"
        PreviewKind.Image -> "image/*"
        PreviewKind.Audio -> "audio/*"
        PreviewKind.Video -> "video/*"
        PreviewKind.Binary -> if (ext == "apk") "application/vnd.android.package-archive" else "application/octet-stream"
    }
}
