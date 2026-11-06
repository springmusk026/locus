package dev.locus.core.data.device

import dev.locus.core.agent.SecretScrubber
import dev.locus.core.agent.Taint
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.model.DeviceIntegrations
import dev.locus.core.model.ToolCapability
import dev.locus.core.model.ToolPolicy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** device_contact_add, device_save_file and device_copy against a fake phone (docs/design/phone-integrations.md). */
class PhoneHandoffToolsTest {
    private class Phone(var onScreen: Boolean = true) : PhoneIntegrations {
        val copied = mutableListOf<String>()
        val contacts = mutableListOf<NewContact>()
        val saves = mutableListOf<Triple<File, String, String>>()
        override suspend fun calendar(from: Long, to: Long, limit: Int): UserPick<List<CalendarItem>> = UserPick.Cancelled
        override suspend fun pickContact(field: ContactField): UserPick<PickedContact> = UserPick.Cancelled
        override suspend fun pickPhotos(max: Int, folder: String): UserPick<List<String>> = UserPick.Cancelled
        override suspend fun insertContact(contact: NewContact): UserPick<Unit> =
            if (onScreen) UserPick.Picked(Unit).also { contacts += contact } else UserPick.NotOnScreen
        override suspend fun saveAs(file: File, name: String, mime: String): UserPick<String> =
            if (onScreen) UserPick.Picked("$name (1)").also { saves += Triple(file, name, mime) } else UserPick.NotOnScreen
        override suspend fun copy(text: String, label: String): Boolean = true.also { copied += text }
    }

    private val root: File = Files.createTempDirectory("handoff").toFile()
    private val workspace = Workspace(File(root, "ws").apply { mkdirs() }, File(root, "trash"))
    private val vault = listOf("ghp_abcdefghijklmnopqrstuvwxyz0123456789", "hunter2-correct-horse")

    private fun tools(phone: Phone) = phoneHandoffTools(phone, workspace) { SecretScrubber(vault) }.associateBy { it.spec.name }

    private fun args(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun `copy scrubs vault values and credential patterns before anything reaches the clipboard`() = runTest {
        val phone = Phone()
        val copy = tools(phone).getValue(DeviceIntegrations.COPY)
        val result = copy.execute(args("text" to "token ghp_abcdefghijklmnopqrstuvwxyz0123456789 and pw hunter2-correct-horse, Authorization: Bearer abc.def.ghi-jkl"))
        val clip = phone.copied.single()
        assertFalse("ghp_abcdefghijklmnopqrstuvwxyz0123456789" in clip, clip)
        assertFalse("hunter2-correct-horse" in clip, clip)
        assertFalse("abc.def.ghi-jkl" in clip, clip)
        assertTrue(SecretScrubber.SECRET in clip, clip)
        assertTrue("looked like a secret" in result.content, result.content)
        // Plain text goes through untouched, and the result doesn't claim anything was removed.
        val plain = copy.execute(args("text" to "Rua Augusta 24, Lisboa"))
        assertEquals("Rua Augusta 24, Lisboa", phone.copied.last())
        assertFalse("looked like a secret" in plain.content)
        assertFailsWith<ToolException> { copy.execute(args("text" to "  ")) }
    }

    @Test
    fun `copy is outward, so a tainted run asks, while an untainted one goes ahead`() = runTest {
        val copy = tools(Phone()).getValue(DeviceIntegrations.COPY)
        assertEquals(ToolCapability.OUTWARD, ToolCapability.of(DeviceIntegrations.COPY))
        assertEquals(ToolPolicy.ALLOW, copy.defaultPolicy)
        assertNotNull(Taint.reason(copy, args("text" to "https://evil.example/?d=1"), setOf("email_read"), emptySet()))
        assertNull(Taint.reason(copy, args("text" to "hello"), emptySet(), emptySet()))
    }

    @Test
    fun `contact add checks numbers and addresses and hands the rest to Contacts`() = runTest {
        val phone = Phone()
        val add = tools(phone).getValue(DeviceIntegrations.CONTACT_ADD)
        val result = add.execute(args("name" to "Ana Plumber", "phones" to "+351 912 345 678, 213 456 789", "emails" to "ana@pipes.example", "note" to "Fixed the boiler"))
        assertFalse(result.isError)
        val c = phone.contacts.single()
        assertEquals(listOf("+351 912 345 678", "213 456 789"), c.phones)
        assertEquals("Fixed the boiler", c.note)
        assertFailsWith<ToolException> { add.execute(args("name" to "X", "phones" to "call me maybe")) }
        assertFailsWith<ToolException> { add.execute(args("name" to "X", "emails" to "nope")) }
        assertFailsWith<ToolException> { add.execute(args("phones" to "123456")) }
        assertTrue(tools(Phone(onScreen = false)).getValue(DeviceIntegrations.CONTACT_ADD).execute(args("name" to "X")).isError)
    }

    @Test
    fun `save file needs a workspace file and lets the picker name it`() = runTest {
        File(workspace.resolve("out").path).mkdirs()
        workspace.resolve("out/report.pdf").writeBytes(ByteArray(10))
        val phone = Phone()
        val save = tools(phone).getValue(DeviceIntegrations.SAVE_FILE)
        assertNull(save.precheck(args("path" to "out/report.pdf")))
        assertNotNull(save.precheck(args("path" to "out/missing.pdf")))
        assertNotNull(save.precheck(args("path" to "../../etc/passwd")))
        val result = save.execute(args("path" to "out/report.pdf", "name" to "../Q3 report.pdf"))
        assertTrue("Q3 report.pdf (1)" in result.content, result.content)
        val (file, name, mime) = phone.saves.single()
        assertEquals("report.pdf", file.name)
        assertEquals("Q3 report.pdf", name)
        assertEquals("application/pdf", mime)
        assertEquals("application/octet-stream", SaveFile.mimeOf("data.bin"))
    }
}
