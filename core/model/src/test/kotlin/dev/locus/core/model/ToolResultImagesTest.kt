package dev.locus.core.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tool-result images are stored without changing a byte of chats saved before them. */
class ToolResultImagesTest {
    // The same settings as the database's (core/data ConversationRepository.json).
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "kind" }
    private val parts = ListSerializer(Part.serializer())

    private val old =
        """[{"kind":"tool_result","callId":"t1","toolName":"read_file","content":"hi","decision":"AUTO_ALLOWED"},""" +
            """{"kind":"attachment","name":"a.jpg","mimeType":"image/jpeg","path":"/p/a.jpg","workspacePath":"inbox/a.jpg","sizeBytes":12}]"""

    @Test
    fun `old rows load unchanged and encode to the same bytes`() {
        val decoded = json.decodeFromString(parts, old)
        val result = decoded[0] as Part.ToolResult
        assertTrue(result.images.isEmpty())
        assertEquals(null, (decoded[1] as Part.Attachment).width)
        assertEquals(old, json.encodeToString(parts, decoded))
    }

    @Test
    fun `images round-trip`() {
        val image = Part.Attachment("home.png", "image/png", "/files/tool-images/ab.png", "shots/home.png", 1234, 800, 600)
        val result = Part.ToolResult("t1", ViewImages.TOOL, "Showing shots/home.png (800×600).", images = listOf(image))
        val text = json.encodeToString(parts, listOf(result))
        assertEquals(listOf(result), json.decodeFromString(parts, text))
        assertTrue(""""images":[{"name":"home.png"""" in text, text)
    }

    @Test
    fun `a view_image call's paths`() {
        assertEquals(listOf("a.png"), ViewImages.paths("""{"path":"a.png"}"""))
        assertEquals(listOf("a.png", "b.png"), ViewImages.paths("""{"path":"a.png","paths":["a.png"," b.png ",""]}"""))
        assertEquals(emptyList(), ViewImages.paths("not json"))
    }
}
