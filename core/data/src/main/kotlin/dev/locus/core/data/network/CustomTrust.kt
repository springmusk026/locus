package dev.locus.core.data.network

import dev.locus.core.model.TrustedCertificate
import java.io.ByteArrayInputStream
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertPathValidator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/** Reading certificate files the user imports. */
internal object Certificates {
    const val MAX_FILE_BYTES = 256 * 1024
    const val MAX_PER_FILE = 20

    /** Every X.509 certificate in [bytes] (PEM, one or several, or DER). Throws when there is none. */
    fun parse(bytes: ByteArray): List<X509Certificate> {
        require(bytes.size <= MAX_FILE_BYTES) { "That file is too large to be a certificate." }
        val certs = try {
            CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(bytes)).filterIsInstance<X509Certificate>()
        } catch (e: CertificateException) {
            throw IllegalArgumentException("That file isn't a PEM or DER certificate.", e)
        }
        require(certs.isNotEmpty()) { "No certificate found in that file." }
        require(certs.size <= MAX_PER_FILE) { "That file holds too many certificates." }
        return certs
    }

    fun toPem(cert: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded) + "\n-----END CERTIFICATE-----\n"

    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    /** CN=… from the subject, or the whole subject when it has no common name. */
    fun subjectName(cert: X509Certificate): String {
        val dn = cert.subjectX500Principal.getName(javax.security.auth.x500.X500Principal.RFC2253)
        return Regex("""(?:^|,)CN=((?:\\.|[^,])+)""").find(dn)?.groupValues?.get(1)?.replace("\\", "") ?: dn
    }

    fun toTrusted(cert: X509Certificate): TrustedCertificate = TrustedCertificate(
        id = UUID.randomUUID().toString(),
        pem = toPem(cert),
        subject = subjectName(cert),
        sha256 = fingerprint(cert),
        notAfter = cert.notAfter.time,
    )

    fun decode(trusted: TrustedCertificate): X509Certificate? = runCatching { parse(trusted.pem.toByteArray()).first() }.getOrNull()
}

/**
 * Trusts what the system trusts, plus [extra] certificates for this client only. The system's trust
 * manager decides first, with its full policy (on Android that includes the Network Security
 * Config and, from Android 17, Certificate Transparency for public CAs). Only a chain it rejects is
 * then checked against [extra] with plain PKIX path validation, so the extra CAs can add trust but
 * never take any away, and other providers and the rest of the app are unaffected.
 *
 * A server certificate that is itself one of [extra] (a self-signed server the user pinned) is
 * accepted as is. Host names are checked by OkHttp's HostnameVerifier either way.
 */
internal class ExtraCaTrustManager(
    private val system: X509TrustManager,
    extra: List<X509Certificate>,
) : X509ExtendedTrustManager() {
    private val anchors: List<X509Certificate> = extra.distinctBy { it.encoded.contentHashCode() }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + anchors

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        (system as? X509ExtendedTrustManager)?.checkClientTrusted(chain, authType, socket) ?: system.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        (system as? X509ExtendedTrustManager)?.checkClientTrusted(chain, authType, engine) ?: system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        orExtra(chain) { system.checkServerTrusted(chain, authType) }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        orExtra(chain) { (system as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, socket) ?: system.checkServerTrusted(chain, authType) }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        orExtra(chain) { (system as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, engine) ?: system.checkServerTrusted(chain, authType) }

    private inline fun orExtra(chain: Array<X509Certificate>, systemCheck: () -> Unit) {
        val rejected = try {
            systemCheck()
            return
        } catch (e: CertificateException) {
            e
        }
        if (anchors.isEmpty() || chain.isEmpty() || !chainsToExtra(chain)) throw rejected
    }

    /** Whether [chain] (leaf first) leads to one of [anchors] and validates under PKIX. */
    fun chainsToExtra(chain: Array<X509Certificate>): Boolean {
        val leaf = chain[0]
        if (anchors.any { it.encoded.contentEquals(leaf.encoded) }) {
            return runCatching { leaf.checkValidity() }.isSuccess && isServerCert(leaf)
        }
        if (!isServerCert(leaf)) return false
        // Follow issuers from the leaf through the certificates the server sent, up to an anchor.
        val path = mutableListOf(leaf)
        var current = leaf
        while (anchors.none { it.subjectX500Principal == current.issuerX500Principal }) {
            current = chain.firstOrNull { c -> c.subjectX500Principal == current.issuerX500Principal && c !in path } ?: return false
            if (path.size >= 8) return false
            path += current
        }
        return try {
            val params = PKIXParameters(anchors.map { TrustAnchor(it, null) }.toSet()).apply {
                // Private CAs rarely publish revocation endpoints a phone can reach.
                isRevocationEnabled = false
            }
            CertPathValidator.getInstance("PKIX").validate(CertificateFactory.getInstance("X.509").generateCertPath(path), params)
            true
        } catch (e: java.security.GeneralSecurityException) {
            false
        }
    }

    /** No extended key usage, or one that allows TLS server authentication. */
    private fun isServerCert(cert: X509Certificate): Boolean {
        val eku = runCatching { cert.extendedKeyUsage }.getOrNull() ?: return true
        return SERVER_AUTH in eku || ANY_EKU in eku
    }

    companion object {
        private const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
        private const val ANY_EKU = "2.5.29.37.0"

        /** The platform's default trust manager (the system store and, on Android, the Network Security Config). */
        fun systemTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        fun sslContext(trustManager: X509TrustManager): SSLContext =
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    }
}
