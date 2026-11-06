package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Connected folders are sealed with the permissions (docs/design/connected-folders.md). */
class ConnectedFolderSealTest {
    private val vault = ConnectedFolder("f1", "vault", "content://tree/vault", readOnly = true)

    @Test
    fun `switching a folder to read-write or linking it outside Locus breaks the seal`() {
        val key = TestKey()
        val sealed = PolicySeal.sealed(AppSettings(connectedFolders = listOf(vault)), key)
        assertFalse(PolicySeal.isDefault(sealed))
        assertEquals(SealCheck.VALID, PolicySeal.check(sealed, key))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(sealed.copy(connectedFolders = listOf(vault.copy(readOnly = false))), key))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(sealed.copy(connectedFolders = listOf(vault.copy(projectIds = listOf("p")))), key))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(sealed.copy(connectedFolders = listOf(vault.copy(treeUri = "content://tree/other"))), key))
        // The source label is only shown, so it isn't sealed.
        assertEquals(SealCheck.VALID, PolicySeal.check(sealed.copy(connectedFolders = listOf(vault.copy(source = "elsewhere"))), key))
    }

    @Test
    fun `the safe fallback keeps folders connected but read-only and unlinked`() {
        val open = vault.copy(readOnly = false, hiddenWritable = true, projectIds = listOf("p"))
        val restricted = PolicySeal.restricted(AppSettings(connectedFolders = listOf(open))).connectedFolders.single()
        assertTrue(restricted.readOnly)
        assertFalse(restricted.hiddenWritable)
        assertTrue(restricted.projectIds.isEmpty())
        assertEquals("content://tree/vault", restricted.treeUri)
    }
}
