package com.example.garagedooropener

// =============================================================================
// HTTP layer
//
// Built from a GarageConfig rather than from constants, because the certificate
// pin and credentials now arrive at runtime. One instance is created when a
// config is loaded and replaced if the config changes.
// =============================================================================

import android.util.Base64
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val LOCAL_TIMEOUT_MS = 5_000L
private const val INTERNET_TIMEOUT_MS = 15_000L

class Net(private val web: WebConfig) {

    /**
     * Certificate pinning.
     *
     * The device uses a self-signed certificate, which Android's trust store
     * rejects outright - a browser can offer a click-through, but an HTTP client
     * just throws. Rather than disabling validation (which would accept
     * anything), we validate against one specific public key.
     *
     * This is stricter than ordinary HTTPS: it trusts exactly one key rather
     * than every CA in existence.
     *
     * The hash covers the public key, not the whole certificate, so the cert can
     * be reissued for a longer expiry or extra hostnames without changing the
     * pin - as long as the same key is kept.
     */
    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            // Not used - we never present a client certificate.
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val cert = chain.firstOrNull()
                ?: throw CertificateException("Server presented no certificate")

            // publicKey.encoded is the SubjectPublicKeyInfo in DER form, which is
            // exactly what `openssl pkey -pubin -outform der` emits - so this
            // matches the value make-cert.sh prints.
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
            val pin = Base64.encodeToString(digest, Base64.NO_WRAP)

            if (pin != web.certPinSha256) {
                throw CertificateException(
                    "Certificate pin mismatch. Config expects ${web.certPinSha256} but device presented $pin. " +
                        "If the device's certificate was regenerated, paste an updated config."
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val socketFactory: SSLSocketFactory =
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }.socketFactory

    /**
     * Must be one shared instance, not a new lambda per request: Android decides
     * whether two requests may share a pooled connection by comparing (among
     * other things) the socket factory and hostname verifier. A fresh object each
     * time means nothing is ever reused.
     *
     * Verification itself is a deliberate no-op - the public key is pinned above,
     * so only this one device can satisfy it. A name check adds nothing, and
     * would fail awkwardly when connecting to the local address by IP.
     */
    private val hostnameVerifier = HostnameVerifier { _, _ -> true }

    private val base: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(socketFactory, trustManager)
        .hostnameVerifier(hostnameVerifier)
        // Idle timeout must stay below the device's own (30s), so we discard a
        // connection before it does rather than after.
        .connectionPool(ConnectionPool(4, 25, TimeUnit.SECONDS))
        .retryOnConnectionFailure(true)
        .build()

    // Separate timeouts, sharing one pool via newBuilder(). Lets a local probe
    // give up quickly when the phone is on cellular and the LAN address will
    // never answer, without shortchanging the slower internet path.
    private val localClient: OkHttpClient = base.newBuilder()
        .connectTimeout(LOCAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(LOCAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val internetClient: OkHttpClient = base.newBuilder()
        .connectTimeout(INTERNET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(INTERNET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    fun clientFor(baseUrl: String): OkHttpClient =
        if (baseUrl == web.localUrl) localClient else internetClient

    fun authHeader(): String =
        "Basic " + Base64.encodeToString(
            "${web.username}:${web.password}".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
}
