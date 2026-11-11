package com.gemini.ocr.network

import com.gemini.ocr.SessionState
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.capture.PipelineTrace
import android.os.SystemClock
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

object SecureConnection {
    fun client(pin: String): OkHttpClient {
        val expected = pin.lowercase().replace(":", "").trim()
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "Mã chứng chỉ phải có 64 ký tự SHA-256." }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw java.security.cert.CertificateException("Unsupported") }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val certificate = chain.firstOrNull() ?: throw java.security.cert.CertificateException("Missing certificate")
                certificate.checkValidity()
                val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())) throw java.security.cert.CertificateException("Chứng chỉ máy chủ không khớp")
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        // Identity is the explicitly paired certificate, independent of a changing LAN IP.
        return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { _, session ->
                val cert = session.peerCertificates.firstOrNull() as? X509Certificate
                cert != null && MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) } == expected
            }.connectTimeout(8, TimeUnit.SECONDS).readTimeout(50, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    }
    fun host(value: String): String {
        val host = value.trim().removePrefix("https://").removePrefix("wss://").trimEnd('/')
        require(host.matches(Regex("[a-zA-Z0-9.-]+:[0-9]{1,5}"))) { "Địa chỉ cần dạng 192.168.1.10:8765" }
        require(host.substringAfterLast(':').toInt() in 1..65535)
        return host
    }
    suspend fun pair(settings: AppSettings, hostValue: String, pin: String, code: String, name: String) = withContext(Dispatchers.IO) {
        val normalizedHost = host(hostValue)
        val normalizedPin = pin.lowercase().replace(":", "").trim()
        val client = client(normalizedPin)
        try {
            val body = JSONObject().put("name", name.take(64)).put("code", code.trim()).toString()
            val request = Request.Builder().url("https://$normalizedHost/v1/pair")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Mã ghép cặp hết hạn hoặc không đúng. Tạo mã mới trên Windows.")
                val json = JSONObject(response.body!!.string())
                val token = json.getString("token")
                require(token.length >= 32)
                currentCoroutineContext().ensureActive()
                settings.savePairing(normalizedHost, normalizedPin, token)
            }
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    suspend fun connectDiscovered(settings: AppSettings, pc: DiscoveredPc, name: String) = withContext(Dispatchers.IO) {
        val normalizedHost = host(pc.host)
        val normalizedPin = pc.pin.lowercase().replace(":", "").trim()
        val client = client(normalizedPin)
        try {
            val existingToken = settings.token
            if (existingToken.isNotBlank() && settings.pin.equals(normalizedPin, ignoreCase = true)) {
                val checkReq = Request.Builder()
                    .url("https://$normalizedHost/v1/auth-check")
                    .header("Authorization", "Bearer $existingToken")
                    .get()
                    .build()
                val stillValid = try {
                    client.newCall(checkReq).execute().use { it.isSuccessful }
                } catch (_: Exception) {
                    false
                }
                if (stillValid) {
                    currentCoroutineContext().ensureActive()
                    settings.host = normalizedHost
                    withContext(Dispatchers.Main) {
                        SessionState.connected.value = true
                        SessionState.status.value = "Đã tự động kết nối với ${pc.name} ($normalizedHost)"
                    }
                    return@withContext
                }
            }
            require(pc.lanTicket.isNotBlank()) { "Máy tính chưa cấp tín hiệu ghép cặp tự động. Hãy thử quét lại." }
            val body = JSONObject()
                .put("name", name.take(64))
                .put("lan_ticket", pc.lanTicket)
                .toString()
            val request = Request.Builder()
                .url("https://$normalizedHost/v1/pair")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Không thể ghép cặp tự động với ${pc.name}. Hãy thử quét lại.")
                }
                val json = JSONObject(response.body!!.string())
                val token = json.getString("token")
                require(token.length >= 32)
                currentCoroutineContext().ensureActive()
                settings.savePairing(normalizedHost, normalizedPin, token)
                withContext(Dispatchers.Main) {
                    SessionState.connected.value = true
                    SessionState.status.value = "Đã tự động kết nối với ${pc.name} ($normalizedHost)"
                }
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}

class TranslationClient(private val settings: AppSettings, private val scope: CoroutineScope,
    private val onMessage: (JSONObject) -> Unit, private val onReconnect: () -> Unit) {
    private val client = SecureConnection.client(settings.pin)
    private var socket: WebSocket? = null
    private var reconnect: Job? = null
    private var stopped = false
    private var attempts = 0
    private var generation = 0L
    private var connected = false
    private var probe: Job? = null
    private var probeAt = 0L
    private val host = SecureConnection.host(settings.host)
    private val token = settings.token

    fun connect() {
        if (stopped || !scope.isActive) return
        val current = ++generation
        connected = false
        SessionState.status.value = "Đang kết nối máy tính…"
        val request = Request.Builder().url("wss://$host/v1/stream")
            .header("Authorization", "Bearer $token").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            private fun dispatch(action: () -> Unit) {
                scope.launch(Dispatchers.Main.immediate) { if (!stopped && generation == current) action() }
            }
            override fun onOpen(webSocket: WebSocket, response: Response) {
                dispatch {
                    attempts = 0; connected = true
                    SessionState.connected.value = true
                    SessionState.status.value = "Đã kết nối • Chờ văn bản"
                    onReconnect()
                    probe?.cancel()
                    probe = scope.launch {
                        while (isActive && connected) {
                            probeAt = SystemClock.elapsedRealtime()
                            send(JSONObject().put("type", "ping"))
                            delay(10000)
                        }
                    }
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    dispatch {
                        if (json.optString("type") == "hello" && json.optInt("protocol") != 1) {
                            stopped = true; connected = false; SessionState.connected.value = false
                            SessionState.status.value = "Phiên bản không tương thích. Cập nhật cả Windows và Android."
                            webSocket.close(1002, "Protocol mismatch")
                        } else if (json.optString("type") == "pong" && probeAt > 0) {
                            SessionState.networkRtt.value = SystemClock.elapsedRealtime() - probeAt
                            PipelineTrace.event("network_ping", "round_trip_ms" to SessionState.networkRtt.value)
                            probeAt = 0
                        } else onMessage(json)
                    }
                } catch (_: Exception) { }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                dispatch {
                    if (code == 4401) unauthorized()
                    else retry(if (code == 4429) 5000 else 1000)
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                dispatch {
                    when {
                        response?.code == 403 || response?.code == 401 -> unauthorized()
                        generateSequence(t) { it.cause }.any { it is SSLPeerUnverifiedException || it is java.security.cert.CertificateException } -> {
                            stopped = true; connected = false; SessionState.connected.value = false
                            SessionState.status.value = "Không xác minh được chứng chỉ. Kiểm tra ngày giờ và ghép cặp lại bằng QR."
                        }
                        else -> retry()
                    }
                }
            }
        })
    }
    private fun unauthorized() {
        stopped = true; connected = false; settings.token = ""
        SessionState.connected.value = false
        SessionState.status.value = "Thiết bị đã bị hủy ghép cặp. Dừng và ghép cặp lại."
    }
    private fun retry(minDelay: Long = 1000) {
        connected = false
        SessionState.connected.value = false
        if (stopped || reconnect?.isActive == true) return
        SessionState.status.value = "Mất kết nối • Đang thử lại…"
        reconnect = scope.launch {
            delay((1000L shl attempts.coerceAtMost(4)).coerceAtMost(15000L).coerceAtLeast(minDelay))
            attempts++
            reconnect = null
            connect()
        }
    }
    fun send(json: JSONObject): Boolean = !stopped && connected && socket?.send(json.toString()) == true
    fun cancel() { send(JSONObject().put("type", "cancel")) }
    fun close() {
        stopped = true
        generation++
        connected = false
        reconnect?.cancel()
        probe?.cancel()
        socket?.close(1000, "Stopped")
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        SessionState.connected.value = false
    }
}

