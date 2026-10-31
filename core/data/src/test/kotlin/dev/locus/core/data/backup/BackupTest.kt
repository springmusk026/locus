package dev.locus.core.data.backup

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private val password = "correct horse battery staple".toCharArray()

    /** A file that passes as SQLite: the 16-byte magic and `user_version` at offset 60. */
    private fun fakeDatabase(file: File, userVersion: Int) {
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        "SQLite format 3\u0000".encodeToByteArray().copyInto(bytes)
        bytes[60] = (userVersion ushr 24).toByte(); bytes[61] = (userVersion ushr 16).toByte()
        bytes[62] = (userVersion ushr 8).toByte(); bytes[63] = userVersion.toByte()
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
    }

    private class Phone(val root: File) {
        val db = File(root, "databases/locus.db")
        val settings = File(root, "files/datastore/settings.json")
        val agentHome = File(root, "files/agent-home")
        val workspace = File(root, "files/workspace")
        val restore = PendingRestore(File(root, "no_backup/restore"), db, settings, agentHome, workspace)
    }

    private fun phone(name: String, schema: Int = 8): Phone {
        val p = Phone(tmp.newFolder(name))
        fakeDatabase(p.db, schema)
        p.settings.parentFile.mkdirs()
        p.settings.writeText("""{"customInstructions":"be brief"}""")
        File(p.agentHome, "memory").mkdirs()
        File(p.agentHome, "memory/MEMORY.md").writeText("- kotlin: likes Kotlin\n")
        File(p.agentHome, "memory/kotlin.md").apply { writeText("Prefers Kotlin.\n"); setLastModified(1_700_000_000_000) }
        File(p.agentHome, "skills/deploy").mkdirs()
        File(p.agentHome, "skills/deploy/SKILL.md").writeText("---\nname: deploy\n---\nSteps.\n")
        File(p.agentHome, "skills-drafts").mkdirs() // empty folder survives
        File(p.agentHome, "IDENTITY.md").writeText("I am Locus.\n")
        File(p.workspace, "src").mkdirs()
        File(p.workspace, "src/main.py").writeText("print('hi')\n")
        File(p.workspace, "big.bin").writeBytes(Random(1).nextBytes(3 * 1024 * 1024)) // spans several 1 MB segments
        File(p.workspace, "empty").mkdirs()
        return p
    }

    private fun sources(p: Phone, snapshot: File, options: BackupOptions = BackupOptions(), vault: Map<String, String> = mapOf("provider:a" to "sk-123")) = BackupSources(
        databaseSnapshot = snapshot,
        settingsFile = p.settings,
        agentHome = p.agentHome,
        workspace = p.workspace.takeIf { options.includeWorkspace },
        vault = vault.takeIf { options.includeVault },
    )

    private fun backup(p: Phone, options: BackupOptions = BackupOptions(), schema: Int = 8, format: Int = 1, pw: CharArray = password): ByteArray {
        val snapshot = File(tmp.newFolder(), "locus.db").also { p.db.copyTo(it) }
        val plan = BackupArchive.plan(sources(p, snapshot, options))
        val manifest = BackupManifest(format = format, appVersion = "0.1.0", appVersionCode = 1, schemaVersion = schema, createdAt = 1_761_900_000_000, sections = plan.sections)
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypt(out, pw, iterations = TEST_ITERATIONS).use { BackupArchive.write(it, plan, manifest) }
        return out.toByteArray()
    }

    private fun verify(bytes: ByteArray, staging: File, pw: CharArray = password, maxSchema: Int = 8): ExtractedBackup {
        val opened = BackupCrypto.open(ByteArrayInputStream(bytes))
        val key = BackupCrypto.unlock(opened, pw)
        return BackupArchive.extract(BackupCrypto.decrypt(opened, key), staging, maxSchema)
    }

    private fun tree(dir: File): Map<String, String> = dir.walkTopDown().filter { it != dir }.associate { f ->
        f.relativeTo(dir).invariantSeparatorsPath to if (f.isDirectory) "<dir>" else f.readBytes().contentHashCode().toString()
    }

    @Test
    fun `round trip restores the same data`() {
        val source = phone("source")
        val file = backup(source, BackupOptions(includeWorkspace = true, includeVault = true))
        val target = phone("target", schema = 8).apply {
            File(agentHome, "memory/other.md").writeText("Something else\n")
            File(workspace, "stale.txt").writeText("old")
            settings.writeText("{}")
            File(db.path + "-wal").writeText("old wal")
        }

        val extracted = verify(file, target.restore.staged)
        assertEquals(mapOf("provider:a" to "sk-123"), extracted.vault)
        assertEquals(listOf("database", "settings", "agent-home", "workspace", "vault"), extracted.manifest.sections.map { it.name })
        assertEquals(1, extracted.manifest.section("vault")!!.files)
        target.restore.markReady()
        assertTrue(target.restore.apply())

        assertContentEquals(source.db.readBytes(), target.db.readBytes())
        assertFalse(File(target.db.path + "-wal").exists())
        assertEquals(source.settings.readText(), target.settings.readText())
        assertEquals(tree(source.agentHome), tree(target.agentHome))
        assertEquals(tree(source.workspace), tree(target.workspace))
        assertEquals(1_700_000_000_000, File(target.agentHome, "memory/kotlin.md").lastModified())
        assertFalse(File(target.root, "no_backup/restore").exists())
    }

    @Test
    fun `the vault is left out by default and the workspace kept`() {
        assertFalse(BackupOptions().includeVault)
        assertFalse(BackupOptions().includeWorkspace)
        val source = phone("source")
        val file = backup(source)
        val target = phone("target").apply { File(workspace, "mine.txt").writeText("keep me") }

        val extracted = verify(file, target.restore.staged)
        assertNull(extracted.vault)
        assertFalse(extracted.manifest.includesVault)
        assertFalse(extracted.manifest.includesWorkspace)
        // The plaintext has no vault entry at all.
        val opened = BackupCrypto.open(ByteArrayInputStream(file))
        val names = ZipInputStream(BackupCrypto.decrypt(opened, BackupCrypto.unlock(opened, password))).use { zip ->
            generateSequence { zip.nextEntry?.name }.toList()
        }
        assertTrue(names.none { it.startsWith("vault") || it.startsWith("workspace") }, names.toString())

        target.restore.markReady()
        target.restore.apply()
        assertEquals("keep me", File(target.workspace, "mine.txt").readText())
    }

    @Test
    fun `a wrong password is reported as such`() {
        val file = backup(phone("source"))
        val e = assertFailsWith<BackupException> { verify(file, tmp.newFolder(), pw = "wrong password".toCharArray()) }
        assertEquals(BackupError.WrongPassword, e.error)
    }

    @Test
    fun `a password typed with decomposed accents opens the same backup`() {
        val file = backup(phone("source"), pw = "café au lait".toCharArray())
        verify(file, tmp.newFolder(), pw = "café au lait".toCharArray())
    }

    @Test
    fun `a changed byte anywhere in the ciphertext is rejected`() {
        val file = backup(phone("source"), BackupOptions(includeWorkspace = true))
        for (at in listOf(file.size / 2, file.size - 5)) {
            val tampered = file.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            val e = assertFailsWith<BackupException> { verify(tampered, tmp.newFolder()) }
            assertEquals(BackupError.Damaged, e.error, "byte $at")
        }
    }

    @Test
    fun `a cut-short file is rejected`() {
        val file = backup(phone("source"), BackupOptions(includeWorkspace = true))
        // Cut at a segment boundary too, which only the final-segment flag catches.
        for (length in listOf(file.size - 1, file.size - 100, file.size / 2)) {
            val e = assertFailsWith<BackupException> { verify(file.copyOf(length), tmp.newFolder()) }
            assertEquals(BackupError.Damaged, e.error, "length $length")
        }
    }

    @Test
    fun `a changed header is rejected`() {
        val file = backup(phone("source"))
        // Re-encode the header with an extra space and a correct length: it parses to the same
        // values and the password check still passes, so only the associated data can catch it.
        val length = java.nio.ByteBuffer.wrap(file, 8, 4).int
        val header = file.copyOfRange(12, 12 + length).decodeToString()
        val changed = header.replaceFirst("{", "{ ").encodeToByteArray()
        val tampered = file.copyOfRange(0, 8) + java.nio.ByteBuffer.allocate(4).putInt(changed.size).array() + changed + file.copyOfRange(12 + length, file.size)
        assertEquals(BackupError.Damaged, assertFailsWith<BackupException> { verify(tampered, tmp.newFolder()) }.error)
        // And something that isn't a backup at all.
        assertEquals(BackupError.NotABackup, assertFailsWith<BackupException> { BackupCrypto.open(ByteArrayInputStream("PK\u0003\u0004".toByteArray())) }.error)
    }

    @Test
    fun `a newer database is refused and an older one accepted`() {
        val newer = backup(phone("newer", schema = 9), schema = 9)
        assertEquals(BackupError.NewerSchema, assertFailsWith<BackupException> { verify(newer, tmp.newFolder(), maxSchema = 8) }.error)

        val older = backup(phone("older", schema = 6), schema = 6)
        assertEquals(6, verify(older, tmp.newFolder(), maxSchema = 8).manifest.schemaVersion)
    }

    @Test
    fun `a newer file format is refused`() {
        val file = backup(phone("source"), format = 2)
        assertEquals(BackupError.NewerFormat, assertFailsWith<BackupException> { verify(file, tmp.newFolder()) }.error)
    }

    @Test
    fun `the manifest must match the database inside`() {
        val file = backup(phone("source", schema = 7), schema = 8)
        assertEquals(BackupError.Damaged, assertFailsWith<BackupException> { verify(file, tmp.newFolder()) }.error)
    }

    @Test
    fun `version checks`() {
        fun manifest(schema: Int, format: Int = 1) = BackupManifest(format = format, appVersion = "x", appVersionCode = 1, schemaVersion = schema, createdAt = 0, sections = emptyList())
        BackupArchive.checkVersions(manifest(8), maxSchema = 8)
        BackupArchive.checkVersions(manifest(1), maxSchema = 8)
        assertEquals(BackupError.NewerSchema, assertFailsWith<BackupException> { BackupArchive.checkVersions(manifest(9), 8) }.error)
        assertEquals(BackupError.NewerFormat, assertFailsWith<BackupException> { BackupArchive.checkVersions(manifest(8, format = 2), 8) }.error)
        assertEquals(BackupError.Damaged, assertFailsWith<BackupException> { BackupArchive.checkVersions(manifest(0), 8) }.error)
    }

    @Test
    fun `unsafe entry names are refused`() {
        listOf("../x", "workspace/../../x", "/etc/passwd", "workspace\\..\\x", "workspace//x", "workspace/./x", "").forEach {
            assertFalse(BackupArchive.isSafe(it), it)
        }
        listOf("workspace/a/b.txt", "agent-home/memory/", "database/locus.db", "workspace/.hidden").forEach {
            assertTrue(BackupArchive.isSafe(it), it)
        }
    }

    @Test
    fun `an unconfirmed stage is discarded, not applied`() {
        val source = phone("source")
        val target = phone("target").apply { settings.writeText("mine") }
        verify(backup(source), target.restore.staged)
        assertFalse(target.restore.apply())
        assertEquals("mine", target.settings.readText())
        assertFalse(target.restore.staged.exists())
    }

    @Test
    fun `password encoding is plain hex of NFC UTF-8`() {
        assertEquals("616263", String(BackupCrypto.encodePassword("abc".toCharArray())))
        assertEquals("c3a9", String(BackupCrypto.encodePassword("é".toCharArray())))
    }

    @Test
    fun `each backup gets its own salt`() {
        val p = phone("source")
        val a = BackupCrypto.open(ByteArrayInputStream(backup(p))).header
        val b = BackupCrypto.open(ByteArrayInputStream(backup(p))).header
        assertTrue(a.salt != b.salt)
        assertEquals(TEST_ITERATIONS, a.iterations)
        assertEquals(BackupCrypto.ITERATIONS, BackupCrypto.Header(salt = "", check = "").iterations)
    }

    private companion object {
        // The real count is 600,000; tests use the reader's floor to stay fast.
        const val TEST_ITERATIONS = 10_000
    }
}
