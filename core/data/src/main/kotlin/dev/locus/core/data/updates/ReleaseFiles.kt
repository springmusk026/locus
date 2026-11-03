package dev.locus.core.data.updates

import java.io.File
import java.security.MessageDigest

/** Which of a release's files is for this phone, and what its SHA-256 must be. */
object ReleaseFiles {
    /** The checksum file the release workflow attaches (.github/workflows/release.yml). */
    const val CHECKSUMS = "SHA256SUMS.txt"

    /**
     * The APK for the first of [supportedAbis] (Build.SUPPORTED_ABIS, "the most preferred ABI is the
     * first element") that the release has: `locus-v<version>-<abi>.apk`. Null when none matches, for
     * example a 32-bit-only phone.
     */
    fun apkFor(release: Release, supportedAbis: List<String>): ReleaseAsset? {
        val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        for (abi in supportedAbis) {
            apks.firstOrNull { it.name.equals("locus-${release.tag}-$abi.apk", ignoreCase = true) }?.let { return it }
            apks.firstOrNull { it.name.endsWith("-$abi.apk", ignoreCase = true) }?.let { return it }
        }
        return null
    }

    /**
     * File name to lower-case hex SHA-256, from `sha256sum` output: "<64 hex>  <name>", or
     * "<64 hex> *<name>" in binary mode. Lines that aren't that are skipped.
     */
    fun parseChecksums(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
        val m = SUM_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
        m.groupValues[2].trim() to m.groupValues[1].lowercase()
    }.toMap()

    /** The hex SHA-256 in a GitHub asset digest ("sha256:<hex>"), or null for none or another algorithm. */
    fun digestHex(digest: String?): String? {
        val d = digest?.trim() ?: return null
        if (!d.startsWith("sha256:", ignoreCase = true)) return null
        return d.substringAfter(':').lowercase().takeIf { HEX64.matches(it) }
    }

    /**
     * What [asset]'s SHA-256 must be: the release's checksum file when it lists the asset, else
     * GitHub's own digest. When both exist they must agree, or nothing can be trusted ([Expected.Conflict]).
     */
    fun expected(asset: ReleaseAsset, sums: Map<String, String>?): Expected {
        val listed = sums?.get(asset.name)
        val github = digestHex(asset.digest)
        return when {
            listed != null && github != null && listed != github -> Expected.Conflict
            listed != null -> Expected.Sha256(listed)
            github != null -> Expected.Sha256(github)
            else -> Expected.None
        }
    }

    sealed interface Expected {
        data class Sha256(val hex: String) : Expected
        data object None : Expected
        data object Conflict : Expected
    }

    /** Whether [actualHex] is [expectedHex], compared in constant time. */
    fun matches(actualHex: String, expectedHex: String): Boolean =
        MessageDigest.isEqual(actualHex.lowercase().toByteArray(), expectedHex.lowercase().toByteArray())

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private val HEX64 = Regex("[0-9a-f]{64}")
    private val SUM_LINE = Regex("([0-9a-fA-F]{64})\\s+\\*?(.+)")
}

/**
 * What Android would decide about installing an APK over the app, checked before handing it over so
 * the user gets a clear reason instead of the installer's "App not installed".
 */
object ApkCheck {
    /** An APK's or the installed app's identity: package, version code, signing certificates (SHA-256 hex). */
    data class Facts(val packageName: String, val versionCode: Long, val signers: Set<String>, val signingHistory: Set<String> = signers)

    enum class Verdict { OK, WRONG_PACKAGE, NOT_NEWER, DIFFERENT_KEY, UNREADABLE }

    /**
     * Android installs an update only when "the certificates match" (https://developer.android.com/studio/publish/app-signing):
     * the same signers, or a rotated key whose history includes the installed one.
     */
    fun verdict(installed: Facts, candidate: Facts?): Verdict = when {
        candidate == null || candidate.signers.isEmpty() -> Verdict.UNREADABLE
        candidate.packageName != installed.packageName -> Verdict.WRONG_PACKAGE
        !sameKey(installed, candidate) -> Verdict.DIFFERENT_KEY
        candidate.versionCode <= installed.versionCode -> Verdict.NOT_NEWER
        else -> Verdict.OK
    }

    private fun sameKey(installed: Facts, candidate: Facts): Boolean =
        installed.signers.isNotEmpty() && (candidate.signers == installed.signers || candidate.signingHistory.containsAll(installed.signers))
}
