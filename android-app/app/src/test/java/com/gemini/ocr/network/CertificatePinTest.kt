package com.gemini.ocr.network

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest

class CertificatePinTest {
    private fun pin(cert: HeldCertificate): String = MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded).joinToString("") { "%02x".format(it) }
    private fun server(cert: HeldCertificate): MockWebServer = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        enqueue(MockResponse().setBody("trusted")); start()
    }
    @Test fun explicitlyPairedSelfSignedCertificateIsAccepted() {
        val cert = HeldCertificate.Builder().commonName("Companion").addSubjectAlternativeName("localhost").build()
        val server = server(cert); val client = SecureConnection.client(pin(cert))
        try {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { assertEquals("trusted", it.body!!.string()) }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    @Test fun otherCertificateCannotReceiveDeviceRequest() {
        val cert = HeldCertificate.Builder().commonName("Other server").build()
        val server = server(cert); val client = SecureConnection.client("ab".repeat(32))
        try {
            assertThrows(IOException::class.java) { client.newCall(Request.Builder().url(server.url("/")).header("Authorization", "Bearer secret-device-token").build()).execute() }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    @Test fun expiredPairedCertificateIsRejected() {
        val now = System.currentTimeMillis()
        val cert = HeldCertificate.Builder().commonName("Expired").validityInterval(now-172800000,now-86400000).build()
        val server = server(cert); val client = SecureConnection.client(pin(cert))
        try {
            assertThrows(IOException::class.java) { client.newCall(Request.Builder().url(server.url("/")).build()).execute() }
            assertEquals(0,server.requestCount)
        } finally { server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    @Test fun unsafeAndAmbiguousHostInputsAreRejected() {
        for (host in listOf("http://192.168.1.1:8765", "user:password@host:8765", "host:8765/path", "host:99999", "host:0")) {
            assertThrows(IllegalArgumentException::class.java) { SecureConnection.host(host) }
        }
        assertThrows(IllegalArgumentException::class.java) { SecureConnection.client("") }
    }
}
