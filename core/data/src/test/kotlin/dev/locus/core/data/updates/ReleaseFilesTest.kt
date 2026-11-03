package dev.locus.core.data.updates

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Versions, the release JSON, which APK is this phone's, checksums and the install pre-check. */
class ReleaseFilesTest {
    private val fixture = javaClass.getResource("/updates/latest-release.json")!!.readText()
    private val release = ReleaseJson.parse(fixture)!!

    // ---- Versions ----

    @Test fun `a higher number in any part is newer`() {
        assertTrue(Versions.isNewer("0.1.5", "0.1.4"))
        assertTrue(Versions.isNewer("0.2.0", "0.1.9"))
        assertTrue(Versions.isNewer("1.0.0", "0.99.99"))
        assertTrue(Versions.isNewer("0.1.10", "0.1.9")) // numbers, not strings
        assertFalse(Versions.isNewer("0.1.4", "0.1.4"))
        assertFalse(Versions.isNewer("0.1.3", "0.1.4"))
    }

    @Test fun `a v prefix, a missing part and build metadata don't matter`() {
        assertFalse(Versions.isNewer("v0.1.4", "0.1.4"))
        assertFalse(Versions.isNewer("0.2", "0.2.0"))
        assertFalse(Versions.isNewer("0.1.4+abc", "0.1.4"))
    }

    @Test fun `a pre-release sorts below its release and is ordered by its identifiers`() {
        assertTrue(Versions.isNewer("0.2.0", "0.2.0-beta.1"))
        assertFalse(Versions.isNewer("0.2.0-beta.1", "0.2.0"))
        assertTrue(Versions.isNewer("0.2.0-beta.2", "0.2.0-beta.1"))
        assertTrue(Versions.isNewer("0.2.0-beta.10", "0.2.0-beta.2"))
        assertTrue(Versions.isNewer("0.2.0-rc.1", "0.2.0-beta.9"))
        assertTrue(Versions.isNewer("0.2.0-beta.1", "0.1.9"))
    }

    @Test fun `unreadable names are never newer`() {
        assertFalse(Versions.isNewer("nightly", "0.1.4"))
        assertFalse(Versions.isNewer("0.1.5", "unknown"))
        assertFalse(Versions.isNewer("", "0.1.4"))
        assertNull(Versions.compare("x.y", "0.1"))
    }

    // ---- Release JSON ----

    @Test fun `the fixture parses with the fields the check uses`() {
        assertEquals("v0.1.5", release.tag)
        assertEquals("0.1.5", release.version)
        assertEquals("Locus v0.1.5", release.name)
        assertFalse(release.draft)
        assertFalse(release.prerelease)
        assertEquals("https://github.com/springmusk026/locus/releases/tag/v0.1.5", release.htmlUrl)
        assertTrue(release.body!!.startsWith("**Install:**"))
        assertEquals(4, release.assets.size)
        val arm = release.assets.first()
        assertEquals("locus-v0.1.5-arm64-v8a.apk", arm.name)
        assertEquals(17053000, arm.size)
        assertEquals("sha256:" + "a".repeat(64), arm.digest)
        assertNull(release.assets[1].digest)
    }

    @Test fun `it survives a round trip and rejects what isn't a release`() {
        assertEquals(release, ReleaseJson.parse(ReleaseJson.encode(release)))
        assertNull(ReleaseJson.parse("not json"))
        assertNull(ReleaseJson.parse("""{"message":"Not Found"}"""))
        assertNull(ReleaseJson.parse("""{"tag_name":""}"""))
        assertNull(ReleaseJson.parse("[]"))
    }

    // ---- ABI ----

    @Test fun `the phone's most preferred ABI that the release has wins`() {
        assertEquals("locus-v0.1.5-arm64-v8a.apk", ReleaseFiles.apkFor(release, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))?.name)
        assertEquals("locus-v0.1.5-x86_64.apk", ReleaseFiles.apkFor(release, listOf("x86_64", "x86", "arm64-v8a"))?.name)
        // An x86 emulator that also translates ARM: no x86 APK, so the ARM one.
        assertEquals("locus-v0.1.5-arm64-v8a.apk", ReleaseFiles.apkFor(release, listOf("x86", "arm64-v8a"))?.name)
    }

    @Test fun `a 32-bit-only phone gets nothing, and the mapping is never picked`() {
        assertNull(ReleaseFiles.apkFor(release, listOf("armeabi-v7a", "armeabi")))
        assertNull(ReleaseFiles.apkFor(release, emptyList()))
        val noApks = release.copy(assets = release.assets.filterNot { it.name.endsWith(".apk") })
        assertNull(ReleaseFiles.apkFor(noApks, listOf("arm64-v8a")))
    }

    // ---- Checksums ----

    private val sums = """
        ${"1".repeat(64)}  locus-v0.1.5-arm64-v8a.apk
        ${"B".repeat(64)} *locus-v0.1.5-x86_64.apk
        ${"3".repeat(64)}  locus-v0.1.5-mapping.txt
        not a checksum line
    """.trimIndent()

    @Test fun `sha256sum output parses, binary marker and upper case included`() {
        val parsed = ReleaseFiles.parseChecksums(sums)
        assertEquals("1".repeat(64), parsed["locus-v0.1.5-arm64-v8a.apk"])
        assertEquals("b".repeat(64), parsed["locus-v0.1.5-x86_64.apk"])
        assertEquals(3, parsed.size)
    }

    @Test fun `the checksum file is preferred, GitHub's digest is the fallback, and a disagreement trusts neither`() {
        val parsed = ReleaseFiles.parseChecksums(sums)
        val x86 = release.assets[1] // no digest
        assertEquals(ReleaseFiles.Expected.Sha256("b".repeat(64)), ReleaseFiles.expected(x86, parsed))
        val arm = release.assets[0] // digest a…a, listed as 1…1
        assertEquals(ReleaseFiles.Expected.Conflict, ReleaseFiles.expected(arm, parsed))
        assertEquals(ReleaseFiles.Expected.Sha256("a".repeat(64)), ReleaseFiles.expected(arm, null))
        assertEquals(ReleaseFiles.Expected.None, ReleaseFiles.expected(x86, null))
        assertEquals(ReleaseFiles.Expected.None, ReleaseFiles.expected(x86.copy(digest = "md5:abc"), emptyMap()))
    }

    @Test fun `a file's SHA-256 is checked against the expected one`() {
        val f = File(Files.createTempDirectory("sums").toFile(), "x.apk").apply { writeText("abc") }
        val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(abc, ReleaseFiles.sha256(f))
        assertTrue(ReleaseFiles.matches(ReleaseFiles.sha256(f), abc.uppercase()))
        assertFalse(ReleaseFiles.matches(ReleaseFiles.sha256(f), "0".repeat(64)))
        assertNotNull(ReleaseFiles.digestHex("sha256:$abc"))
        assertNull(ReleaseFiles.digestHex("sha256:short"))
    }

    // ---- Install pre-check ----

    private val installed = ApkCheck.Facts("dev.locus", 53, setOf("key"))

    @Test fun `a newer Locus signed with the same key passes`() {
        assertEquals(ApkCheck.Verdict.OK, ApkCheck.verdict(installed, ApkCheck.Facts("dev.locus", 63, setOf("key"))))
    }

    @Test fun `another key, package or an older code is refused before Android sees it`() {
        assertEquals(ApkCheck.Verdict.DIFFERENT_KEY, ApkCheck.verdict(installed, ApkCheck.Facts("dev.locus", 63, setOf("other"))))
        assertEquals(ApkCheck.Verdict.WRONG_PACKAGE, ApkCheck.verdict(installed, ApkCheck.Facts("dev.evil", 63, setOf("key"))))
        assertEquals(ApkCheck.Verdict.NOT_NEWER, ApkCheck.verdict(installed, ApkCheck.Facts("dev.locus", 53, setOf("key"))))
        assertEquals(ApkCheck.Verdict.UNREADABLE, ApkCheck.verdict(installed, null))
        assertEquals(ApkCheck.Verdict.UNREADABLE, ApkCheck.verdict(installed, ApkCheck.Facts("dev.locus", 63, emptySet())))
    }

    @Test fun `a rotated key whose history includes the installed one passes`() {
        val rotated = ApkCheck.Facts("dev.locus", 63, setOf("new"), signingHistory = setOf("key", "new"))
        assertEquals(ApkCheck.Verdict.OK, ApkCheck.verdict(installed, rotated))
    }

    @Test fun `stores that update Locus themselves are recognised, sideloads aren't`() {
        assertEquals("F-Droid", StoreInstallers.nameOf("org.fdroid.fdroid"))
        assertEquals("Obtainium", StoreInstallers.nameOf("dev.imranr.obtainium"))
        assertNull(StoreInstallers.nameOf("com.google.android.packageinstaller"))
        assertNull(StoreInstallers.nameOf(null))
    }
}
