package dev.locus.core.terminal

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabRegistryTest {
    private class FakeSession(val id: Int) { var stopped = false }

    private val registry = TabRegistry<FakeSession>(maxTabs = 3)

    @Test
    fun `a new tab is selected and numbered from one`() {
        val a = registry.add(::FakeSession)!!
        val b = registry.add(::FakeSession)!!
        assertEquals(b, registry.selected.value)
        assertEquals(listOf("Shell 1", "Shell 2"), registry.tabs.value.map { it.label })
        assertEquals(a, registry.session(a)!!.id)
    }

    @Test
    fun `no session is created past the limit`() {
        repeat(3) { registry.add(::FakeSession) }
        var created = false
        assertNull(registry.add { created = true; FakeSession(it) })
        assertFalse(created)
        assertFalse(registry.canOpen)
    }

    @Test
    fun `closing the selected tab selects its left neighbour, or the next one`() {
        val a = registry.add(::FakeSession)!!
        val b = registry.add(::FakeSession)!!
        val c = registry.add(::FakeSession)!!
        registry.select(b)
        assertEquals(b, registry.remove(b)!!.id)
        assertEquals(a, registry.selected.value)
        registry.remove(a)
        assertEquals(c, registry.selected.value)
        registry.remove(c)
        assertNull(registry.selected.value)
        assertTrue(registry.tabs.value.isEmpty())
    }

    @Test
    fun `closing another tab keeps the selection`() {
        val a = registry.add(::FakeSession)!!
        val b = registry.add(::FakeSession)!!
        registry.remove(a)
        assertEquals(b, registry.selected.value)
    }

    @Test
    fun `a closed tab's number is reused`() {
        val a = registry.add(::FakeSession)!!
        registry.add(::FakeSession)
        registry.remove(a)
        registry.add(::FakeSession)
        assertEquals(listOf(2, 1), registry.tabs.value.map { it.number })
    }

    @Test
    fun `the shell's title replaces the label until it's cleared`() {
        val a = registry.add(::FakeSession)!!
        registry.retitle(a, "  vim notes.md ")
        assertEquals("vim notes.md", registry.tabs.value.single().label)
        registry.retitle(a, " ")
        assertEquals("Shell 1", registry.tabs.value.single().label)
    }

    @Test
    fun `a finished shell keeps its tab with the exit status`() {
        val a = registry.add(::FakeSession)!!
        assertTrue(registry.tabs.value.single().running)
        registry.finished(a, 130)
        val tab = registry.tabs.value.single()
        assertFalse(tab.running)
        assertEquals(130, tab.exitStatus)
        assertEquals(a, registry.session(a)!!.id)
    }

    @Test
    fun `selecting an unknown tab does nothing`() {
        val a = registry.add(::FakeSession)!!
        registry.select(99)
        assertEquals(a, registry.selected.value)
    }

    @Test
    fun `removeAll hands back every session`() {
        repeat(3) { registry.add(::FakeSession) }
        val all = registry.removeAll()
        assertEquals(3, all.size)
        assertTrue(registry.tabs.value.isEmpty())
        assertNull(registry.selected.value)
        assertTrue(registry.canOpen)
        assertNull(registry.remove(all.first().id))
    }
}
