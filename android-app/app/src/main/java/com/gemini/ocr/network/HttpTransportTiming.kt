package com.gemini.ocr.network

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/** Timings and an enum protocol only. Never retain callback addresses, hostnames or HTTP data. */
data class HttpTransportMetrics(
    val totalMs: Long?, val connectionMs: Long?, val dnsMs: Long?, val tcpMs: Long?, val tlsMs: Long?,
    val connectionOtherMs: Long?, val prepareMs: Long?, val uploadMs: Long?, val waitHeadersMs: Long?,
    val bodyMs: Long?, val connectionReused: Boolean?, val protocol: String?,
) {
    fun traceFields(): Array<Pair<String, Any?>> = arrayOf(
        "http_call_ms" to totalMs, "http_connection_ms" to connectionMs, "http_dns_ms" to dnsMs,
        "http_tcp_ms" to tcpMs, "http_tls_ms" to tlsMs, "http_connection_other_ms" to connectionOtherMs,
        "http_prepare_ms" to prepareMs, "http_upload_ms" to uploadMs, "http_wait_headers_ms" to waitHeadersMs,
        "http_body_ms" to bodyMs, "http_connection_reused" to connectionReused, "http_protocol" to protocol)
}

class HttpTransportTiming(private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private var callAt: Long? = null
    private var connectedAt: Long? = null
    private var headersAt: Long? = null
    private var uploadedAt: Long? = null
    private var receivedAt: Long? = null
    private var endedAt: Long? = null
    private var dnsAt: Long? = null
    private var connectAt: Long? = null
    private var tlsAt: Long? = null
    private var dnsTotal = 0L
    private var connectTotal = 0L
    private var tlsTotal = 0L
    private var connects = 0
    private var protocol: String? = null
    private fun span(start: Long?, end: Long?): Long? =
        if (start == null || end == null || end < start) null else end - start

    val listener = object : EventListener() {
        override fun callStart(call: Call) = synchronized(this@HttpTransportTiming) { callAt = clockMs() }
        override fun dnsStart(call: Call, domainName: String) = synchronized(this@HttpTransportTiming) { dnsAt = clockMs() }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = synchronized(this@HttpTransportTiming) {
            dnsTotal += span(dnsAt, clockMs()) ?: 0; dnsAt = null
        }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = synchronized(this@HttpTransportTiming) {
            connects++; connectAt = clockMs()
        }
        override fun secureConnectStart(call: Call) = synchronized(this@HttpTransportTiming) { tlsAt = clockMs() }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) = synchronized(this@HttpTransportTiming) {
            tlsTotal += span(tlsAt, clockMs()) ?: 0; tlsAt = null
        }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = synchronized(this@HttpTransportTiming) {
            connectTotal += span(connectAt, clockMs()) ?: 0; connectAt = null
            this@HttpTransportTiming.protocol = protocol?.toString()
        }
        override fun connectionAcquired(call: Call, connection: Connection) = synchronized(this@HttpTransportTiming) {
            connectedAt = clockMs(); protocol = connection.protocol().toString()
        }
        override fun requestHeadersStart(call: Call) = synchronized(this@HttpTransportTiming) { headersAt = clockMs() }
        override fun requestBodyEnd(call: Call, byteCount: Long) = synchronized(this@HttpTransportTiming) { uploadedAt = clockMs() }
        // Use the complete-header boundary so a slow header read stays in the wait phase.
        override fun responseHeadersEnd(call: Call, response: Response) = synchronized(this@HttpTransportTiming) { receivedAt = clockMs() }
        override fun callEnd(call: Call) = synchronized(this@HttpTransportTiming) { endedAt = clockMs() }
        override fun callFailed(call: Call, ioe: IOException) = synchronized(this@HttpTransportTiming) { endedAt = clockMs() }
    }

    @Synchronized fun snapshot(): HttpTransportMetrics {
        val connection = span(callAt, connectedAt)
        val dns = dnsTotal.takeIf { connectedAt != null && dnsAt == null }
        val tls = tlsTotal.takeIf { connectedAt != null && tlsAt == null }
        val tcp = if (connectedAt != null && connectAt == null && tls != null)
            (connectTotal - tls).takeIf { it >= 0 } else null
        val other = if (connection != null && dns != null && tls != null && tcp != null)
            (connection - dns - tls - tcp).takeIf { it >= 0 } else null
        return HttpTransportMetrics(span(callAt, endedAt), connection, dns, tcp, tls, other,
            span(connectedAt, headersAt), span(headersAt, uploadedAt), span(uploadedAt, receivedAt),
            span(receivedAt, endedAt), connectedAt?.let { connects == 0 }, protocol)
    }
}
