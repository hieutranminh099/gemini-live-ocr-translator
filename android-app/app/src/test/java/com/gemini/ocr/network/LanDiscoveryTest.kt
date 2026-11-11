package com.gemini.ocr.network

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import kotlin.concurrent.thread

class LanDiscoveryTest {
    @After
    fun tearDown() {
        LanScanner.overrideScanner = null
    }

    private fun pin(cert: HeldCertificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cert.certificate.encoded)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun parseBeaconExtractsValidPcAndRejectsInvalidPin() {
        val validPin = "ab".repeat(32)
        val raw = """{"type":"gemocr_beacon","protocol":1,"name":"STUDIO-PC","host":"192.168.1.50:8765","addresses":["192.168.1.50"],"port":8765,"pin":"$validPin","lan_ticket":"ticket-1234567890abcdef"}"""

        val parsed = LanScanner.parseBeacon(raw, "192.168.1.55")
        assertNotNull(parsed)
        assertEquals("STUDIO-PC", parsed!!.name)
        assertEquals("192.168.1.55:8765", parsed.host)
        assertEquals(validPin, parsed.pin)
        assertEquals("ticket-1234567890abcdef", parsed.lanTicket)

        val invalid = """{"type":"gemocr_beacon","protocol":1,"name":"STUDIO-PC","host":"192.168.1.50:8765","port":8765,"pin":"short-pin","lan_ticket":"ticket-1234567890abcdef"}"""
        assertNull(LanScanner.parseBeacon(invalid))
    }

    @Test
    fun udpProbeDiscoversLocalBeaconOverSocket() = runBlocking {
        val fakePin = "cd".repeat(32)
        val serverSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val udpPort = serverSocket.localPort
        val responder = thread(start = true) {
            try {
                serverSocket.soTimeout = 1500
                val buf = ByteArray(2048)
                val packet = DatagramPacket(buf, buf.size)
                serverSocket.receive(packet)
                val beacon = """{"type":"gemocr_beacon","protocol":1,"name":"GAMING-PC","host":"127.0.0.1:8765","addresses":["127.0.0.1"],"port":8765,"pin":"$fakePin","lan_ticket":"ticket-abcdef1234567890"}"""
                    .toByteArray(Charsets.UTF_8)
                serverSocket.send(DatagramPacket(beacon, beacon.size, packet.address, packet.port))
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
            }
        }
        val found = LanScanner.scan(port = udpPort, timeoutMs = 1000L, settleAfterFirstMs = 50L)
        responder.join(1500)
        assertEquals(1, found.size)
        assertEquals("GAMING-PC", found.first().name)
        assertEquals("127.0.0.1:8765", found.first().host)
        assertEquals(fakePin, found.first().pin)
        assertEquals("ticket-abcdef1234567890", found.first().lanTicket)
    }

    @Test
    fun discoveredCertificatePinnedClientVerifiesAndExchangesTicket() {
        val cert = HeldCertificate.Builder().commonName("Companion").addSubjectAlternativeName("localhost").build()
        val certPin = pin(cert)
        val server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
            enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"device_id":"dev-1","token":"${"t".repeat(40)}","protocol":1}""")
            )
            start()
        }
        val client = SecureConnection.client(certPin)
        try {
            val hostPort = SecureConnection.host("localhost:${server.port}")
            val res = client.newCall(
                Request.Builder().url("https://$hostPort/v1/pair").build()
            ).execute()
            assertEquals(200, res.code)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
