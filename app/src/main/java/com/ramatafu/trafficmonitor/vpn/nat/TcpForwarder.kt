package com.ramatafu.trafficmonitor.vpn.nat

import android.content.Context
import android.util.Log
import com.ramatafu.trafficmonitor.parser.TcpFlags
import com.ramatafu.trafficmonitor.parser.TcpPacketParser
import com.ramatafu.trafficmonitor.parser.TcpSegment
import com.ramatafu.trafficmonitor.parser.TlsSniParser
import com.ramatafu.trafficmonitor.vpn.ConnectionLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext // <--- ДОБАВЛЕН ЭТОТ ИМПОРТ
import kotlinx.coroutines.delay
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

// Состояние одного TCP-соединения. Мы выступаем "TCP-сервером" для
// приложения на устройстве (отвечаем на его SYN своим SYN-ACK) и
// одновременно обычным TCP-клиентом для настоящего сервера в интернете
// (через java.net.Socket). По сути мы - прозрачный мост между двумя
// независимыми TCP-соединениями.
//
// УПРОЩЕНИЯ, что осталось (осознанно, для учебного MVP):
// - нет ретрансмиссий: если наш пакет потеряется по дороге к приложению,
//   мы не заметим и не пошлём повторно - полагаемся на то, что на
//   локальном участке (tun-интерфейс) потерь почти не бывает
// - нет управления перегрузкой (congestion control) - только простое
//   уважение окна получателя (flow control), см. ourSeq/highestAckedByClient
private class TcpSession(val key: TcpSessionKey, val socket: Socket, val packageName: String) {
    var state = TcpState.SYN_RECEIVED

    // ourSeq - следующий байт последовательности, который МЫ отправим приложению
    var ourSeq: Long = 0

    // clientSeqNext - следующий байт, который мы ОЖИДАЕМ от приложения
    var clientSeqNext: Long = 0

    // Управление окном (flow control): сколько байт мы уже отправили, но
    // приложение их ещё не подтвердило (highestAckedByClient), и какое
    // окно приёма оно сейчас рекламирует (clientWindow). Не даём себе
    // отправить больше, чем клиент готов принять — иначе он просто
    // отбросит лишнее, а мы решим, что всё доставлено.
    var highestAckedByClient: Long = 0
    @Volatile var clientWindow: Int = 65535

    var relayJob: Job? = null
    @Volatile var lastActivityMs: Long = System.currentTimeMillis()

    // Честный half-close вместо мгновенного закрытия по первому FIN:
    // сессия закрывается полностью только когда ОБЕ стороны закончили.
    var appSentFin = false   // приложение прислало нам FIN
    var weSentFin = false    // мы прислали приложению FIN (потому что сервер закрылся)

    // Пробуем распознать SNI только один раз - в первом пакете данных
    // от приложения (там обычно и лежит TLS ClientHello целиком).
    var sniChecked = false
}

class TcpForwarder(
    private val context: Context,
    private val tunOutput: FileOutputStream
) {
    private val sessions = ConcurrentHashMap<TcpSessionKey, TcpSession>()
    private val forwarderScope = CoroutineScope(Dispatchers.IO)

    // @param tcpSegmentBytes сырые байты, начиная с TCP-заголовка (после IP-заголовка)
    // @param packageName пакет приложения-инициатора, если удалось определить ("" если нет)
    fun handle(clientIp: String, serverIp: String, tcpSegmentBytes: ByteArray, packageName: String) {
        val tcp = TcpPacketParser.parse(tcpSegmentBytes) ?: return
        val key = TcpSessionKey(clientIp, tcp.sourcePort, serverIp, tcp.destPort)

        when {
            TcpFlags.has(tcp.flags, TcpFlags.RST) -> {
                Log.d(TAG, "Получили RST для $key")
                closeSession(key)
            }

            TcpFlags.has(tcp.flags, TcpFlags.SYN) && !TcpFlags.has(tcp.flags, TcpFlags.ACK) -> {
                if (!sessions.containsKey(key)) startNewConnection(key, tcp, packageName)
                // повторный SYN (ретрансмит из-за задержки нашего SYN-ACK) - игнорируем
            }

            else -> {
                val session = sessions[key] ?: return // сегмент для неизвестной/уже закрытой сессии
                handleSegment(session, tcp)
            }
        }
    }

    private fun startNewConnection(key: TcpSessionKey, tcp: TcpSegment, packageName: String) {
        Log.d(TAG, "Новый SYN: $key ($packageName)")
        val socket = Socket()
        val session = TcpSession(key, socket, packageName)
        session.clientSeqNext = (tcp.sequenceNumber + 1) and 0xFFFFFFFFL
        session.ourSeq = Random.nextInt().toLong() and 0xFFFFFFFFL
        session.highestAckedByClient = session.ourSeq
        sessions[key] = session

        forwarderScope.launch {
            try {
                val network = UnderlyingNetworkProvider.find(context)
                if (network == null) {
                    Log.w(TAG, "Не нашли не-VPN сеть для $key - нет доступа в интернет")
                    sessions.remove(key)
                    return@launch
                }
                network.bindSocket(socket)
                socket.connect(InetSocketAddress(key.serverIp, key.serverPort), 5000)
                Log.d(TAG, "Подключились к ${key.serverIp}:${key.serverPort}")
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось подключиться к ${key.serverIp}:${key.serverPort}: ${e.message}")
                sessions.remove(key)
                return@launch
            }

            // Отвечаем приложению нашим SYN-ACK с опцией MSS, завершая нашу половину handshake
            writePacket(
                TcpPacketBuilder.build(
                    sourceIp = InetAddress.getByName(key.serverIp), sourcePort = key.serverPort,
                    destIp = InetAddress.getByName(key.clientIp), destPort = key.clientPort,
                    seq = session.ourSeq, ack = session.clientSeqNext,
                    flags = TcpFlags.SYN or TcpFlags.ACK, window = 65535, payload = ByteArray(0),
                    options = TcpPacketBuilder.mssOption()
                )
            )
            Log.d(TAG, "Отправили SYN-ACK для $key")
            session.ourSeq += 1
            session.highestAckedByClient = session.ourSeq

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
                    if (n <= 0) {
                        Log.d(TAG, "Сервер закрыл соединение ${session.key}, шлём FIN")
                        sendFin(session)
                        session.weSentFin = true
                        maybeFullyClose(session)
                        break
                    }
                    // Ждём, пока в окне клиента не появится место — это и есть
                    // управление потоком: не заваливаем приложение быстрее,
                    // чем оно готово принимать.
                    waitForWindowSpace(session, n)
                    sendData(session, buffer.copyOfRange(0, n))
                    session.lastActivityMs = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                Log.d(TAG, "relayServerResponses завершился для ${session.key}: ${e.message}")
            }
        }
    }

    /** Простое (не идеальное) уважение окна: ждём, пока не отправленных-но-не-подтверждённых байт станет меньше окна. */
    private suspend fun waitForWindowSpace(session: TcpSession, incomingSize: Int) {
        var waited = 0
        while (currentCoroutineContext().isActive) {
            val inFlight = seqDiff(session.ourSeq, session.highestAckedByClient)
            val window = session.clientWindow.coerceAtLeast(1) // окно 0 означало бы вечное ожидание
            if (inFlight + incomingSize <= window || waited >= 2000) return
            delay(20)
            waited += 20
        }
    }

    private fun handleSegment(session: TcpSession, tcp: TcpSegment) {
        session.lastActivityMs = System.currentTimeMillis()
        session.clientWindow = tcp.window

        if (session.state == TcpState.SYN_RECEIVED && TcpFlags.has(tcp.flags, TcpFlags.ACK)) {
            session.state = TcpState.ESTABLISHED
            Log.d(TAG, "Соединение ${session.key} перешло в ESTABLISHED")
        }

        if (TcpFlags.has(tcp.flags, TcpFlags.ACK)) {
            // Обновляем "самый свежий" ack, который прислал клиент — но только
            // если он действительно новее (учитывая переполнение 32-битного счётчика)
            if (seqDiff(tcp.ackNumber, session.highestAckedByClient) > 0) {
                session.highestAckedByClient = tcp.ackNumber
            }
        }

        if (tcp.payload.isNotEmpty()) {
            if (!session.sniChecked) {
                session.sniChecked = true
                val domain = TlsSniParser.extractSni(tcp.payload)
                if (domain != null) {
                    Log.d(TAG, "SNI для ${session.key}: $domain")
                    ConnectionLog.recordDomain(session.key.serverIp, session.key.serverPort, domain)
                }
            }
            try {
                session.socket.getOutputStream().write(tcp.payload)
                session.clientSeqNext = (session.clientSeqNext + tcp.payload.size) and 0xFFFFFFFFL
                sendAck(session)
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
            session.appSentFin = true
            try {
                session.socket.shutdownOutput() // half-close: сообщаем реальному серверу, что данных больше не будет
            } catch (e: Exception) {
                // сокет уже мог закрыться с другой стороны — не критично
            }
            maybeFullyClose(session)
        }
    }

    /** Полностью закрываем сессию только когда закончили ОБЕ стороны, а не при первом FIN. */
    private fun maybeFullyClose(session: TcpSession) {
        if (session.appSentFin && session.weSentFin) {
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

    // Закрывает ВСЕ активные сессии указанного пакета - вызывается сразу
    // при включении блокировки, чтобы разорвать уже открытые соединения.
    fun closeSessionsForPackage(packageName: String) {
        val toClose = sessions.entries.filter { it.value.packageName == packageName }
        toClose.forEach { (key, session) ->
            Log.d(TAG, "Закрываем сессию $key - приложение $packageName заблокировано")
            sessions.remove(key)
            session.relayJob?.cancel()
            try { session.socket.close() } catch (e: Exception) { }
        }
    }

    // Закрывает соединения, которые молчат дольше timeoutMs - вызывать периодически.
    // Это же ловит сессии, застрявшие в half-close, если вторая сторона так и не закрылась.
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

    /** Разница a-b для 32-битных (с переполнением) номеров последовательности, знаковая. */
    private fun seqDiff(a: Long, b: Long): Long {
        var diff = (a - b) and 0xFFFFFFFFL
        if (diff > 0x7FFFFFFFL) diff -= 0x100000000L
        return diff
    }
}