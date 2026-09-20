package com.ramatafu.trafficmonitor.parser

// SNI (Server Name Indication) - это поле в TLS ClientHello, где браузер
// открытым текстом указывает домен, к которому подключается (нужно серверу,
// чтобы выбрать нужный TLS-сертификат при shared hosting). Именно поэтому
// даже в HTTPS-трафике домен назначения виден без расшифровки - этим
// пользуются как раз DPI-системы и родительские фильтры, и этим же
// воспользуемся мы, чтобы показать домен вместо голого IP.
//
// Структура TLS record (упрощённо, только то, что нужно для ClientHello):
// type(1), version(2), length(2), затем handshake type(1), length(3),
// client version(2), random(32), session id, cipher suites, compression,
// extensions... - ищем extension type 0x0000 (server_name)
object TlsSniParser {

    fun extractSni(data: ByteArray): String? {
        return try {
            parse(data)
        } catch (e: Exception) {
            // Любая неожиданная структура (не TLS, обрезанный пакет и т.п.) -
            // просто не считаем домен, а не роняем форвардинг.
            null
        }
    }

    private fun parse(data: ByteArray): String? {
        if (data.size < 6) return null
        if (data[0] != 0x16.toByte()) return null // не TLS handshake record
        if (data[5] != 0x01.toByte()) return null // не ClientHello

        var pos = 5 + 4 // TLS record header (5) плюс handshake header (тип и длина, 4)
        pos += 2   // client version
        pos += 32  // random

        if (pos >= data.size) return null
        val sessionIdLen = data[pos].toInt() and 0xFF
        pos += 1 + sessionIdLen

        if (pos + 2 > data.size) return null
        val cipherSuitesLen = readUInt16(data, pos)
        pos += 2 + cipherSuitesLen

        if (pos + 1 > data.size) return null
        val compressionLen = data[pos].toInt() and 0xFF
        pos += 1 + compressionLen

        if (pos + 2 > data.size) return null
        val extensionsLen = readUInt16(data, pos)
        pos += 2
        val extensionsEnd = (pos + extensionsLen).coerceAtMost(data.size)

        while (pos + 4 <= extensionsEnd) {
            val extType = readUInt16(data, pos)
            val extLen = readUInt16(data, pos + 2)
            pos += 4

            if (extType == 0x0000) { // server_name extension
                return parseServerNameExtension(data, pos, extLen)
            }
            pos += extLen
        }
        return null
    }

    private fun parseServerNameExtension(data: ByteArray, offset: Int, length: Int): String? {
        // server_name_list: сначала длина списка (2 байта), затем записи:
        // тип(1), длина(2), имя
        var p = offset + 2 // пропускаем длину списка
        if (p + 3 > data.size) return null

        val nameType = data[p].toInt() and 0xFF
        val nameLen = readUInt16(data, p + 1)
        p += 3

        if (nameType != 0) return null // 0 = host_name, другие типы не определены в спецификации
        if (p + nameLen > data.size) return null

        return String(data, p, nameLen, Charsets.US_ASCII)
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }
}
