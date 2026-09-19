package com.ramatafu.trafficmonitor.vpn.nat

import java.net.InetAddress

/**
 * Когда ответ приходит из настоящего интернета, устройство ожидает
 * увидеть обычный IP-пакет "от исходного адресата". Поэтому мы
 * вручную собираем IPv4+UDP заголовки вокруг данных ответа.
 */
object UdpPacketBuilder {

    fun build(
        sourceIp: InetAddress, sourcePort: Int,
        destIp: InetAddress, destPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = 8 + payload.size
        val totalLength = 20 + udpLength
        val packet = ByteArray(totalLength)

        // --- IPv4 заголовок (20 байт, без опций) ---
        packet[0] = 0x45 // версия 4, IHL 5 (20 байт)
        packet[1] = 0    // DSCP/ECN — не используем
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0; packet[5] = 0 // identification — упрощаем, фрагментация не поддерживается
        packet[6] = 0x40.toByte(); packet[7] = 0 // флаг "не фрагментировать"
        packet[8] = 64 // TTL
        packet[9] = 17 // протокол UDP
        packet[10] = 0; packet[11] = 0 // checksum — посчитаем ниже

        System.arraycopy(sourceIp.address, 0, packet, 12, 4)
        System.arraycopy(destIp.address, 0, packet, 16, 4)

        val ipChecksum = ChecksumUtils.checksum(packet, 0, 20)
        packet[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // --- UDP заголовок (8 байт) ---
        val udpOffset = 20
        packet[udpOffset] = ((sourcePort shr 8) and 0xFF).toByte()
        packet[udpOffset + 1] = (sourcePort and 0xFF).toByte()
        packet[udpOffset + 2] = ((destPort shr 8) and 0xFF).toByte()
        packet[udpOffset + 3] = (destPort and 0xFF).toByte()
        packet[udpOffset + 4] = ((udpLength shr 8) and 0xFF).toByte()
        packet[udpOffset + 5] = (udpLength and 0xFF).toByte()
        packet[udpOffset + 6] = 0; packet[udpOffset + 7] = 0 // checksum — посчитаем ниже

        System.arraycopy(payload, 0, packet, udpOffset + 8, payload.size)

        val udpSegment = packet.copyOfRange(udpOffset, totalLength)
        val udpChecksum = ChecksumUtils.udpChecksumWithPseudoHeader(
            sourceIp.address, destIp.address, udpSegment, udpLength
        )
        packet[udpOffset + 6] = ((udpChecksum shr 8) and 0xFF).toByte()
        packet[udpOffset + 7] = (udpChecksum and 0xFF).toByte()

        return packet
    }
}
