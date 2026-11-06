package dev.locus.core.browser

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC 6238 Appendix B (https://www.rfc-editor.org/rfc/rfc6238), and the forms of key users paste. */
class TotpTest {
    private val sha1Seed = "12345678901234567890".toByteArray()
    private val sha256Seed = "12345678901234567890123456789012".toByteArray()
    private val sha512Seed = "1234567890123456789012345678901234567890123456789012345678901234".toByteArray()

    /** Time, then the TOTP for SHA1, SHA256 and SHA512, 8 digits, X = 30, T0 = 0. */
    private val vectors = listOf(
        Triple(59L, "94287082", "46119246" to "90693936"),
        Triple(1111111109L, "07081804", "68084774" to "25091201"),
        Triple(1111111111L, "14050471", "67062674" to "99943326"),
        Triple(1234567890L, "89005924", "91819424" to "93441116"),
        Triple(2000000000L, "69279037", "90698825" to "38618901"),
        Triple(20000000000L, "65353130", "77737706" to "47863826"),
    )

    @Test
    fun `matches every RFC 6238 test vector`() {
        vectors.forEach { (time, sha1, rest) ->
            assertEquals(sha1, Totp.code(sha1Seed, time, digits = 8, algorithm = "HmacSHA1"), "SHA1 at $time")
            assertEquals(rest.first, Totp.code(sha256Seed, time, digits = 8, algorithm = "HmacSHA256"), "SHA256 at $time")
            assertEquals(rest.second, Totp.code(sha512Seed, time, digits = 8, algorithm = "HmacSHA512"), "SHA512 at $time")
        }
    }

    @Test
    fun `six digits are the last six of the eight-digit code`() {
        assertEquals("287082", Totp.code(sha1Seed, 59))
        assertEquals("081804", Totp.code(sha1Seed, 1111111109))
    }

    @Test
    fun `a base32 key and an otpauth link read the same seed`() {
        // "12345678901234567890" in RFC 4648 base32.
        val base32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        assertEquals("287082", Totp.codeFor(base32, 59))
        assertEquals("287082", Totp.codeFor("gezd gnbv gy3t qojq gezd gnbv gy3t qojq", 59), "spaces and lower case, as apps show it")
        assertEquals("94287082", Totp.codeFor("otpauth://totp/Example:alice?secret=$base32&issuer=Example&digits=8", 59))
        assertEquals(Totp.code(sha1Seed, 59), Totp.codeFor("otpauth://totp/x?secret=$base32&algorithm=SHA1&period=30", 59))
    }

    @Test
    fun `unreadable keys are refused, never guessed`() {
        assertFalse(Totp.valid("not a key!"))
        assertFalse(Totp.valid("ABC"), "fewer than 10 bytes")
        assertFalse(Totp.valid("otpauth://hotp/x?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"), "HOTP is counter-based, not supported")
        assertFalse(Totp.valid("otpauth://totp/x?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&algorithm=MD5"))
        assertNull(Totp.codeFor("", 59))
        assertTrue(Totp.valid("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"))
    }

    @Test
    fun `codes around now cover the previous, current and next step`() {
        val key = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        val around = Totp.codesAround(key, 1111111111)
        assertEquals(listOf(Totp.codeFor(key, 1111111081), Totp.codeFor(key, 1111111111), Totp.codeFor(key, 1111111141)), around)
    }
}
