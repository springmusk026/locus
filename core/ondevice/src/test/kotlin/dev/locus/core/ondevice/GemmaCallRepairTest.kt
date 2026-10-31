package dev.locus.core.ondevice

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaCallRepairTest {
    @Test
    fun `repairs the stray quote seen on device`() {
        val msg = "INVALID_ARGUMENT: Failed to parse tool calls from code block: call:write_file{content:<|\"|>hi<|\"|><|\"|>,path:<|\"|>hello.txt<|\"|>}"
        val call = GemmaCallRepair.fromError(msg).single()
        assertEquals("write_file", call.name)
        assertEquals("hi", call.arguments["content"]!!.jsonPrimitive.content)
        assertEquals("hello.txt", call.arguments["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses numbers booleans nested values and several calls`() {
        val calls = GemmaCallRepair.parseAll("call:a{n:3,f:true,o:{x:<|\"|>y,z<|\"|>},l:[1,<|\"|>two<|\"|>]} call:b{}")
        assertEquals(listOf("a", "b"), calls.map { it.name })
        val a = calls[0].arguments
        assertEquals(JsonPrimitive(3L), a["n"])
        assertEquals(JsonPrimitive(true), a["f"])
        assertEquals("y,z", a["o"]!!.jsonObject["x"]!!.jsonPrimitive.content)
        assertEquals("two", a["l"]!!.jsonArray[1].jsonPrimitive.content)
    }

    @Test
    fun `a block repeated in the message gives one call`() {
        val block = "call:write_file{content:<|\"|>hi<|\"|>,path:<|\"|>a.txt<|\"|>}"
        assertEquals(1, GemmaCallRepair.fromError("Failed to parse tool calls from code block: $block\n$block").size)
    }

    @Test
    fun `other errors are not repaired`() {
        assertTrue(GemmaCallRepair.fromError("OUT_OF_MEMORY").isEmpty())
        assertTrue(GemmaCallRepair.fromError("Failed to parse tool calls from code block: call:x{broken").isEmpty())
    }
}
