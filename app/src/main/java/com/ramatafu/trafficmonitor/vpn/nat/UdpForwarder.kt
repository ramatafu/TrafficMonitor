package com.ramatafu.trafficmonitor.vpn.nat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "UdpForwarder"

/**
 * Ключ NAT-сессии: кто из приложения (адрес+порт) говорит с кем снаружи.
 * По этому ключу находим уже открытый сокет для ответа.
 */
data class UdpSessionKey(
    val clientIp: String, val clientPort: Int,
    val remoteIp: String, val remotePort: Int
)

private class UdpSession(val socket: DatagramSocket, val key: UdpSessionKey) {
    var readerJob: Job? = null
    @Volatile var lastActivityMs: Long = System.currentTimeMillis()
}

/**
 * На каждый НОВЫЙ (clientIp,clientPort,remoteIp,remotePort) открываем один
 * настоящий DatagramSocket и привязываем его к реальной (не-VPN) сети через
 * Network.bindSocket() — иначе он сам попадёт в tun и получится петля.
 * (На части устройств VpnService.protect() ненадёжен, поэтому используем
 * этот более прямой способ — см. UnderlyingNetworkProvider.)
 */
class UdpForwarder(
    private val context: Context,
    private val tunOutput: FileOutputStream
) {
    private val sessions = ConcurrentHashMap<UdpSessionKey, UdpSession>()
    private val forwarderScope = CoroutineScope(Dispatchers.IO)

    fun forward(
        clientIp: String, clientPort: Int,
        remoteIp: String, remotePort: Int,
        payload: ByteArray
    ) {
        val key = UdpSessionKey(clientIp, clientPort, remoteIp, remotePort)
        val session = sessions.getOrPut(key) { createSession(key) } ?: return

        try {
            val remoteAddress = InetAddress.getByName(remoteIp)
            val packet = DatagramPacket(payload, payload.size, remoteAddress, remotePort)
            session.socket.send(packet)
            session.lastActivityMs = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось отправить UDP пакет: ${e.message}")
            closeSession(key)
        }
    }

    private fun createSession(key: UdpSessionKey): UdpSession? {
        val network = UnderlyingNetworkProvider.find(context)
        if (network == null) {
            Log.w(TAG, "Не нашли не-VPN сеть для UDP $key — нет доступа в интернет")
            return null
        }

        val socket = try {
            DatagramSocket().apply {
                // Критично: без привязки к реальной сети этот сокет сам уйдёт
                // через наш же tun — получится петля.
                network.bindSocket(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось создать сокет: ${e.message}")
            return null
        }

        val session = UdpSession(socket, key)
        session.readerJob = forwarderScope.launch {
            val buffer = ByteArray(32 * 1024)
            while (isActive) {
                try {
                    val incoming = DatagramPacket(buffer, buffer.size)
                    socket.receive(incoming) // блокируется, пока не придёт ответ

                    val response = UdpPacketBuilder.build(
                        sourceIp = incoming.address, sourcePort = incoming.port,
                        destIp = InetAddress.getByName(key.clientIp), destPort = key.clientPort,
                        payload = incoming.data.copyOfRange(0, incoming.length)
                    )

                    synchronized(tunOutput) {
                        tunOutput.write(response)
                    }
                    session.lastActivityMs = System.currentTimeMillis()
                } catch (e: Exception) {
                    // сокет закрылся (таймаут сессии) — нормальное завершение цикла
                    break
                }
            }
        }
        return session
    }

    private fun closeSession(key: UdpSessionKey) {
        sessions.remove(key)?.let {
            it.readerJob?.cancel()
            it.socket.close()
        }
    }

    /** Закрывает сессии, которые молчат дольше timeoutMs — вызывать периодически. */
    fun cleanupIdleSessions(timeoutMs: Long = 60_000) {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { (key, session) ->
            val idle = now - session.lastActivityMs > timeoutMs
            if (idle) {
                session.readerJob?.cancel()
                session.socket.close()
            }
            idle
        }
    }

    fun stopAll() {
        sessions.values.forEach {
            it.readerJob?.cancel()
            it.socket.close()
        }
        sessions.clear()
    }
}
