package dev.locus.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SshAuthTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `stored ids map to methods`() {
        assertEquals(SshAuth.KEY, SshAuth.of("key"))
        assertEquals(SshAuth.PASSWORD, SshAuth.of("password"))
        assertEquals(SshAuth.NONE, SshAuth.of("none"))
    }

    @Test
    fun `unknown or missing ids fall back to key`() {
        assertEquals(SshAuth.KEY, SshAuth.of(null))
        assertEquals(SshAuth.KEY, SshAuth.of(""))
        assertEquals(SshAuth.KEY, SshAuth.of("NONE"))
        assertEquals(SshAuth.KEY, SshAuth.of("agent"))
    }

    @Test
    fun `hosts saved before no-auth existed still load`() {
        val old = json.decodeFromString(
            AppSettings.serializer(),
            """{"providers":[],"sshHosts":[
                {"id":"a","alias":"pw","host":"10.0.0.1","port":22,"user":"me","auth":"password","fingerprint":"SHA256:a"},
                {"id":"b","alias":"k","host":"10.0.0.2","user":"me"}
            ]}""",
        )
        val (pw, key) = old.sshHosts
        assertEquals(SshAuth.PASSWORD, pw.authMethod)
        assertEquals("SHA256:a", pw.fingerprint)
        assertEquals(SshAuth.KEY, key.authMethod)
        assertEquals(22, key.port)
    }

    @Test
    fun `no-auth host round-trips with its pinned fingerprint`() {
        val host = SshHost("h", "sandbox", "192.0.2.7", 2222, "root", auth = SshAuth.NONE.id, fingerprint = "SHA256:xyz")
        val text = json.encodeToString(SshHost.serializer(), host)
        val back = json.decodeFromString(SshHost.serializer(), text)
        assertEquals(host, back)
        assertEquals(SshAuth.NONE, back.authMethod)
        // Only the string id is stored, not the derived method.
        assertTrue("\"auth\":\"none\"" in text)
        assertFalse("authMethod" in text)
    }
}
