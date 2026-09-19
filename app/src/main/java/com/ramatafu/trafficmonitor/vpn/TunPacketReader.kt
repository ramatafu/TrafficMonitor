package com.ramatafu.trafficmonitor.vpn

import android.util.Log
import com.ramatafu.trafficmonitor.parser.IpPacketParser
import com.ramatafu.trafficmonitor.parser.ParsedPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.IOException

private const val TAG = "TunPacketReader"

/**
 * Читает сырые байты из tun-интерфейса в отдельной корутине и парсит заголовок
 * каждого IP-пакета. Разбор TCP/UDP-заголовков и форвардинг — уже забота
 * вызывающего кода (UdpForwarder/TcpForwarder), этот класс их не касается.
 */
class TunPacketReader(
    private val input: FileInputStream,
    // transportSegment — всё, что идёт после IP-заголовка (TCP- или UDP-заголовок + данные).
    // Разбор конкретного протокола теперь на стороне вызывающего кода (форвардеров),
    // TunPacketReader сам протокол не трогает.
    private val onPacket: (packet: ParsedPacket, transportSegment: ByteArray) -> Unit
) {
    private var job: Job? = null
    private val buffer = ByteArray(32 * 1024) // с запасом на MTU

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val length = input.read(buffer)
                    if (length <= 0) continue

                    val parsed = IpPacketParser.parse(buffer, length)
                    if (parsed != null) {
                        val transportSegment = if (parsed.ipHeaderLength < length) {
                            buffer.copyOfRange(parsed.ipHeaderLength, length)
                        } else {
                            ByteArray(0)
                        }
                        onPacket(parsed, transportSegment)
                    }
                } catch (e: IOException) {
                    // возникает при остановке VPN (interface закрывается) — это нормальное завершение
                    Log.d(TAG, "Tun read stopped: ${e.message}")
                    break
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}
