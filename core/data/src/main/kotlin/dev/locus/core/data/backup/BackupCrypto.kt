package dev.locus.core.data.backup

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import com.google.crypto.tink.util.SecretBytes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encryption layer of a `.locusbackup` file (format in docs/design/backup.md):
 *
 * ```
 * "LOCUSBAK"            8 bytes, magic
 * header length         4 bytes, big-endian
 * header                UTF-8 JSON: format, KDF, iterations, salt, cipher, password check
 * ciphertext            Tink Streaming AEAD (AES256_GCM_HKDF_1MB) of the zip;
 *                       associated data = every byte before it (magic, length, header)
 * ```
 *
 * Tink has no password-based key derivation, so the key comes from PBKDF2-HMAC-SHA256 with a random
 * 128-bit salt and 600,000 iterations (OWASP's current figure; NIST SP 800-132 asks for a salt of at
 * least 128 bits). Streaming AEAD authenticates every 1 MB segment and its position, so a changed,
 * reordered or cut-short file fails to decrypt.
 */
object BackupCrypto {
    const val FORMAT = 1
    const val KDF = "PBKDF2-HMAC-SHA256"
    const val CIPHER = "AES256_GCM_HKDF_1MB"
    const val ITERATIONS = 600_000

    /** Readers accept this range: low enough for tests, high enough that a file can't stall the phone. */
    internal const val MIN_ITERATIONS = 10_000
    internal const val MAX_ITERATIONS = 10_000_000

    private val MAGIC = "LOCUSBAK".encodeToByteArray()
    private const val SALT_BYTES = 16
    private const val KEY_BYTES = 32
    private const val SEGMENT_BYTES = 1 shl 20
    private const val MAX_HEADER_BYTES = 16 * 1024
    private val STREAM_KEY_INFO = "locus-backup v1 stream key".encodeToByteArray()
    private val CHECK_INFO = "locus-backup v1 password check".encodeToByteArray()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Header(
        val format: Int = FORMAT,
        val kdf: String = KDF,
        val iterations: Int = ITERATIONS,
        /** Base64 salt. */
        val salt: String,
        val cipher: String = CIPHER,
        /** Base64 HMAC of a fixed label under the derived key: tells a wrong password from a damaged file. */
        val check: String,
    )

    /** The header read from the front of a file, plus the exact bytes that are authenticated. */
    class Opened internal constructor(val header: Header, internal val associatedData: ByteArray, internal val rest: InputStream)

    /** A key derived from the password; derive once per file, it's deliberately slow. */
    class Key internal constructor(internal val master: ByteArray)

    /** Starts a new file on [out]: writes the header and returns the stream to write the plaintext to. */
    fun encrypt(out: OutputStream, password: CharArray, iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()): OutputStream {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val key = deriveKey(password, salt, iterations)
        val header = Header(iterations = iterations, salt = b64(salt), check = b64(hmac(key.master, CHECK_INFO)))
        val headerBytes = json.encodeToString(Header.serializer(), header).encodeToByteArray()
        val ad = MAGIC + int32(headerBytes.size) + headerBytes
        out.write(ad)
        return streamingAead(key).newEncryptingStream(out, ad)
    }

    /** Reads and checks the header only; no password needed. */
    fun open(input: InputStream): Opened {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try {
            data.readFully(magic)
        } catch (e: IOException) {
            throw BackupException(BackupError.NotABackup, e)
        }
        if (!magic.contentEquals(MAGIC)) throw BackupException(BackupError.NotABackup)
        val length = try { data.readInt() } catch (e: IOException) { throw BackupException(BackupError.Damaged, e) }
        if (length !in 2..MAX_HEADER_BYTES) throw BackupException(BackupError.Damaged)
        val headerBytes = ByteArray(length)
        try {
            data.readFully(headerBytes)
        } catch (e: IOException) {
            throw BackupException(BackupError.Damaged, e)
        }
        val header = try {
            json.decodeFromString(Header.serializer(), headerBytes.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupError.Damaged, e)
        }
        if (header.format > FORMAT) throw BackupException(BackupError.NewerFormat)
        if (header.format < 1 || header.kdf != KDF || header.cipher != CIPHER || header.iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            throw BackupException(BackupError.Damaged)
        }
        return Opened(header, MAGIC + int32(length) + headerBytes, input)
    }

    /** Derives the key and checks it against the header. Throws [BackupError.WrongPassword]. */
    fun unlock(opened: Opened, password: CharArray): Key {
        val salt = unb64(opened.header.salt) ?: throw BackupException(BackupError.Damaged)
        val expected = unb64(opened.header.check) ?: throw BackupException(BackupError.Damaged)
        if (salt.size < SALT_BYTES) throw BackupException(BackupError.Damaged)
        val key = deriveKey(password, salt, opened.header.iterations)
        if (!MessageDigest.isEqual(expected, hmac(key.master, CHECK_INFO))) throw BackupException(BackupError.WrongPassword)
        return key
    }

    /**
     * The plaintext of an unlocked file. Reading it throws an [IOException] as soon as a segment
     * fails to authenticate, and at the end if the file was cut short, so callers must read it to
     * the end before trusting anything they got from it.
     */
    fun decrypt(opened: Opened, key: Key): InputStream = streamingAead(key).newDecryptingStream(opened.rest, opened.associatedData)

    internal fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): Key {
        val spec = PBEKeySpec(encodePassword(password), salt, iterations, KEY_BYTES * 8)
        try {
            return Key(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded)
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * The password as PBKDF2 sees it. Providers disagree on how they turn `char`s into bytes (UTF-8
     * on the JVM and Conscrypt, the low 8 bits in some older Android providers), so the password is
     * normalised (NFC) and UTF-8 encoded here, then given to PBKDF2 as lowercase hex, which every
     * provider encodes the same way. A backup made on one phone then opens on any other.
     */
    internal fun encodePassword(password: CharArray): CharArray {
        val utf8 = Normalizer.normalize(String(password), Normalizer.Form.NFC).encodeToByteArray()
        val hex = CharArray(utf8.size * 2)
        utf8.forEachIndexed { i, b ->
            val v = b.toInt() and 0xff
            hex[i * 2] = HEX[v ushr 4]
            hex[i * 2 + 1] = HEX[v and 0x0f]
        }
        utf8.fill(0)
        return hex
    }

    private fun streamingAead(key: Key): StreamingAead {
        val parameters = AesGcmHkdfStreamingParameters.builder()
            .setKeySizeBytes(KEY_BYTES)
            .setDerivedAesGcmKeySizeBytes(KEY_BYTES)
            .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
            .setCiphertextSegmentSizeBytes(SEGMENT_BYTES)
            .build()
        val ikm = hmac(key.master, STREAM_KEY_INFO)
        return AesGcmHkdfStreaming.create(AesGcmHkdfStreamingKey.create(parameters, SecretBytes.copyFrom(ikm, InsecureSecretKeyAccess.get())))
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(text: String) = runCatching { Base64.getDecoder().decode(text) }.getOrNull()
    private val HEX = "0123456789abcdef".toCharArray()
}

enum class BackupError {
    /** Not a `.locusbackup` file at all. */
    NotABackup,
    WrongPassword,
    /** Changed, damaged or incomplete: authentication failed after the password was accepted. */
    Damaged,
    /** Made by a newer Locus whose file format this one doesn't know. */
    NewerFormat,
    /** Its database is newer than this app's; update Locus first. */
    NewerSchema,
    /** A task is running; restoring would pull its data from under it. */
    RunActive,
}

class BackupException(val error: BackupError, cause: Throwable? = null, message: String? = null) :
    IOException(message ?: error.name, cause)
