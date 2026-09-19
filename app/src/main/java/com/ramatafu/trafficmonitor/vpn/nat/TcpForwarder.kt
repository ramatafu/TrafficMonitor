package com.ramatafu.trafficmonitor.vpn.nat

import android.content.Context
import android.util.Log
import com.ramatafu.trafficmonitor.parser.TcpFlags
import com.ramatafu.trafficmonitor.parser.TcpPacketParser
import com.ramatafu.trafficmonitor.parser.TcpSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

private const val TAG = "TcpForwarder"

data class TcpSessionKey(
    val clientIp: String, val clientPort: Int,
    val serverIp: String, val serverPort: Int
)

private enum class TcpState { SYN_RECEIVED, ESTABLISHED, CLOSING, CLOSED }

/**
 * Состояние одного TCP-соединения. Мы выступаем "TCP-сервером" для
 * приложения на устройстве (отвечаем на его SYN своим SYN-ACK) и
 * одновременно обычным TCP-клиентом для настоящего сервера в интернете
 * (через java.net.Socket). По сути мы — прозрачный мост между двумя
 * независимыми TCP-соединениями.
 *
 * УПРОЩЕНИЯ (осознанно, для учебного MVP):
 * - нет ретрансмиссий: если наш пакет потеряется по дороге к приложению,
 *   мы не заметим и не пошлём повторно — полагаемся на то, что на
 *   локальном участке (tun-интерфейс) потерь почти не бывает
 * - нет управления окном/перегрузкой — всегда шлём фиксированное окно
 * - закрытие соединения при FIN с любой стороны — сразу закрываем оба
 *   направления, а не поддерживаем честный half-close
 */
private class TcpSession(val key: TcpSessionKey, val socket: Socket) {
    var state = TcpState.SYN_RECEIVED

    // ourSeq — следующий байт последовательности, который МЫ отправим приложению
    // (сервер -> клиент, с точки зрения устройства)
    var ourSeq: Long = 0

    // clientSeqNext — следующий байт, который мы ОЖИДАЕМ от приложения;
    // именно это значение мы указываем в поле ack наших пакетов
    var clientSeqNext: Long = 0

    var relayJob: Job? = null
    @Volatile var lastActivityMs: Long = System.currentTimeMillis()
}

class TcpForwarder(
    private val context: Context,
    private val tunOutput: FileOutputStream
) {
    private val sessions = ConcurrentHashMap<TcpSessionKey, TcpSession>()
    private val forwarderScope = CoroutineScope(Dispatchers.IO)

    /** @param tcpSegmentBytes сырые байты, начиная с TCP-заголовка (после IP-заголовка) */
    fun handle(clientIp: String, serverIp: String, tcpSegmentBytes: ByteArray) {
        val tcp = TcpPacketParser.parse(tcpSegmentBytes) ?: return
        val key = TcpSessionKey(clientIp, tcp.sourcePort, serverIp, tcp.destPort)

        when {
            TcpFlags.has(tcp.flags, TcpFlags.RST) -> {
                Log.d(TAG, "Получили RST для $key")
                closeSession(key)
            }

            TcpFlags.has(tcp.flags, TcpFlags.SYN) && !TcpFlags.has(tcp.flags, TcpFlags.ACK) -> {
                if (!sessions.containsKey(key)) startNewConnection(key, tcp)
                // повторный SYN (ретрансмит из-за задержки нашего SYN-ACK) — игнорируем,
                // настоящий SYN-ACK уже летит или улетел
            }

            else -> {
                val session = sessions[key] ?: return // сегмент для неизвестной/уже закрытой сессии
                handleSegment(session, tcp)
            }
        }
    }

    private fun startNewConnection(key: TcpSessionKey, tcp: TcpSegment) {
        Log.d(TAG, "Новый SYN: $key")
        val socket = Socket()
        val session = TcpSession(key, socket)
        session.clientSeqNext = (tcp.sequenceNumber + 1) and 0xFFFFFFFFL // SYN занимает 1 байт последовательности
        session.ourSeq = Random.nextInt().toLong() and 0xFFFFFFFFL // случайный начальный номер (ISN)
        sessions[key] = session

        forwarderScope.launch {
            try {
                val network = UnderlyingNetworkProvider.find(context)
                if (network == null) {
                    Log.w(TAG, "Не нашли не-VPN сеть для $key — нет доступа в интернет")
                    sessions.remove(key)
                    return@launch
                }
                network.bindSocket(socket) // рабочая замена protect(), см. UnderlyingNetworkProvider
                socket.connect(InetSocketAddress(key.serverIp, key.serverPort), 5000)
                Log.d(TAG, "Подключились к ${key.serverIp}:${key.serverPort}")
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось подключиться к ${key.serverIp}:${key.serverPort}: ${e.message}")
                sessions.remove(key)
                return@launch
            }

            // Отвечаем приложению нашим SYN-ACK, завершая нашу половину handshake
            writePacket(
                TcpPacketBuilder.build(
                    sourceIp = InetAddress.getByName(key.serverIp), sourcePort = key.serverPort,
                    destIp = InetAddress.getByName(key.clientIp), destPort = key.clientPort,
                    seq = session.ourSeq, ack = session.clientSeqNext,
                    flags = TcpFlags.SYN or TcpFlags.ACK, window = 65535, payload = ByteArray(0)
                )
            )
            Log.d(TAG, "Отправили SYN-ACK для $key")
            session.ourSeq += 1 // наш SYN тоже "занимает" один номер последовательности

            // Дальше просто перекладываем байты ответа сервера в TCP-сегменты для приложения
            relayServerResponses(session)
        }
    }

    private fun relayServerResponses(session: TcpSession) {
        session.relayJob = forwarderScope.launch {
            val buffer = ByteArray(16 * 1024)
            val input = try { session.socket.getInputStream() } catch (e: Exception) {
                Log.w(TAG, "Нет input stream для ${session.key}: ${e.message}")
                closeSession(session.key); return@launch
            }
            try {
                while (isActive) {
                    val n = input.read(buffer)
                    if (n <= 0) { // сервер закрыл соединение — сообщаем об этом приложению через FIN
                        Log.d(TAG, "Сервер закрыл соединение ${session.key}, шлём FIN")
                        sendFin(session)
                        break
                    }
                    Log.d(TAG, "Получили $n байт от сервера ${session.key}, пересылаем в приложение")
                    sendData(session, buffer.copyOfRange(0, n))
                    session.lastActivityMs = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                Log.d(TAG, "relayServerResponses завершился для ${session.key}: ${e.message}")
            }
        }
    }

    private fun handleSegment(session: TcpSession, tcp: TcpSegment) {
        session.lastActivityMs = System.currentTimeMillis()

        if (session.state == TcpState.SYN_RECEIVED && TcpFlags.has(tcp.flags, TcpFlags.ACK)) {
            session.state = TcpState.ESTABLISHED
            Log.d(TAG, "Соединение ${session.key} перешло в ESTABLISHED")
        }

        if (tcp.payload.isNotEmpty()) {
            try {
                session.socket.getOutputStream().write(tcp.payload)
                session.clientSeqNext = (session.clientSeqNext + tcp.payload.size) and 0xFFFFFFFFL
                sendAck(session)
                Log.d(TAG, "Записали ${tcp.payload.size} байт от приложения в сокет ${session.key}")
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось записать данные в сокет ${session.key}: ${e.message}")
                closeSession(session.key)
                return
            }
        }

        if (TcpFlags.has(tcp.flags, TcpFlags.FIN)) {
            Log.d(TAG, "Получили FIN от приложения для ${session.key}")
            session.clientSeqNext = (session.clientSeqNext + 1) and 0xFFFFFFFFL
            sendAck(session)
            // Упрощение: не поддерживаем честный half-close, закрываем сессию целиком
            closeSession(session.key)
        }
    }

    private fun sendAck(session: TcpSession) = writePacket(
        TcpPacketBuilder.build(
            sourceIp = InetAddress.getByName(session.key.serverIp), sourcePort = session.key.serverPort,
            destIp = InetAddress.getByName(session.key.clientIp), destPort = session.key.clientPort,
            seq = session.ourSeq, ack = session.clientSeqNext,
            flags = TcpFlags.ACK, window = 65535, payload = ByteArray(0)
        )
    )

    private fun sendData(session: TcpSession, data: ByteArray) {
        writePacket(
            TcpPacketBuilder.build(
                sourceIp = InetAddress.getByName(session.key.serverIp), sourcePort = session.key.serverPort,
                destIp = InetAddress.getByName(session.key.clientIp), destPort = session.key.clientPort,
                seq = session.ourSeq, ack = session.clientSeqNext,
                flags = TcpFlags.ACK or TcpFlags.PSH, window = 65535, payload = data
            )
        )
        session.ourSeq = (session.ourSeq + data.size) and 0xFFFFFFFFL
    }

    private fun sendFin(session: TcpSession) {
        writePacket(
            TcpPacketBuilder.build(
                sourceIp = InetAddress.getByName(session.key.serverIp), sourcePort = session.key.serverPort,
                destIp = InetAddress.getByName(session.key.clientIp), destPort = session.key.clientPort,
                seq = session.ourSeq, ack = session.clientSeqNext,
                flags = TcpFlags.FIN or TcpFlags.ACK, window = 65535, payload = ByteArray(0)
            )
        )
        session.ourSeq += 1
    }

    private fun writePacket(packet: ByteArray) {
        try {
            synchronized(tunOutput) { tunOutput.write(packet) }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось записать пакет в tun: ${e.message}")
        }
    }

    private fun closeSession(key: TcpSessionKey) {
        sessions.remove(key)?.let {
            it.relayJob?.cancel()
            try { it.socket.close() } catch (e: Exception) { /* уже закрыт */ }
        }
    }

    /** Закрывает соединения, которые молчат дольше timeoutMs — вызывать периодически. */
    fun cleanupIdleSessions(timeoutMs: Long = 120_000) {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { (_, session) ->
            val idle = now - session.lastActivityMs > timeoutMs
            if (idle) {
                session.relayJob?.cancel()
                try { session.socket.close() } catch (e: Exception) { }
            }
            idle
        }
    }

    fun stopAll() {
        sessions.values.forEach {
            it.relayJob?.cancel()
            try { it.socket.close() } catch (e: Exception) { }
        }
        sessions.clear()
    }
}
