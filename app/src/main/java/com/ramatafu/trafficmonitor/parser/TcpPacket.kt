package com.ramatafu.trafficmonitor.parser

/**
 * Разбор TCP-заголовка. В отличие от UDP, тут важны номера
 * последовательности (seq/ack) и флаги — именно они управляют
 * состоянием TCP-соединения (handshake, закрытие и т.д.).
 */
data class TcpSegment(
    val sourcePort: Int,
    val destPort: Int,
    val sequenceNumber: Long, // 32-битное unsigned значение, храним в Long
    val ackNumber: Long,
    val flags: Int,
    val window: Int,
    val payload: ByteArray
)

object TcpFlags {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    fun has(flags: Int, flag: Int) = (flags and flag) != 0
}

object TcpPacketParser {

    /** @param segment сырые байты, начиная с TCP-заголовка (после IP-заголовка) */
    fun parse(segment: ByteArray): TcpSegment? {
        if (segment.size < 20) return null // короче минимального TCP-заголовка

        val srcPort = ((segment[0].toInt() and 0xFF) shl 8) or (segment[1].toInt() and 0xFF)
        val dstPort = ((segment[2].toInt() and 0xFF) shl 8) or (segment[3].toInt() and 0xFF)
        val seq = readUInt32(segment, 4)
        val ack = readUInt32(segment, 8)

        val dataOffsetByte = segment[12].toInt() and 0xFF
        val headerLength = ((dataOffsetByte shr 4) and 0x0F) * 4 // data offset хранится в 4-битных словах
        if (segment.size < headerLength) return null

        val flags = segment[13].toInt() and 0x3F // младшие 6 бит: FIN,SYN,RST,PSH,ACK,URG
        val window = ((segment[14].toInt() and 0xFF) shl 8) or (segment[15].toInt() and 0xFF)

        val payload = if (segment.size > headerLength) {
            segment.copyOfRange(headerLength, segment.size)
        } else {
            ByteArray(0)
        }

        return TcpSegment(srcPort, dstPort, seq, ack, flags, window, payload)
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }
}
