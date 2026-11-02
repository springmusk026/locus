package dev.locus.core.agent

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.home.ConnectorSeal
import dev.locus.core.model.IntegrityKey
import java.io.File
import java.nio.file.Files
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Connector approvals are sealed; a connector file added or changed outside Locus goes back to drafts. */
class ConnectorSealTest {
    private class Key(private val secret: ByteArray = ByteArray(32) { 3 }, var new: Boolean = false) : IntegrityKey {
        override fun mac(data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }.doFinal(data)
        override fun createdThisRun() = new
    }

    private val dir: File = Files.createTempDirectory("home").toFile()
    private val key = Key()
    private val reported = mutableListOf<String>()
    private fun home(k: IntegrityKey = key) = AgentHome(dir, connectorSeal = ConnectorSeal(k) { reported += it })

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun def(id: String, url: String = "https://api.$id.test") = Connector(id = id, kind = "mcp", name = id, description = "d", baseUrl = url)

    private fun plant(id: String, url: String = "https://evil.test") =
        File(dir, "connectors/$id.json").writeText(AgentHome.json.encodeToString(Connector.serializer(), def(id, url)))

    @Test
    fun `approved and user-added connectors stay active across restarts`() {
        home().apply {
            draftConnector(def("todo"))
            approveConnector("todo")
            addConnector(def("github"))
        }
        val again = home()
        assertEquals(listOf("github", "todo"), again.connectors().map { it.id })
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `a connector planted in the active folder goes back to drafts`() {
        home().addConnector(def("github"))
        plant("evil")
        val h = home()
        assertNull(h.connector("evil"))
        assertEquals(listOf("github"), h.connectors().map { it.id })
        assertEquals(listOf("evil"), h.connectorDrafts().map { it.id })
        assertEquals(listOf("evil"), reported)
    }

    @Test
    fun `an approved connector changed on disk goes back to drafts`() {
        home().addConnector(def("github"))
        plant("github", "https://evil.test")
        val h = home()
        assertEquals(emptyList(), h.connectors().map { it.id })
        assertEquals("https://evil.test", h.connectorDrafts().single().baseUrl)
        // The user can review and approve it again.
        h.approveConnector("github")
        assertNotNull(h.connector("github"))
    }

    @Test
    fun `a forged or deleted seal approves nothing`() {
        home().apply { addConnector(def("a")); addConnector(def("b")) }
        File(dir, "connectors/${ConnectorSeal.FILE}").writeText("""{"connectors":{"a":"00","b":"00"},"mac":"00"}""")
        assertEquals(emptyList(), home().connectors())
        assertEquals(listOf("a", "b"), reported)

        reported.clear()
        home().addConnector(def("c"))
        File(dir, "connectors/${ConnectorSeal.FILE}").delete()
        assertEquals(emptyList(), home().connectors())
        assertEquals(listOf("c"), reported)
    }

    @Test
    fun `a seal from another key approves nothing`() {
        home(Key(ByteArray(32) { 9 })).addConnector(def("a"))
        assertEquals(emptyList(), home().connectors())
        assertEquals(listOf("a"), reported)
    }

    @Test
    fun `connectors from before seals are trusted once, when the key is new`() {
        AgentHome(dir).addConnector(def("old")) // a version without seals
        assertEquals(listOf("old"), home(Key(new = true)).connectors().map { it.id })
        // Sealed now: still active with the key no longer new.
        assertEquals(listOf("old"), home().connectors().map { it.id })
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `an empty home is sealed too, so a connector planted later isn't trusted as first use`() {
        val newKey = Key(new = true)
        assertEquals(emptyList(), home(newKey).connectors())
        assertTrue(File(dir, "connectors/${ConnectorSeal.FILE}").isFile)
        plant("evil")
        assertEquals(emptyList(), home(newKey).connectors())
        assertEquals(listOf("evil"), reported)
    }

    @Test
    fun `a write after tampering doesn't seal the tampered file`() {
        home().addConnector(def("github"))
        plant("evil")
        home().removeConnector("github")
        assertEquals(emptyList(), home().connectors())
        assertEquals(listOf("evil"), home().connectorDrafts().map { it.id })
    }
}
