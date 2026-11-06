package dev.locus.core.markdown.files

import org.junit.Test
import kotlin.test.assertEquals

/** Where a sent file's Open goes: Locus's viewer for every type it shows, another app otherwise. */
class FileKindsTest {
    private fun target(name: String, mime: String? = null) = openTargetFor(name, mime)

    @Test
    fun `every type the Workspace viewer shows opens in Locus`() {
        for (name in listOf(
            "create-invoice.html", "page.HTM", "logo.svg", "notes.md", "report.pdf", "photo.jpg", "shot.PNG", "anim.webp",
            "data.csv", "data.tsv", "package.json", "song.mp3", "clip.mp4", "main.py", "App.kt", "server.log", "README",
        )) {
            assertEquals(OpenTarget.InApp, target(name, "application/octet-stream"), name)
        }
    }

    @Test
    fun `types with no viewer go to another app`() {
        for (name in listOf("bundle.zip", "app.apk", "letter.docx", "sheet.xlsx", "deck.pptx", "scan.tiff", "font.woff2", "book.epub", "old.avi")) {
            assertEquals(OpenTarget.OtherApp, target(name, "application/octet-stream"), name)
        }
    }

    @Test
    fun `the extension decides over the type`() {
        // A listed extension wins whatever the type says (an .html sent as octet-stream is still a page).
        assertEquals(OpenTarget.InApp, target("index.html", "application/octet-stream"))
        assertEquals(OpenTarget.OtherApp, target("archive.zip", "text/plain"))
    }

    @Test
    fun `an unlisted extension is routed by its type`() {
        // Text, code and unknown bytes open as text; the viewer shows a binary file's details and Open with….
        assertEquals(OpenTarget.InApp, target("config.ini", "text/plain"))
        assertEquals(OpenTarget.InApp, target("schema.graphql", null))
        assertEquals(OpenTarget.InApp, target("dump.dat", "application/octet-stream"))
        assertEquals(OpenTarget.InApp, target("feed.atom", "application/atom+xml"))
        assertEquals(OpenTarget.InApp, target("deploy.yml", "application/x-yaml; charset=utf-8"))
        assertEquals(OpenTarget.InApp, target("run.sh", "application/x-sh"))
        // Media, fonts and documents the viewer can't show go to another app.
        assertEquals(OpenTarget.OtherApp, target("photo.jxl", "image/jxl"))
        assertEquals(OpenTarget.OtherApp, target("voice.amr-wb", "audio/amr-wb"))
        assertEquals(OpenTarget.OtherApp, target("film.ogv", "video/ogg"))
        assertEquals(OpenTarget.OtherApp, target("glyphs.ttc", "font/collection"))
        assertEquals(OpenTarget.OtherApp, target("mail.msg", "application/vnd.ms-outlook"))
        assertEquals(OpenTarget.OtherApp, target("model.glb", "model/gltf-binary"))
    }

    @Test
    fun `the shared table names each viewer`() {
        assertEquals(PreviewKind.Web, basePreviewKind("a/b/create-invoice.html"))
        assertEquals(PreviewKind.Pdf, basePreviewKind("Report.PDF"))
        assertEquals(PreviewKind.Image, basePreviewKind("a.heic"))
        assertEquals(PreviewKind.Table, basePreviewKind("x.tsv"))
        assertEquals(PreviewKind.Binary, basePreviewKind("x.docx"))
        assertEquals(PreviewKind.Text, basePreviewKind("main.py"), "the Workspace refines unlisted names with its highlighter")
        assertEquals("", extensionOf(".bashrc"))
        assertEquals("", extensionOf("trailing."))
        assertEquals("gz", extensionOf("dir.v2/archive.tar.gz"))
    }
}
