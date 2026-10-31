package dev.locus.core.designsystem

import org.junit.Test
import kotlin.test.assertEquals

class FileIconsTest {
    private fun file(name: String) = fileIconFor(name, isDirectory = false)
    private fun dir(name: String) = fileIconFor(name, isDirectory = true)

    @Test
    fun codeByExtension() {
        assertEquals(FileIcon.Python, file("main.py"))
        assertEquals(FileIcon.JavaScript, file("app.mjs"))
        assertEquals(FileIcon.TypeScript, file("App.TSX"))
        assertEquals(FileIcon.Kotlin, file("build.gradle.kts"))
        assertEquals(FileIcon.Java, file("Main.java"))
        assertEquals(FileIcon.Go, file("server.go"))
        assertEquals(FileIcon.Rust, file("lib.rs"))
        assertEquals(FileIcon.C, file("x.c"))
        assertEquals(FileIcon.Cpp, file("x.cpp"))
        assertEquals(FileIcon.CHeader, file("x.h"))
        assertEquals(FileIcon.Shell, file("deploy.sh"))
        assertEquals(FileIcon.Html, file("index.html"))
        assertEquals(FileIcon.Css, file("site.css"))
        assertEquals(FileIcon.Json, file("data.json"))
        assertEquals(FileIcon.Yaml, file("ci.yml"))
        assertEquals(FileIcon.Toml, file("config.toml"))
        assertEquals(FileIcon.Xml, file("layout.xml"))
        assertEquals(FileIcon.Sql, file("schema.sql"))
        assertEquals(FileIcon.Markdown, file("notes.md"))
    }

    @Test
    fun documentsMediaArchives() {
        assertEquals(FileIcon.Pdf, file("report.PDF"))
        assertEquals(FileIcon.Word, file("letter.docx"))
        assertEquals(FileIcon.Table, file("b.csv"))
        assertEquals(FileIcon.Table, file("budget.xlsx"))
        assertEquals(FileIcon.PowerPoint, file("deck.pptx"))
        assertEquals(FileIcon.Text, file("notes.txt"))
        assertEquals(FileIcon.Image, file("photo.jpeg"))
        assertEquals(FileIcon.Svg, file("logo.svg"))
        assertEquals(FileIcon.Audio, file("voice.m4a"))
        assertEquals(FileIcon.Video, file("clip.mp4"))
        assertEquals(FileIcon.Archive, file("backup.tar.gz"))
        assertEquals(FileIcon.Archive, file("x.zip"))
        assertEquals(FileIcon.Apk, file("locus-release.apk"))
        assertEquals(FileIcon.Key, file("server.key"))
        assertEquals(FileIcon.Lock, file("Gemfile.lock"))
    }

    @Test
    fun wellKnownNames() {
        assertEquals(FileIcon.Docker, file("Dockerfile"))
        assertEquals(FileIcon.Docker, file("Dockerfile.dev"))
        assertEquals(FileIcon.Docker, file("docker-compose.yml"))
        assertEquals(FileIcon.NodeJs, file("package.json"))
        assertEquals(FileIcon.Lock, file("package-lock.json"))
        assertEquals(FileIcon.Readme, file("README.md"))
        assertEquals(FileIcon.Readme, file("readme"))
        assertEquals(FileIcon.Git, file(".gitignore"))
        assertEquals(FileIcon.Makefile, file("Makefile"))
        assertEquals(FileIcon.Env, file(".env"))
        assertEquals(FileIcon.Env, file(".env.local"))
        assertEquals(FileIcon.License, file("LICENSE"))
        assertEquals(FileIcon.License, file("LICENSE.md"))
        assertEquals(FileIcon.Rust, file("Cargo.toml"))
        assertEquals(FileIcon.Key, file("id_ed25519"))
    }

    @Test
    fun pathsAndFallbacks() {
        assertEquals(FileIcon.Markdown, file("notes/todo-list.md"))
        assertEquals(FileIcon.Table, file("inbox/b.csv"))
        assertEquals(FileIcon.File, file("noext"))
        assertEquals(FileIcon.File, file("weird.unknownext"))
        assertEquals(FileIcon.File, file("trailingdot."))
        assertEquals(FileIcon.File, file(".hidden"))
        assertEquals(FileIcon.File, file(""))
    }

    @Test
    fun folders() {
        assertEquals(FileIcon.Folder, dir("projects"))
        assertEquals(FileIcon.Folder, dir("report.pdf"))
        assertEquals(FileIcon.FolderGit, dir(".git"))
        assertEquals(FileIcon.FolderNode, dir("node_modules"))
        assertEquals(FileIcon.FolderSrc, dir("src"))
        assertEquals(FileIcon.FolderDownload, dir("inbox"))
        assertEquals(FileIcon.FolderDocs, dir("Notes"))
        assertEquals(FileIcon.FolderSrc, dir("app/src/"))
        assertEquals(FileIcon.FolderPython, dir(".venv"))
    }
}
