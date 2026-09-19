package com.ramatafu.trafficmonitor.vpn.nat

import com.ramatafu.trafficmonitor.parser.TcpFlags
import java.net.InetAddress

/**
 * Собирает IPv4+TCP пакет вручную. В отличие от UDP, тут в заголовке
 * есть seq/ack и флаги — они и определяют, как удалённая система (или
 * само устройство) интерпретирует пакет: SYN-ACK при подключении,
 * ACK при подтверждении данных, FIN при закрытии и т.д.
 */
object TcpPacketBuilder {

    private const val TCP_HEADER_LENGTH = 20 // без опций — для MVP этого достаточно

    fun build(
        sourceIp: InetAddress, sourcePort: Int,
        destIp: InetAddress, destPort: Int,
        seq: Long, ack: Long, flags: Int, window: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLength = 20 + TCP_HEADER_LENGTH + payload.size
        val packet = ByteArray(totalLength)

        // --- IPv4 заголовок ---
        packet[0] = 0x45
        packet[1] = 0
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0; packet[5] = 0
        packet[6] = 0x40.toByte(); packet[7] = 0
        packet[8] = 64
        packet[9] = 6 // протокол TCP
        packet[10] = 0; packet[11] = 0

        System.arraycopy(sourceIp.address, 0, packet, 12, 4)
        System.arraycopy(destIp.address, 0, packet, 16, 4)

        val ipChecksum = ChecksumUtils.checksum(packet, 0, 20)
        packet[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // --- TCP заголовок ---
        val tcpOffset = 20
        packet[tcpOffset] = ((sourcePort shr 8) and 0xFF).toByte()
        packet[tcpOffset + 1] = (sourcePort and 0xFF).toByte()
        packet[tcpOffset + 2] = ((destPort shr 8) and 0xFF).toByte()
        packet[tcpOffset + 3] = (destPort and 0xFF).toByte()

        writeUInt32(packet, tcpOffset + 4, seq)
        writeUInt32(packet, tcpOffset + 8, ack)

        packet[tcpOffset + 12] = (((TCP_HEADER_LENGTH / 4) shl 4) and 0xF0).toByte() // data offset, без опций
        packet[tcpOffset + 13] = (flags and 0x3F).toByte()
        packet[tcpOffset + 14] = ((window shr 8) and 0xFF).toByte()
        packet[tcpOffset + 15] = (window and 0xFF).toByte()
        packet[tcpOffset + 16] = 0; packet[tcpOffset + 17] = 0 // checksum — посчитаем ниже
        packet[tcpOffset + 18] = 0; packet[tcpOffset + 19] = 0 // urgent pointer — не используем

        System.arraycopy(payload, 0, packet, tcpOffset + TCP_HEADER_LENGTH, payload.size)

        val tcpSegment = packet.copyOfRange(tcpOffset, totalLength)
        val tcpChecksum = ChecksumUtils.tcpChecksumWithPseudoHeader(
            sourceIp.address, destIp.address, tcpSegment
        )
        packet[tcpOffset + 16] = ((tcpChecksum shr 8) and 0xFF).toByte()
        packet[tcpOffset + 17] = (tcpChecksum and 0xFF).toByte()

        return packet
    }

    /** Удобные шорткаты для частых комбинаций флагов. */
    fun synAck(sourceIp: InetAddress, sourcePort: Int, destIp: InetAddress, destPort: Int, seq: Long, ack: Long) =
        build(sourceIp, sourcePort, destIp, destPort, seq, ack, TcpFlags.SYN or TcpFlags.ACK, 65535, ByteArray(0))

    private fun writeUInt32(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = ((value shr 24) and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 3] = (value and 0xFF).toByte()
    }
}
