package com.gemini.ocr.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

data class DiscoveredPc(
    val name: String,
    val host: String,
    val pin: String,
    val lanTicket: String,
    val addresses: List<String> = emptyList()
)

object LanScanner {
    const val DEFAULT_DISCOVERY_PORT = 8767

    @Volatile
    var overrideScanner: (suspend () -> List<DiscoveredPc>)? = null

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    private fun extractString(json: String, key: String): String? {
        val match = Regex(""""${Regex.escape(key)}"\s*:\s*"((?:\\.|[^"\\])*)"""").find(json) ?: return null
        return match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun extractInt(json: String, key: String): Int? {
        val match = Regex(""""${Regex.escape(key)}"\s*:\s*(-?\d+)""").find(json) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun extractStringArray(json: String, key: String): List<String> {
        val match = Regex(""""${Regex.escape(key)}"\s*:\s*\[([^\]]*)]""").find(json) ?: return emptyList()
        return Regex(""""((?:\\.|[^"\\])*)"""").findAll(match.groupValues[1])
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    fun parseBeacon(payload: String, senderIp: String? = null): DiscoveredPc? {
        return try {
            val trimmed = payload.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
            if (extractString(trimmed, "type") != "gemocr_beacon") return null
            if ((extractInt(trimmed, "protocol") ?: 1) != 1) return null
            val pin = extractString(trimmed, "pin").orEmpty().lowercase().replace(":", "").trim()
            if (!pin.matches(Regex("[0-9a-f]{64}"))) {
                return null
            }
            val port = extractInt(trimmed, "port") ?: 8765
            if (port !in 1..65535) {
                return null
            }
            val name = extractString(trimmed, "name").orEmpty().trim().ifBlank { "Windows PC" }
            val lanTicket = extractString(trimmed, "lan_ticket").orEmpty().trim()
            val addresses = extractStringArray(trimmed, "addresses")
            val rawHost = extractString(trimmed, "host").orEmpty().trim()
            val cleanedSender = senderIp?.removePrefix("/")?.trim().orEmpty()
            val resolvedHost = when {
                cleanedSender.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) &&
                    cleanedSender != "0.0.0.0" &&
                    (!cleanedSender.startsWith("127.") || rawHost.isBlank() || rawHost.startsWith("127.")) ->
                    "$cleanedSender:$port"
                rawHost.isNotBlank() -> SecureConnection.host(rawHost)
                addresses.isNotEmpty() -> SecureConnection.host("${addresses.first()}:$port")
                else -> return null
            }
            DiscoveredPc(
                name = name,
                host = SecureConnection.host(resolvedHost),
                pin = pin,
                lanTicket = lanTicket,
                addresses = addresses
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun collectBroadcastTargets(): List<InetAddress> {
        val targets = linkedSetOf<String>()
        targets.add("255.255.255.255")
        targets.add("10.0.2.2")
        targets.add("127.0.0.1")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast is Inet4Address) {
                        broadcast.hostAddress?.let { targets.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return targets.mapNotNull { host ->
            try {
                InetAddress.getByName(host)
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun scan(
        port: Int = DEFAULT_DISCOVERY_PORT,
        timeoutMs: Long = 1800L,
        settleAfterFirstMs: Long = 300L
    ): List<DiscoveredPc> {
        overrideScanner?.let { return it.invoke() }
        return withContext(Dispatchers.IO) {
            val discovered = linkedMapOf<String, DiscoveredPc>()
            val probeBytes = """{"type":"gemocr_discover","protocol":1}""".toByteArray(Charsets.UTF_8)

            val probeSocket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 80
            }
            val passiveSocket = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 60
                    bind(InetSocketAddress(port))
                }
            } catch (_: Exception) {
                null
            }

            try {
                val targets = collectBroadcastTargets()
                fun sendProbes() {
                    for (target in targets) {
                        try {
                            val packet = DatagramPacket(probeBytes, probeBytes.size, target, port)
                            probeSocket.send(packet)
                        } catch (_: Exception) {
                        }
                    }
                }

                fun readSocket(socket: DatagramSocket, buffer: ByteArray) {
                    while (true) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                            val senderIp = packet.address?.hostAddress
                            val pc = parseBeacon(text, senderIp)
                            if (pc != null) {
                                discovered[pc.pin] = pc
                            }
                        } catch (_: SocketTimeoutException) {
                            break
                        } catch (_: Exception) {
                            break
                        }
                    }
                }

                val buffer = ByteArray(4096)
                val startAt = monotonicMs()
                var firstFoundAt = 0L
                var secondProbeSent = false

                sendProbes()

                while (true) {
                    coroutineContext.ensureActive()
                    val now = monotonicMs()
                    val elapsed = now - startAt
                    if (elapsed >= timeoutMs) break
                    if (!secondProbeSent && elapsed >= 550L && discovered.isEmpty()) {
                        secondProbeSent = true
                        sendProbes()
                    }
                    readSocket(probeSocket, buffer)
                    if (passiveSocket != null) {
                        readSocket(passiveSocket, buffer)
                    }
                    if (discovered.isNotEmpty()) {
                        if (firstFoundAt == 0L) {
                            firstFoundAt = monotonicMs()
                        } else if (monotonicMs() - firstFoundAt >= settleAfterFirstMs) {
                            break
                        }
                    }
                }
                discovered.values.toList()
            } finally {
                try {
                    probeSocket.close()
                } catch (_: Exception) {
                }
                try {
                    passiveSocket?.close()
                } catch (_: Exception) {
                }
            }
        }
    }
}
