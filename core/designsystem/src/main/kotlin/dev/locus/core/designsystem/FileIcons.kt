package dev.locus.core.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * File-type icons converted from material-icon-theme 5.40.0 (MIT). See core/designsystem/NOTICE.md for
 * the source and licence. Icons keep the source colours. [lightRes] is the source's `_light` variant,
 * used when the surface is light because the default one is drawn for dark editors.
 */
enum class FileIcon(@DrawableRes val res: Int, @DrawableRes val lightRes: Int = res) {
    Folder(R.drawable.ficon_folder),
    FolderGit(R.drawable.ficon_folder_git),
    FolderNode(R.drawable.ficon_folder_node),
    FolderSrc(R.drawable.ficon_folder_src),
    FolderDocs(R.drawable.ficon_folder_docs),
    FolderMail(R.drawable.ficon_folder_mail),
    FolderImages(R.drawable.ficon_folder_images),
    FolderScripts(R.drawable.ficon_folder_scripts),
    FolderTest(R.drawable.ficon_folder_test),
    FolderConfig(R.drawable.ficon_folder_config),
    FolderDist(R.drawable.ficon_folder_dist),
    FolderTemp(R.drawable.ficon_folder_temp),
    FolderTrash(R.drawable.ficon_folder_trash),
    FolderGithub(R.drawable.ficon_folder_github),
    FolderVscode(R.drawable.ficon_folder_vscode),
    FolderLib(R.drawable.ficon_folder_lib),
    FolderPublic(R.drawable.ficon_folder_public),
    FolderAudio(R.drawable.ficon_folder_audio),
    FolderVideo(R.drawable.ficon_folder_video),
    FolderArchive(R.drawable.ficon_folder_archive),
    FolderDatabase(R.drawable.ficon_folder_database),
    FolderKeys(R.drawable.ficon_folder_keys),
    FolderDownload(R.drawable.ficon_folder_download),
    FolderSkills(R.drawable.ficon_folder_skills),
    FolderPython(R.drawable.ficon_folder_python),
    FolderLog(R.drawable.ficon_folder_log),

    Python(R.drawable.ficon_python),
    JavaScript(R.drawable.ficon_javascript),
    TypeScript(R.drawable.ficon_typescript),
    Kotlin(R.drawable.ficon_kotlin),
    Java(R.drawable.ficon_java),
    Go(R.drawable.ficon_go),
    Rust(R.drawable.ficon_rust),
    C(R.drawable.ficon_c),
    Cpp(R.drawable.ficon_cpp),
    CHeader(R.drawable.ficon_h),
    CppHeader(R.drawable.ficon_hpp),
    Shell(R.drawable.ficon_shell),
    Html(R.drawable.ficon_html),
    Css(R.drawable.ficon_css),
    Json(R.drawable.ficon_json),
    Yaml(R.drawable.ficon_yaml),
    Toml(R.drawable.ficon_toml, R.drawable.ficon_toml_light),
    Xml(R.drawable.ficon_xml),
    Sql(R.drawable.ficon_sql),
    Docker(R.drawable.ficon_docker),
    Markdown(R.drawable.ficon_markdown),
    Readme(R.drawable.ficon_readme),
    Swift(R.drawable.ficon_swift),
    Ruby(R.drawable.ficon_ruby),
    Php(R.drawable.ficon_php),
    Lua(R.drawable.ficon_lua),
    CSharp(R.drawable.ficon_csharp),
    Dart(R.drawable.ficon_dart),
    RLang(R.drawable.ficon_r),
    Jupyter(R.drawable.ficon_jupyter),
    Gradle(R.drawable.ficon_gradle),
    Makefile(R.drawable.ficon_makefile),
    NodeJs(R.drawable.ficon_nodejs),
    Npm(R.drawable.ficon_npm),
    Git(R.drawable.ficon_git),

    Pdf(R.drawable.ficon_pdf),
    Word(R.drawable.ficon_word),
    Table(R.drawable.ficon_table),
    PowerPoint(R.drawable.ficon_powerpoint),
    Text(R.drawable.ficon_text),
    Todo(R.drawable.ficon_todo),
    Log(R.drawable.ficon_log),
    License(R.drawable.ficon_license),

    Image(R.drawable.ficon_image),
    Audio(R.drawable.ficon_audio),
    Video(R.drawable.ficon_video),
    Svg(R.drawable.ficon_svg),
    Font(R.drawable.ficon_font),

    Archive(R.drawable.ficon_zip),
    Apk(R.drawable.ficon_android),
    Exe(R.drawable.ficon_exe),
    Env(R.drawable.ficon_env),
    Lock(R.drawable.ficon_lock),
    Settings(R.drawable.ficon_settings),
    Key(R.drawable.ficon_key),
    Certificate(R.drawable.ficon_certificate),

    File(R.drawable.ficon_file),
}

private val folderNames: Map<String, FileIcon> = buildMap {
    fun put(icon: FileIcon, vararg names: String) = names.forEach { put(it, icon) }
    put(FileIcon.FolderGit, ".git", "git", "submodules")
    put(FileIcon.FolderNode, "node_modules")
    put(FileIcon.FolderSrc, "src", "source", "sources", "srcs")
    put(FileIcon.FolderDocs, "docs", "doc", "documents", "documentation", "notes", "wiki")
    put(FileIcon.FolderMail, "mail", "email", "emails", "messages")
    put(FileIcon.FolderDownload, "inbox", "download", "downloads", "imports", "import", "attachments", "uploads")
    put(FileIcon.FolderImages, "images", "image", "img", "imgs", "photos", "pictures", "screenshots", "assets", "icons")
    put(FileIcon.FolderScripts, "scripts", "script", "bin")
    put(FileIcon.FolderTest, "test", "tests", "__tests__", "spec", "specs", "testing")
    put(FileIcon.FolderConfig, "config", "configs", "conf", "settings", "etc", ".config")
    put(FileIcon.FolderDist, "dist", "build", "out", "output", "outputs", "target", "release")
    put(FileIcon.FolderTemp, "tmp", "temp", ".tmp", ".cache", "cache")
    put(FileIcon.FolderTrash, ".trash", "trash", ".trashes")
    put(FileIcon.FolderGithub, ".github")
    put(FileIcon.FolderVscode, ".vscode")
    put(FileIcon.FolderLib, "lib", "libs", "library", "vendor")
    put(FileIcon.FolderPublic, "public", "www", "static", "site")
    put(FileIcon.FolderAudio, "audio", "music", "sounds", "recordings")
    put(FileIcon.FolderVideo, "video", "videos", "movies")
    put(FileIcon.FolderArchive, "archive", "archives", "backup", "backups")
    put(FileIcon.FolderDatabase, "db", "database", "databases", "data")
    put(FileIcon.FolderKeys, ".ssh", "keys", "secrets", ".gnupg")
    put(FileIcon.FolderSkills, "skills", ".skills")
    put(FileIcon.FolderPython, "venv", ".venv", "__pycache__", "site-packages")
    put(FileIcon.FolderLog, "log", "logs")
}

private val fileNames: Map<String, FileIcon> = buildMap {
    fun put(icon: FileIcon, vararg names: String) = names.forEach { put(it, icon) }
    put(FileIcon.Docker, "dockerfile", "containerfile", ".dockerignore", "docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml")
    put(FileIcon.NodeJs, "package.json", ".nvmrc", ".node-version")
    put(FileIcon.Npm, ".npmrc", ".npmignore")
    put(FileIcon.Lock, "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "cargo.lock", "poetry.lock", "gemfile.lock", "composer.lock", "go.sum", "uv.lock", "bun.lockb", "flake.lock")
    put(FileIcon.Git, ".gitignore", ".gitattributes", ".gitmodules", ".gitkeep", ".git")
    put(FileIcon.Makefile, "makefile", "gnumakefile", "cmakelists.txt")
    put(FileIcon.Gradle, "gradlew", "gradlew.bat", "gradle.properties")
    put(FileIcon.Go, "go.mod", "go.work")
    put(FileIcon.Rust, "cargo.toml")
    put(FileIcon.Python, "requirements.txt", "pipfile", "pyproject.toml", "setup.py", "setup.cfg")
    put(FileIcon.Ruby, "gemfile", "rakefile")
    put(FileIcon.License, "license", "licence", "copying", "notice", "unlicense")
    put(FileIcon.Todo, "todo", "todo.md", "todo.txt")
    put(FileIcon.Settings, ".editorconfig", ".prettierrc", ".eslintrc", ".bashrc", ".zshrc", ".profile", ".bash_profile")
    put(FileIcon.Key, "id_rsa", "id_ed25519", "id_ecdsa", "authorized_keys", "known_hosts")
}

private val extensions: Map<String, FileIcon> = buildMap {
    fun put(icon: FileIcon, vararg exts: String) = exts.forEach { put(it, icon) }
    put(FileIcon.Python, "py", "pyw", "pyi", "pyx")
    put(FileIcon.JavaScript, "js", "mjs", "cjs", "jsx")
    put(FileIcon.TypeScript, "ts", "mts", "cts", "tsx")
    put(FileIcon.Kotlin, "kt", "kts")
    put(FileIcon.Java, "java", "jar", "class")
    put(FileIcon.Go, "go")
    put(FileIcon.Rust, "rs")
    put(FileIcon.C, "c")
    put(FileIcon.Cpp, "cpp", "cc", "cxx", "c++")
    put(FileIcon.CHeader, "h")
    put(FileIcon.CppHeader, "hpp", "hh", "hxx")
    put(FileIcon.Shell, "sh", "bash", "zsh", "fish", "ksh", "command", "ps1", "bat", "cmd")
    put(FileIcon.Html, "html", "htm", "xhtml")
    put(FileIcon.Css, "css", "scss", "sass", "less")
    put(FileIcon.Json, "json", "jsonc", "json5", "jsonl", "ndjson", "geojson")
    put(FileIcon.Yaml, "yaml", "yml")
    put(FileIcon.Toml, "toml")
    put(FileIcon.Xml, "xml", "xsd", "xsl", "plist", "rss", "atom")
    put(FileIcon.Sql, "sql", "db", "sqlite", "sqlite3", "db3")
    put(FileIcon.Markdown, "md", "markdown", "mdx")
    put(FileIcon.Swift, "swift")
    put(FileIcon.Ruby, "rb", "erb")
    put(FileIcon.Php, "php")
    put(FileIcon.Lua, "lua")
    put(FileIcon.CSharp, "cs")
    put(FileIcon.Dart, "dart")
    put(FileIcon.RLang, "r", "rmd")
    put(FileIcon.Jupyter, "ipynb")
    put(FileIcon.Gradle, "gradle")
    put(FileIcon.Makefile, "mk", "mak")
    put(FileIcon.Docker, "dockerfile")
    put(FileIcon.Pdf, "pdf")
    put(FileIcon.Word, "doc", "docx", "odt", "rtf")
    put(FileIcon.Table, "csv", "tsv", "xls", "xlsx", "ods")
    put(FileIcon.PowerPoint, "ppt", "pptx", "odp")
    put(FileIcon.Text, "txt", "text")
    put(FileIcon.Log, "log")
    put(FileIcon.Image, "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif", "ico", "tif", "tiff")
    put(FileIcon.Svg, "svg")
    put(FileIcon.Audio, "mp3", "wav", "ogg", "oga", "opus", "flac", "m4a", "aac", "amr", "mid", "midi")
    put(FileIcon.Video, "mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "wmv")
    put(FileIcon.Font, "ttf", "otf", "woff", "woff2")
    put(FileIcon.Archive, "zip", "tar", "gz", "tgz", "bz2", "xz", "zst", "7z", "rar", "lz4")
    put(FileIcon.Apk, "apk", "aab", "apks", "xapk")
    put(FileIcon.Exe, "exe", "msi", "dll", "so", "bin", "elf")
    put(FileIcon.Env, "env")
    put(FileIcon.Lock, "lock")
    put(FileIcon.Settings, "ini", "cfg", "conf", "config", "properties", "rc", "editorconfig")
    put(FileIcon.Key, "pem", "key", "pub", "gpg", "asc", "p12", "pfx", "jks", "keystore")
    put(FileIcon.Certificate, "crt", "cer", "csr", "der")
}

/**
 * The icon for a workspace entry, chosen from well-known names first (Dockerfile, package.json,
 * README.md, .gitignore, `.env.local`…), then by extension, case-insensitively. [name] may be a path;
 * only its last segment is used.
 */
fun fileIconFor(name: String, isDirectory: Boolean): FileIcon {
    val base = name.trimEnd('/').substringAfterLast('/').lowercase()
    if (isDirectory) return folderNames[base] ?: FileIcon.Folder
    fileNames[base]?.let { return it }
    if (base.startsWith("readme")) return FileIcon.Readme
    if (base == ".env" || base.startsWith(".env.") || base.endsWith(".env")) return FileIcon.Env
    if (base.startsWith("dockerfile") || base.endsWith(".dockerfile")) return FileIcon.Docker
    if (base.startsWith("license") || base.startsWith("licence")) return FileIcon.License
    val dot = base.lastIndexOf('.')
    if (dot <= 0 || dot == base.length - 1) return FileIcon.File
    return extensions[base.substring(dot + 1)] ?: FileIcon.File
}

/**
 * A file-type icon on the same neutral tile as [IconTile], so the colourful marks read in light and dark
 * mode. Decorative: the row's text names the file.
 */
@Composable
fun FileTypeIcon(name: String, isDirectory: Boolean, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val icon = fileIconFor(name, isDirectory)
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    Box(
        modifier
            .size(size)
            .clearAndSetSemantics {}
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(size / 3.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(if (light) icon.lightRes else icon.res), contentDescription = null, modifier = Modifier.size(size * 0.6f))
    }
}
