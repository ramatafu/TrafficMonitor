package com.ramatafu.trafficmonitor.vpn

import com.ramatafu.trafficmonitor.parser.ParsedPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConnectionEntry(
    val appLabel: String,
    val destIp: String,
    val destPort: Int,
    val protocol: String,
    var bytes: Long,
    var packetCount: Int
)

/**
 * Простой in-memory журнал, сгруппированный по (приложение, назначение, порт).
 * На следующем этапе это стоит заменить на Room, чтобы данные переживали
 * перезапуск сервиса и можно было смотреть историю.
 */
object ConnectionLog {

    private val entries = LinkedHashMap<String, ConnectionEntry>()
    private val _state = MutableStateFlow<List<ConnectionEntry>>(emptyList())
    val state = _state.asStateFlow()

    @Synchronized
    fun record(packet: ParsedPacket, appLabel: String) {
        val key = "$appLabel|${packet.destIp}|${packet.destPort}|${packet.protocol}"
        val existing = entries[key]
        if (existing != null) {
            existing.bytes += packet.totalLength
            existing.packetCount += 1
        } else {
            entries[key] = ConnectionEntry(
                appLabel = appLabel,
                destIp = packet.destIp,
                destPort = packet.destPort,
                protocol = packet.protocol.name,
                bytes = packet.totalLength.toLong(),
                packetCount = 1
            )
        }
        _state.value = entries.values.sortedByDescending { it.bytes }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        _state.value = emptyList()
    }
}
