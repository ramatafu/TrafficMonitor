package com.ramatafu.trafficmonitor.vpn.nat

/**
 * Минимальный разбор DNS-запроса — нужно только имя домена из вопроса,
 * и сборка синтетического NXDOMAIN-ответа взамен реального DNS-сервера.
 * Формат DNS-сообщения: 12-байтный заголовок, затем секция вопроса
 * (имя из length-prefixed меток, тип, класс).
 */
object DnsSinkhole {

    /** Достаёт запрошенное имя из DNS-запроса, если получится его разобрать. */
    fun extractQueryName(payload: ByteArray): String? {
        if (payload.size < 12) return null
        var pos = 12
        val labels = mutableListOf<String>()
        while (pos < payload.size) {
            val len = payload[pos].toInt() and 0xFF
            pos += 1
            if (len == 0) break
            if (pos + len > payload.size) return null
            labels.add(String(payload, pos, len, Charsets.US_ASCII))
            pos += len
        }
        if (labels.isEmpty()) return null
        return labels.joinToString(".")
    }

    /**
     * Собирает ответ NXDOMAIN на основе исходного запроса (тот же ID и секция
     * вопроса, но с флагом "ответ" и кодом ошибки "домен не существует").
     * Приложение получает обычный отрицательный ответ DNS, как будто домена
     * просто не существует — без объяснений, что именно его заблокировали.
     */
    fun buildNxDomainResponse(query: ByteArray): ByteArray? {
        val questionEnd = questionSectionEnd(query) ?: return null
        val response = query.copyOfRange(0, questionEnd)

        response[2] = 0x81.toByte() // QR=1 (ответ), Opcode=0, AA=0, TC=0, RD=1
        response[3] = 0x83.toByte() // RA=1, RCODE=3 (NXDOMAIN)
        response[6] = 0; response[7] = 0   // ANCOUNT = 0
        response[8] = 0; response[9] = 0   // NSCOUNT = 0
        response[10] = 0; response[11] = 0 // ARCOUNT = 0

        return response
    }

    private fun questionSectionEnd(data: ByteArray): Int? {
        if (data.size < 12) return null
        var pos = 12
        while (pos < data.size) {
            val len = data[pos].toInt() and 0xFF
            pos += 1
            if (len == 0) break
            pos += len
        }
        pos += 4 // QTYPE(2) + QCLASS(2)
        return if (pos <= data.size) pos else null
    }
}
