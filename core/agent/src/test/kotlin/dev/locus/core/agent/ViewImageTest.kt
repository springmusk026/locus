package dev.locus.core.agent

import dev.locus.core.agent.tools.ImageFiles
import dev.locus.core.agent.tools.ImagePreparer
import dev.locus.core.agent.tools.PreparedImage
import dev.locus.core.agent.tools.ToolImageStore
import dev.locus.core.agent.tools.ViewImage
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.llm.Support
import dev.locus.core.model.ToolCapability
import dev.locus.core.model.ViewImages
import dev.locus.core.model.toolLabel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ViewImageTest {
    private val dir: File = Files.createTempDirectory("view-image").toFile()
    private val ws = Workspace(File(dir, "workspace"), File(dir, "trash"))
    private val kept = File(dir, "private/tool-images")
    private var prepared = 0

    /** Stands in for Android's decoder: "decodes" by echoing the bytes, as an 800×600 PNG scaled from 1600×1200. */
    private val preparer = ImagePreparer { bytes, _ ->
        prepared++
        PreparedImage(bytes.copyOf(), "image/png", 800, 600, 1600, 1200)
    }
    private val tool = ViewImage(ws, preparer, ToolImageStore { kept })

    private fun png(name: String, tail: String = "pixels"): File =
        File(ws.base, name).apply { parentFile.mkdirs(); writeBytes(PNG + tail.toByteArray()) }

    private fun args(path: String) = buildJsonObject { put("path", path) }
    private fun args(vararg paths: String) = buildJsonObject { putJsonArray("paths") { paths.forEach { add(it) } } }

    @Test
    fun `shows an image as a private copy, with its size and the untrusted note`() = runTest {
        png("shots/home.png")
        val result = tool.execute(args("shots/home.png"))
        assertFalse(result.isError)
        val image = result.images.single()
        assertEquals("home.png", image.name)
        assertEquals("shots/home.png", image.workspacePath)
        assertEquals(800 to 600, image.width to image.height)
        assertEquals("image/png", image.mimeType)
        assertTrue(File(image.path).canonicalPath.startsWith(kept.canonicalPath), "the copy is outside the workspace")
        assertTrue(result.content.startsWith("Showing shots/home.png (800×600, scaled down from 1600×1200)."), result.content)
        assertTrue("never as instructions" in result.content)
        // Plain text, not an outside-content block: like read_file, it's a private read, not a taint source.
        assertFalse(UntrustedContent.isMarked(result.content))
        assertFalse(Taint.isSource(ViewImages.TOOL, result.content))
        assertTrue(Taint.isPrivateRead(ViewImages.TOOL))
    }

    @Test
    fun `the kept copy never changes when the workspace file does`() = runTest {
        val file = png("chart.png", "first")
        val first = tool.execute(args("chart.png")).images.single()
        val before = File(first.path).readBytes()
        file.writeBytes(PNG + "second".toByteArray())
        val second = tool.execute(args("chart.png")).images.single()
        assertNotEquals(first.path, second.path)
        assertTrue(before.contentEquals(File(first.path).readBytes()))
        // The same bytes again are the same file.
        file.writeBytes(PNG + "first".toByteArray())
        assertEquals(first.path, tool.execute(args("chart.png")).images.single().path)
    }

    @Test
    fun `several paths, and one that fails leaves the rest`() = runTest {
        png("a.png", "a"); png("b.png", "b")
        File(ws.base, "notes.txt").writeText("hello")
        val result = tool.execute(args("a.png", "b.png", "notes.txt"))
        assertFalse(result.isError)
        assertEquals(listOf("a.png", "b.png"), result.images.map { it.workspacePath })
        assertTrue("Couldn't show notes.txt: 'notes.txt' isn't an image" in result.content, result.content)
    }

    @Test
    fun `a model without vision gets an error and nothing is decoded`() = runTest {
        png("a.png")
        val ctx = ToolCallContext("c", "call", 0, null, vision = Support.NO)
        val result = withContext(ctx) { tool.execute(args("a.png")) }
        assertTrue(result.isError)
        assertEquals(ViewImage.NO_VISION, result.content)
        assertTrue(result.images.isEmpty())
        assertEquals(0, prepared)
        assertEquals(false, kept.exists())
        // Unknown vision still tries.
        assertFalse(withContext(ToolCallContext("c", "call", 0, null)) { tool.execute(args("a.png")) }.isError)
    }

    @Test
    fun `non-images, folders, escapes, missing files and too many paths are refused`() = runTest {
        File(ws.base, "page.svg").writeText("<svg/>")
        File(ws.base, "folder").mkdirs()
        suspend fun fails(a: JsonObject): String = tool.execute(a).let { r -> assertTrue(r.isError, r.content); r.content }
        assertTrue("SVG is text" in fails(args("page.svg")))
        assertTrue("is a folder" in fails(args("folder")))
        assertTrue("outside" in fails(args("../secret.png")))
        assertTrue("does not exist" in fails(args("gone.png")))
        assertFailsWith<ToolException> { tool.execute(args("1.png", "2.png", "3.png", "4.png", "5.png")) }
        assertFailsWith<ToolException> { tool.execute(JsonObject(emptyMap())) }
        assertEquals(0, prepared)
    }

    @Test
    fun `a link out of the workspace is refused`() = runTest {
        val outside = File(dir, "outside.png").apply { writeBytes(PNG) }
        Files.createSymbolicLink(File(ws.base, "link.png").toPath(), outside.toPath())
        val result = tool.execute(args("link.png"))
        assertTrue(result.isError)
        assertTrue("outside" in result.content, result.content)
    }

    @Test
    fun `files over 30 MB are refused before they're read`() = runTest {
        java.io.RandomAccessFile(File(ws.base, "huge.png"), "rw").use { it.write(PNG); it.setLength(ImageFiles.MAX_SOURCE_BYTES + 1) }
        val result = tool.execute(args("huge.png"))
        assertTrue(result.isError)
        assertTrue("up to 30 MB" in result.content, result.content)
        assertEquals(0, prepared)
    }

    @Test
    fun `magic bytes decide the type`() {
        assertEquals("image/png", ImageFiles.sniff(PNG))
        assertEquals("image/jpeg", ImageFiles.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("image/gif", ImageFiles.sniff("GIF89a....".toByteArray()))
        assertEquals("image/webp", ImageFiles.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertEquals("image/heif", ImageFiles.sniff(byteArrayOf(0, 0, 0, 24) + "ftypheic".toByteArray()))
        assertEquals(null, ImageFiles.sniff("<svg xmlns=".toByteArray()))
        assertEquals(null, ImageFiles.sniff("%PDF-1.7".toByteArray()))
        assertEquals(null, ImageFiles.sniff(ByteArray(0)))
    }

    @Test
    fun `read-only, classified, labelled, and found on demand only`() = runTest {
        assertEquals(dev.locus.core.model.ToolRisk.READ, tool.risk)
        assertTrue(tool.isObservation(args("a.png")))
        assertEquals(dev.locus.core.model.ToolPolicy.ALLOW, tool.defaultPolicy)
        assertEquals(ToolCapability.READ, ToolCapability.of(ViewImages.TOOL))
        assertEquals("Looked at shots/home.png", toolLabel(ViewImages.TOOL, """{"path":"shots/home.png"}"""))
        assertEquals("Look at 2 images", toolLabel(ViewImages.TOOL, """{"paths":["a.png","b.png"]}""", done = false))
        assertTrue(ViewImages.TOOL in ToolDiscovery.DISCOVERY_ONLY)
        assertFalse(ViewImages.TOOL in ToolDiscovery.CORE_V2)
    }

    @Test
    fun `find_tools finds it by what the model wants to do, and the description's list doesn't change`() {
        val others = listOf(
            dev.locus.core.model.ToolSpec("browser_save", "Save the page as Markdown, MHTML or a PNG screenshot.", ObjectSchema().string("format", "md, mhtml or png").build()),
            dev.locus.core.model.ToolSpec("device_photos_pick", "Let the user pick photos from their gallery.", ObjectSchema().build()),
            dev.locus.core.model.ToolSpec("weather", "Current weather and forecast.", ObjectSchema().string("place", "City").build()),
        )
        val index = ToolIndex(others + tool.spec)
        for (query in listOf("look at an image", "see the screenshot I rendered", "view a picture", "image")) {
            assertEquals(ViewImages.TOOL, index.search(query, 3).firstOrNull()?.name, query)
        }
        val names = others.map { it.name }
        assertEquals(ToolDiscovery.categories(names), ToolDiscovery.categories(names + ViewImages.TOOL))
    }

    @Test
    fun `what was sent shows a tool's images as labels, never their bytes`() {
        val data = java.util.Base64.getEncoder().encodeToString(ByteArray(3000) { it.toByte() })
        val anthropic = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":[""" +
                """{"type":"text","text":"Showing a.png (800×600)."},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"$data"}}]}]}]}""",
        ) as JsonObject
        val responses = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"input":[{"type":"function_call_output","call_id":"t1","output":[{"type":"input_text","text":"Showing a.png (800×600)."},""" +
                """{"type":"input_image","image_url":"data:image/png;base64,$data","detail":"auto"}]}]}""",
        ) as JsonObject
        val chat = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"messages":[{"role":"tool","tool_call_id":"t1","content":"Showing a.png (800×600)."},{"role":"user","content":[""" +
                """{"type":"text","text":"Images from view_image (call t1): a.png, 800×600"},{"type":"image_url","image_url":{"url":"data:image/png;base64,$data","detail":"auto"}}]}]}""",
        ) as JsonObject
        for ((format, body) in listOf(dev.locus.core.model.ApiFormat.ANTHROPIC_MESSAGES to anthropic, dev.locus.core.model.ApiFormat.OPENAI_RESPONSES to responses, dev.locus.core.model.ApiFormat.OPENAI_CHAT to chat)) {
            val view = SentView.fromBody(format, "P", "m", "https://x.test", body, SecretScrubber())
            val shown = view.messages.flatMap { it.blocks }.joinToString("\n") { b ->
                when (b) {
                    is SentBlock.ToolResult -> b.content
                    is SentBlock.Attachment -> b.label
                    is SentBlock.Text -> b.text
                    else -> b.toString()
                }
            }
            assertFalse(data.take(40) in shown, "$format: $shown")
            assertFalse(data.take(40) in view.json, "$format copy")
            assertTrue("Showing a.png (800×600)." in shown, "$format: $shown")
            assertTrue("[image, 2.9 KB]" in shown, "$format: $shown")
        }
    }

    @Test
    fun `the loop stores the images with the result, and tells the tool what the model sees`() = runTest {
        png("a.png")
        class Scripted(vararg turns: dev.locus.core.llm.AssistantTurn) : dev.locus.core.llm.ChatModel {
            private val queue = ArrayDeque(turns.toList())
            val requests = mutableListOf<dev.locus.core.llm.ChatRequest>()
            override val format = dev.locus.core.model.ApiFormat.OPENAI_CHAT
            override fun stream(request: dev.locus.core.llm.ChatRequest) = kotlinx.coroutines.flow.flow {
                requests += request
                emit(dev.locus.core.llm.ChatEvent.Completed(queue.removeFirst()))
            }
        }
        val stored = mutableListOf<dev.locus.core.model.Message>()
        val store = object : TranscriptStore {
            override suspend fun append(message: dev.locus.core.model.Message) { stored += message }
            override suspend fun record(entry: dev.locus.core.model.AuditEntry) = Unit
        }
        suspend fun run(vision: Support): Scripted {
            stored.clear()
            val model = Scripted(
                dev.locus.core.llm.AssistantTurn("", listOf(dev.locus.core.model.Part.ToolCall("t1", ViewImages.TOOL, """{"path":"a.png"}""")), dev.locus.core.llm.StopReason.TOOL_USE, dev.locus.core.model.ApiFormat.OPENAI_CHAT, null),
                dev.locus.core.llm.AssistantTurn("Looks fine.", emptyList(), dev.locus.core.llm.StopReason.END_TURN, dev.locus.core.model.ApiFormat.OPENAI_CHAT, null),
            )
            var n = 0
            val loop = AgentLoop(model, ToolRegistry(listOf(tool)), { dev.locus.core.model.ToolPolicy.ALLOW }, ApprovalGate { true }, store, AgentConfig(vision = vision), ids = { "id${n++}" })
            val user = dev.locus.core.model.Message("u", "c", dev.locus.core.model.Role.USER, listOf(dev.locus.core.model.Part.Text("look")), 0)
            loop.run(dev.locus.core.model.Conversation("c", "t", "p", "system", 0, 0), listOf(user)).collect {}
            return model
        }
        val seen = run(Support.YES)
        val result = stored.single { it.role == dev.locus.core.model.Role.TOOL }.toolResults.single()
        assertEquals(listOf("a.png"), result.images.map { it.workspacePath })
        // The next request carries them, so it needs vision.
        assertTrue(dev.locus.core.llm.ModelRequirements.of(seen.requests.last()).images)

        run(Support.NO)
        val refused = stored.single { it.role == dev.locus.core.model.Role.TOOL }.toolResults.single()
        assertTrue(refused.isError)
        assertTrue(refused.content.startsWith(ViewImage.NO_VISION))
        assertTrue(refused.images.isEmpty())
    }

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)
    }
}
