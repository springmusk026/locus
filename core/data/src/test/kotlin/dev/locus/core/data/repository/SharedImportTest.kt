package dev.locus.core.data.repository

import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SharedImportTest {
    private fun refusal(scheme: String?, authority: String?, own: String = "dev.locus") = SharedImport.refusal(scheme, authority, own)

    @Test
    fun `another app's content URI is accepted`() {
        assertNull(refusal("content", "com.google.android.apps.photos.contentprovider"))
        assertNull(refusal("CONTENT", "com.android.providers.downloads.documents"))
    }

    @Test
    fun `file URIs and other schemes are refused`() {
        assertNotNull(refusal("file", null))
        assertNotNull(refusal("file", ""))
        assertNotNull(refusal("http", "example.com"))
        assertNotNull(refusal(null, null))
    }

    @Test
    fun `a content URI without an authority is refused`() {
        assertNotNull(refusal("content", null))
        assertNotNull(refusal("content", " "))
    }

    @Test
    fun `Locus's own providers are refused, in any build`() {
        assertNotNull(refusal("content", "dev.locus.workspace"))
        assertNotNull(refusal("content", "dev.locus.exports"))
        assertNotNull(refusal("content", "DEV.LOCUS.Workspace"))
        assertNotNull(refusal("content", "0@dev.locus.workspace"))
        assertNotNull(refusal("content", "dev.locus"))
        assertNotNull(refusal("content", "other.app;dev.locus.exports"))
        assertNotNull(refusal("content", "dev.locus.debug.workspace", own = "dev.locus.debug"))
    }

    @Test
    fun `an app whose name only starts like Locus's is not Locus`() {
        assertNull(refusal("content", "dev.locusfake.provider"))
    }

    @Test
    fun `copies past the size cap fail`() {
        SharedImport.checkSize(SharedImport.MAX_BYTES)
        assertFailsWith<SharedImportException> { SharedImport.checkSize(SharedImport.MAX_BYTES + 1) }
        assertFailsWith<SharedImportException> { SharedImport.checkSize(11, max = 10) }
    }
}
