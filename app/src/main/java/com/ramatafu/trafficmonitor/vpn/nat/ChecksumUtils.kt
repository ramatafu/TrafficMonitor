package com.ramatafu.trafficmonitor.vpn.nat

/**
 * IP и UDP используют один и тот же алгоритм контрольной суммы —
 * дополнение до единицы суммы 16-битных слов. Без правильной суммы
 * система (и удалённая сторона) молча отбросит собранный вручную пакет.
 */
object ChecksumUtils {

    /** Считает интернет-чексумму (RFC 1071) для произвольного диапазона байт. */
    fun checksum(data: ByteArray, offset: Int, length: Int, initial: Long = 0L): Int {
        var sum = initial
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) { // нечётная длина — последний байт дополняется нулём
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.toInt().inv() and 0xFFFF
    }

    /**
     * UDP-чексумма считается не только по UDP-заголовку и данным,
     * но и по «псевдозаголовку» (src/dst IP, протокол, длина) —
     * так UDP защищается от подмены адресов на пути.
     */
    fun udpChecksumWithPseudoHeader(
        srcIp: ByteArray, dstIp: ByteArray, udpSegment: ByteArray, udpLength: Int
    ): Int {
        val result = transportChecksumWithPseudoHeader(srcIp, dstIp, protocol = 17, udpSegment)
        return if (result == 0) 0xFFFF else result // 0 зарезервирован как "чексумма не считалась"
    }

    /** То же самое, но для TCP (номер протокола 6). В TCP, в отличие от UDP, 0 — не особый случай. */
    fun tcpChecksumWithPseudoHeader(
        srcIp: ByteArray, dstIp: ByteArray, tcpSegment: ByteArray
    ): Int {
        return transportChecksumWithPseudoHeader(srcIp, dstIp, protocol = 6, tcpSegment)
    }

    private fun transportChecksumWithPseudoHeader(
        srcIp: ByteArray, dstIp: ByteArray, protocol: Int, segment: ByteArray
    ): Int {
        val pseudoHeader = ByteArray(12 + segment.size + (segment.size % 2))
        System.arraycopy(srcIp, 0, pseudoHeader, 0, 4)
        System.arraycopy(dstIp, 0, pseudoHeader, 4, 4)
        pseudoHeader[8] = 0
        pseudoHeader[9] = protocol.toByte()
        pseudoHeader[10] = ((segment.size shr 8) and 0xFF).toByte()
        pseudoHeader[11] = (segment.size and 0xFF).toByte()
        System.arraycopy(segment, 0, pseudoHeader, 12, segment.size)

        return checksum(pseudoHeader, 0, pseudoHeader.size)
    }
}
