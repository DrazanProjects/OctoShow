package com.drazan.echoshow.octoprint.octo

import okhttp3.internal.tls.OkHostnameVerifier
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * How this display decides an https OctoPrint is the right one.
 *
 * A Pi on the home network serves a certificate it signed itself, which no
 * certificate authority vouches for. So trust works the way an SSH client's
 * does: during setup ([learning]) the certificate on offer is accepted and its
 * fingerprint noted; from then on only that exact certificate ([pinned]) is
 * accepted, and a different one is refused rather than quietly trusted.
 *
 * Certificates that do chain to a public authority are accepted as usual.
 */
class ServerTrust(private val pinned: String?, private val learning: Boolean) {

    /** Fingerprint of a self-signed certificate accepted on first use, to be saved with the server. */
    @Volatile
    var learned: String? = null
        private set

    /** Set when the server showed a certificate other than the one pinned at setup. */
    @Volatile
    var changed: Boolean = false
        private set

    /** Certificates let in by fingerprint; their names aren't expected to match the address. */
    private val byFingerprint = ConcurrentHashMap.newKeySet<String>()

    val manager: X509TrustManager = object : X509TrustManager {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val print = fingerprint(chain.firstOrNull() ?: throw CertificateException("No certificate"))
            if (pinned != null && pinned == print) {
                byFingerprint += print
                changed = false
                return
            }
            try {
                platform.checkServerTrusted(chain, authType)
                return
            } catch (e: CertificateException) {
                if (pinned != null) {
                    changed = true
                    throw CertificateException("The server's certificate is not the one this display was set up with", e)
                }
                if (!learning) throw e
            }
            // First meeting with a self-signed server. Whichever certificate is met first is
            // the one that is kept: a different one turning up before setup is over is someone
            // else answering in the server's place, and is refused.
            synchronized(byFingerprint) {
                val first = learned
                if (first != null && first != print) {
                    throw CertificateException("The server's certificate changed while this display was being set up")
                }
                learned = print
            }
            byFingerprint += print
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("Client certificates are not used")

        override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
    }

    val socketFactory: SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }.socketFactory

    val hostnames = HostnameVerifier { host, session ->
        val cert = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
        (cert != null && fingerprint(cert) in byFingerprint) || OkHostnameVerifier.verify(host, session)
    }

    private companion object {
        val platform: X509TrustManager by lazy {
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as KeyStore?) }
                .trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
    }
}
