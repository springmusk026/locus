package dev.locus.core.data.chatimport

import dev.locus.core.model.ChatImportSource
import dev.locus.core.model.Role
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ChatGPT and Claude parsers on hand-written exports (src/test/resources/chatimport): trees with
 * branches, hidden plumbing, multimodal parts, missing fields, unknown types, empty conversations and
 * unicode. Nothing unexpected throws; it's skipped and counted.
 */
class ExportParserTest {
    private suspend fun entries(name: String) = ExportFixtures.readAll(ExportFixtures.file(name, ExportFixtures.bytes(name))).first

    @Test
    fun `chatgpt keeps the tree, follows current_node, and shows only what the user saw`() = runTest {
        val all = entries("chatgpt-conversations.json")
        val chats = all.filterIsInstance<ParsedEntry.Chat>().map { it.chat }
        assertEquals(2, chats.size)
        assertEquals(1, all.count { it is ParsedEntry.Empty })
        // {}, a null mapping and a bare string.
        assertEquals(3, all.count { it is ParsedEntry.Unreadable })

        val kyoto = chats.first()
        assertEquals(ChatImportSource.CHATGPT, kyoto.source)
        assertEquals("6789abcd-0000-4000-8000-000000000001", kyoto.sourceId)
        assertEquals("Trip to Kyoto 🍵", kyoto.title)
        assertEquals(1_704_067_200_123L, kyoto.createdAt)
        assertEquals("gpt-4o", kyoto.model)
        // System, the custom-instruction context, the tool call, the thoughts and the tool's quote are hidden;
        // the reply split by the tool step is one reply.
        assertEquals(listOf("u1", "a1b", "u2", "a2", "u2b", "a2b"), kyoto.messages.map { it.nodeId })
        val byId = kyoto.messages.associateBy { it.nodeId }
        assertNull(byId.getValue("u1").parentNodeId)
        assertEquals("u1", byId.getValue("a1b").parentNodeId)
        assertEquals("a1b", byId.getValue("u2").parentNodeId)
        assertEquals("a1b", byId.getValue("u2b").parentNodeId)
        assertEquals("Day 1: Uji for matcha.\n\nDay 2: Fushimi Inari.", byId.getValue("a1b").text)
        assertEquals(Role.ASSISTANT, byId.getValue("a1b").role)
        // current_node is the shown branch, though a2b is newer.
        assertEquals("a2", kyoto.activeLeaf)

        val u1 = byId.getValue("u1")
        assertEquals("Plan 3 days in Kyoto — 京都 — for a tea lover.\n\nAlso temples, please.", u1.text)
        assertEquals(listOf("itinerary.pdf"), u1.notImported)
        assertEquals(1, u1.imagesNotImported)
        assertEquals(1, kyoto.unknownParts)
        assertTrue("Think about Uji" !in kyoto.messages.joinToString { it.text })
        assertTrue("Lisbon" !in kyoto.messages.joinToString { it.text })

        // Missing title, times as ISO, a parent that isn't there, unknown content types.
        val plain = chats[1]
        assertNull(plain.title)
        assertEquals("Héllo without a title\nsecond line", plain.firstUserText)
        assertEquals(listOf("x1", "x3"), plain.messages.map { it.nodeId })
        assertEquals("x1", plain.messages[1].parentNodeId)
        assertEquals("Readable despite the new type", plain.messages[1].text)
        assertEquals(1_682_935_200_000L, plain.createdAt)
        assertEquals(1, plain.unknownParts)
    }

    @Test
    fun `claude keeps retries as branches, hides tool and thinking blocks, imports pasted text`() = runTest {
        val all = entries("claude-conversations.json")
        val chats = all.filterIsInstance<ParsedEntry.Chat>().map { it.chat }
        assertEquals(2, chats.size)
        assertEquals(1, all.count { it is ParsedEntry.Empty })

        val bread = chats.first()
        assertEquals(ChatImportSource.CLAUDE, bread.source)
        assertEquals("Sourdough schedule", bread.title)
        assertEquals(1_741_075_200_123L, bread.createdAt)
        assertEquals(listOf("m1", "m2", "m3"), bread.messages.map { it.nodeId })
        assertEquals(listOf(null, "m1", "m1"), bread.messages.map { it.parentNodeId })
        // No leaf pointer in Claude exports: the newest reply.
        assertEquals("m3", bread.activeLeaf)
        val first = bread.messages.first()
        assertTrue(first.text.startsWith("Plan a sourdough bake for Saturday. Ünïcode ✓"), first.text)
        assertTrue("Attached: starter-notes.txt\n\nStarter fed at 9pm." in first.text, first.text)
        assertEquals(listOf("kitchen.jpg"), first.notImported)
        assertEquals("Mix Friday 8pm, shape Saturday 7am.", bread.messages[1].text)
        assertEquals(1, bread.unknownParts)

        // A flat list out of order, no name: chained by time, named by the first question later.
        val flat = chats[1]
        assertNull(flat.title)
        assertEquals(listOf("b1", "b2"), flat.messages.map { it.nodeId })
        assertEquals(listOf(null, "b1"), flat.messages.map { it.parentNodeId })
        assertEquals("First question", flat.firstUserText)
    }

    @Test
    fun `a very long message and a deep conversation parse without trouble`() {
        val long = "é".repeat(2_000_000)
        val deep = ExportFixtures.chatGpt("deep", 1_700_000_000, turns = 5_000)
        val reader = ExportReader()
        val one = reader.parse(Json.parseToJsonElement("""{"id":"l","mapping":{"a":{"id":"a","parent":null,"message":{"author":{"role":"user"},"content":{"content_type":"text","parts":[${ExportFixtures.quote(long)}]}}}}}"""))
        assertEquals(long, (one as ParsedEntry.Chat).chat.messages.single().text)
        val many = reader.parse(Json.parseToJsonElement(deep)) as ParsedEntry.Chat
        assertEquals(10_000, many.chat.messages.size)
        assertEquals("n9999", many.chat.activeLeaf)
    }

    @Test
    fun `odd shapes are unreadable, never a crash`() {
        val reader = ExportReader()
        listOf(
            """{"mapping":{"a":5,"b":{"id":"b","parent":"b","message":{"author":{"role":"user"},"content":{"content_type":"text","parts":[1,null,{"x":1}]}}}}}""",
            """{"chat_messages":[1,2,{"sender":"human","content":"not a list"}]}""",
            """{"mapping":{"a":{"id":"a","parent":"b","message":{"author":{"role":"user"},"content":{"content_type":"text","parts":["loop a"]}}},"b":{"id":"b","parent":"a","message":{"author":{"role":"assistant"},"content":{"content_type":"text","parts":["loop b"]}}}}}""",
            """{"mapping":{},"create_time":"yesterday"}""",
            """[1]""",
        ).forEach { raw ->
            val parsed = reader.parse(Json.parseToJsonElement(raw))
            assertFalse(parsed is ParsedEntry.Chat && parsed.chat.messages.isEmpty(), raw)
        }
        // Parents that loop still keep both messages.
        val loop = reader.parse(Json.parseToJsonElement("""{"id":"l","mapping":{"a":{"id":"a","parent":"b","message":{"author":{"role":"user"},"content":{"content_type":"text","parts":["loop a"]}}},"b":{"id":"b","parent":"a","message":{"author":{"role":"assistant"},"content":{"content_type":"text","parts":["loop b"]}}}}}""")) as ParsedEntry.Chat
        assertEquals(2, loop.chat.messages.size)
    }
}
