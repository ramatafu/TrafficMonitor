package com.ramatafu.trafficmonitor.vpn

import com.ramatafu.trafficmonitor.parser.ParsedPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConnectionEntry(
    val appLabel: String,
    val packageName: String, // "" - если UID не удалось определить
    val destIp: String,
    val destPort: Int,
    val protocol: String,
    var bytes: Long,
    var packetCount: Int,
    var domain: String? = null, // заполняется сразу из KnownDomainsStore либо позже, когда поймаем SNI
    var blocked: Boolean = false
)

/**
 * Простой in-memory журнал, сгруппированный по (приложение, назначение, порт).
 * Сами соединения не переживают перезапуск (это просто текущая сессия),
 * а вот известные домены и список блокировок — уже в Room, см.
 * KnownDomainsStore и BlockListStore.
 */
object ConnectionLog {

    private val entries = LinkedHashMap<String, ConnectionEntry>()
    private val _state = MutableStateFlow<List<ConnectionEntry>>(emptyList())
    val state = _state.asStateFlow()

    @Synchronized
    fun record(packet: ParsedPacket, appLabel: String, packageName: String, blocked: Boolean) {
        val key = "$appLabel|${packet.destIp}|${packet.destPort}|${packet.protocol}"
        val existing = entries[key]
        if (existing != null) {
            existing.bytes += packet.totalLength
            existing.packetCount += 1
            existing.blocked = blocked
        } else {
            entries[key] = ConnectionEntry(
                appLabel = appLabel,
                packageName = packageName,
                destIp = packet.destIp,
                destPort = packet.destPort,
                protocol = packet.protocol.name,
                bytes = packet.totalLength.toLong(),
                packetCount = 1,
                domain = KnownDomainsStore.get(packet.destIp, packet.destPort),
                blocked = blocked
            )
        }
        _state.value = entries.values.sortedByDescending { it.bytes }
    }

    /**
     * Вызывается форвардером, когда удалось вытащить домен из SNI —
     * проставляем его во все TCP-записи на этот IP:порт (обычно она одна)
     * и запоминаем в KnownDomainsStore для будущих сессий.
     */
    @Synchronized
    fun recordDomain(destIp: String, destPort: Int, domain: String) {
        KnownDomainsStore.record(destIp, destPort, domain)
        var changed = false
        entries.values.forEach { entry ->
            if (entry.destIp == destIp && entry.destPort == destPort && entry.protocol == "TCP" && entry.domain == null) {
                entry.domain = domain
                changed = true
            }
        }
        if (changed) {
            _state.value = entries.values.sortedByDescending { it.bytes }
        }
    }

    /** Список уникальных приложений, которые уже засветились в трафике (для экрана блокировки). */
    @Synchronized
    fun distinctApps(): List<Pair<String, String>> {
        return entries.values
            .filter { it.packageName.isNotEmpty() }
            .map { it.packageName to it.appLabel }
            .distinct()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        _state.value = emptyList()
    }
}
