package dev.locus.core.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageTreeTest {
    private fun tree(vararg nodes: Pair<String, String?>) = MessageTree(nodes.map { (id, parent) -> TreeNode(id, parent) })

    /**
     * u1 ─ a1 ─ u2 ─ a2          (the original)
     *         └ u2' ─ a2'        (u2 edited)
     *    └ a1'                   (a1 regenerated)
     */
    private val branched = tree(
        "u1" to null, "a1" to "u1", "u2" to "a1", "a2" to "u2",
        "u2e" to "a1", "a2e" to "u2e",
        "a1r" to "u1",
    )

    @Test
    fun `a migrated linear chat is one branch shown in full`() {
        // As the v7 backfill leaves it: each message's parent is the previous one, no active leaf.
        val t = tree("m1" to null, "m2" to "m1", "m3" to "m2", "m4" to "m3")
        assertEquals("m4", t.leaf(null))
        assertEquals(listOf("m1", "m2", "m3", "m4"), t.path(t.leaf(null)))
        assertTrue(t.branches(t.path("m4")).isEmpty())
    }

    @Test
    fun `the active leaf picks the path, and an unknown leaf falls back to the newest message`() {
        assertEquals(listOf("u1", "a1", "u2", "a2"), branched.path(branched.leaf("a2")))
        assertEquals(listOf("u1", "a1", "u2e", "a2e"), branched.path(branched.leaf("a2e")))
        assertEquals(listOf("u1", "a1r"), branched.path(branched.leaf("gone")))
        // An inner message can be the leaf: a regenerate shows the chat up to the user message while it runs.
        assertEquals(listOf("u1"), branched.path("u1"))
        assertTrue(tree().path(tree().leaf(null)).isEmpty())
    }

    @Test
    fun `siblings are counted per parent, oldest first`() {
        assertEquals(listOf("a1", "a1r"), branched.siblings("a1r"))
        assertEquals(listOf("u2", "u2e"), branched.siblings("u2"))
        assertEquals(listOf("u1"), branched.siblings("u1"))

        val shown = branched.branches(branched.path("a2e"))
        assertEquals(setOf("a1", "u2e"), shown.keys)
        assertEquals(Branch(1, listOf("u2", "u2e")), shown["u2e"])
        assertEquals("u2", shown.getValue("u2e").previous)
        assertNull(shown.getValue("u2e").next)
        assertEquals(0, shown.getValue("a1").index)
        assertEquals(2, shown.getValue("a1").count)
    }

    @Test
    fun `switching to a sibling lands on the newest message under it`() {
        assertEquals("a2e", branched.newestUnder("a1"))
        assertEquals("a2", branched.newestUnder("u2"))
        assertEquals("a1r", branched.newestUnder("a1r"))
        // After continuing the original branch, going back to it returns to its newest message.
        val continued = tree("u1" to null, "a1" to "u1", "a1r" to "u1", "u2" to "a1")
        assertEquals("u2", continued.newestUnder("a1"))
    }

    @Test
    fun `a missing parent or a cycle never hides or loops`() {
        val damaged = tree("x" to "nowhere", "y" to "x", "self" to "self")
        assertEquals(listOf("x", "y"), damaged.path("y"))
        assertEquals(listOf("self"), damaged.path("self"))
        val cycle = tree("p" to "q", "q" to "p")
        assertEquals(2, cycle.path("p").size)
        assertEquals("q", cycle.newestUnder("p"))
    }

    @Test
    fun `a fork copies the path up to the chosen message with new ids`() {
        val path = listOf("u1", "a1", "t1", "a2", "u2").map { Message(it, "c", Role.USER, listOf(Part.Text(it)), 7, rawPayload = "raw-$it") }
        var n = 0
        val inclusive = MessageTree.forkCopies(path, "a2", inclusive = true, conversationId = "fork") { "n${n++}" }
        assertEquals(listOf("u1", "a1", "t1", "a2"), inclusive.map { it.text })
        assertEquals(listOf("n0", "n1", "n2", "n3"), inclusive.map { it.id })
        assertTrue(inclusive.all { it.conversationId == "fork" && it.createdAt == 7L && it.rawPayload == "raw-${it.text}" })

        val before = MessageTree.forkCopies(path, "u2", inclusive = false, conversationId = "fork") { "x" }
        assertEquals(listOf("u1", "a1", "t1", "a2"), before.map { it.text })
        assertTrue(MessageTree.forkCopies(path, "elsewhere", inclusive = true, conversationId = "fork") { "x" }.isEmpty())
    }
}
