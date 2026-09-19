package com.ramatafu.trafficmonitor.parser

import java.net.InetAddress

/**
 * Минимальный разбор IPv4-заголовка + TCP/UDP портов.
 * Достаточно для MVP: понять, кто с кем и по какому протоколу соединяется.
 * IPv6 и опции IP-заголовка сознательно не поддержаны на этом этапе.
 */
data class ParsedPacket(
    val sourceIp: String,
    val destIp: String,
    val protocol: Protocol,
    val sourcePort: Int,
    val destPort: Int,
    val totalLength: Int,
    val ipHeaderLength: Int // нужно, чтобы найти начало полезной нагрузки (payload)
)

enum class Protocol(val number: Int) {
    TCP(6), UDP(17), OTHER(-1);

    companion object {
        fun fromNumber(n: Int) = values().firstOrNull { it.number == n } ?: OTHER
    }
}

object IpPacketParser {

    /**
     * @param buffer сырые байты, прочитанные из tun-интерфейса
     * @param length сколько байт реально прочитано (read() может вернуть буфер длиннее данных)
     */
    fun parse(buffer: ByteArray, length: Int): ParsedPacket? {
        if (length < 20) return null // короче минимального IPv4-заголовка

        val versionAndIhl = buffer[0].toInt() and 0xFF
        val version = versionAndIhl shr 4
        if (version != 4) return null // IPv6 — отдельный парсер, пока пропускаем

        val ihl = (versionAndIhl and 0x0F) * 4 // длина заголовка в байтах
        if (length < ihl) return null

        val totalLength = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
        val protocolNumber = buffer[9].toInt() and 0xFF
        val protocol = Protocol.fromNumber(protocolNumber)

        val srcIp = ipToString(buffer, 12)
        val dstIp = ipToString(buffer, 16)

        var srcPort = 0
        var dstPort = 0

        // TCP и UDP оба хранят порты в первых 4 байтах после IP-заголовка
        if ((protocol == Protocol.TCP || protocol == Protocol.UDP) && length >= ihl + 4) {
            srcPort = ((buffer[ihl].toInt() and 0xFF) shl 8) or (buffer[ihl + 1].toInt() and 0xFF)
            dstPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)
        }

        return ParsedPacket(srcIp, dstIp, protocol, srcPort, dstPort, totalLength, ihl)
    }

    private fun ipToString(buffer: ByteArray, offset: Int): String {
        return InetAddress.getByAddress(
            byteArrayOf(buffer[offset], buffer[offset + 1], buffer[offset + 2], buffer[offset + 3])
        ).hostAddress ?: "0.0.0.0"
    }
}
