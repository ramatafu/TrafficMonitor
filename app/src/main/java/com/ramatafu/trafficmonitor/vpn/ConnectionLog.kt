package com.ramatafu.trafficmonitor.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConnectionEntry(
    val appLabel: String,
    val packageName: String, // "" - если UID не удалось определить
    val destIp: String,
    val destPort: Int,
    val protocol: String,
    var bytesSent: Long = 0,      // от приложения к серверу (upload)
    var bytesReceived: Long = 0,  // от сервера к приложению (download)
    var sessionCount: Int = 0,    // сколько раз открывалось новое соединение на этот адрес
    var lastActivityMs: Long = System.currentTimeMillis(),
    var domain: String? = null,   // заполняется из KnownDomainsStore либо когда поймаем SNI
    var blocked: Boolean = false,
    var blockedAttempts: Int = 0  // сколько раз приложение пыталось достучаться уже ПОСЛЕ блокировки
) {
    val totalBytes: Long get() = bytesSent + bytesReceived
    val isTracker: Boolean get() = domain?.let { TrackerDomains.isTracker(it) } ?: false
}

/**
 * Журнал соединений, сгруппированный по (приложение, назначение, порт).
 * Сами соединения не переживают перезапуск (это текущая сессия монитора),
 * а вот известные домены и список блокировок — уже в Room, см.
 * KnownDomainsStore и BlockListStore.
 *
 * Важный нюанс группировки: один и тот же домен (особенно за CDN) может
 * резолвиться в разные IP от раза к разу. Поэтому ключ строится не по
 * голому IP, а по домену, если он уже известен — а когда домен становится
 * известен ПОЗЖЕ (поймали SNI в середине сессии, которая начиналась как
 * запись по IP), существующая запись переагрегируется под доменный ключ
 * (см. recordDomain). Без этого в списке появлялись бы дубли вроде
 * "site.com" и "1.2.3.4" за один и тот же реальный сервис.
 */
object ConnectionLog {

    private val entries = LinkedHashMap<String, ConnectionEntry>()
    private val _state = MutableStateFlow<List<ConnectionEntry>>(emptyList())
    val state = _state.asStateFlow()

    private fun keyFor(appLabel: String, destPart: String, destPort: Int, protocol: String) =
        "$appLabel|$destPart|$destPort|$protocol"

    /**
     * Единая точка записи: и форвардеры (реальные отправленные/полученные байты,
     * новые сессии), и LocalVpnService (пометка "заблокировано") пишут через неё.
     * Дельты по умолчанию нулевые — вызывающий передаёт только то, что изменилось.
     */
    @Synchronized
    fun record(
        appLabel: String, packageName: String,
        destIp: String, destPort: Int, protocol: String,
        blocked: Boolean = false,
        sentDelta: Long = 0, receivedDelta: Long = 0,
        newSession: Boolean = false
    ) {
        val knownDomain = KnownDomainsStore.get(destIp, destPort)
        val key = keyFor(appLabel, knownDomain ?: destIp, destPort, protocol)
        val entry = entries.getOrPut(key) {
            ConnectionEntry(
                appLabel = appLabel,
                packageName = packageName,
                destIp = destIp,
                destPort = destPort,
                protocol = protocol,
                domain = knownDomain
            )
        }
        entry.bytesSent += sentDelta
        entry.bytesReceived += receivedDelta
        if (newSession) entry.sessionCount += 1
        entry.blocked = blocked
        entry.lastActivityMs = System.currentTimeMillis()

        _state.value = entries.values.sortedByDescending { it.totalBytes }
    }

    /** Помечает соединение заблокированным и увеличивает счётчик попыток — без изменения байт/сессий. */
    @Synchronized
    fun markBlocked(appLabel: String, packageName: String, destIp: String, destPort: Int, protocol: String) {
        val knownDomain = KnownDomainsStore.get(destIp, destPort)
        val key = keyFor(appLabel, knownDomain ?: destIp, destPort, protocol)
        val entry = entries.getOrPut(key) {
            ConnectionEntry(
                appLabel = appLabel, packageName = packageName,
                destIp = destIp, destPort = destPort, protocol = protocol,
                domain = knownDomain
            )
        }
        entry.blocked = true
        entry.blockedAttempts += 1
        entry.lastActivityMs = System.currentTimeMillis()
        _state.value = entries.values.sortedByDescending { it.totalBytes }
    }

    /**
     * Вызывается форвардером, когда удалось вытащить домен из SNI.
     * Запоминаем домен в KnownDomainsStore для будущих сессий, и — если
     * запись на этот ip:port существовала ещё без домена (ключ был по IP) —
     * переносим её накопленные байты/сессии под новый ключ по домену,
     * сливая с уже существующей записью на этот домен, если она есть
     * (именно так лечится дубль "site.com" / "1.2.3.4" для одного сервиса).
     */
    @Synchronized
    fun recordDomain(destIp: String, destPort: Int, domain: String) {
        KnownDomainsStore.record(destIp, destPort, domain)

        val toMigrate = entries.entries
            .filter { (_, e) -> e.destIp == destIp && e.destPort == destPort && e.protocol == "TCP" && e.domain == null }
            .toList()

        if (toMigrate.isEmpty()) return

        toMigrate.forEach { (oldKey, entry) ->
            entries.remove(oldKey)
            entry.domain = domain
            val newKey = keyFor(entry.appLabel, domain, entry.destPort, entry.protocol)
            val existing = entries[newKey]
            if (existing != null) {
                existing.bytesSent += entry.bytesSent
                existing.bytesReceived += entry.bytesReceived
                existing.sessionCount += entry.sessionCount
                existing.lastActivityMs = maxOf(existing.lastActivityMs, entry.lastActivityMs)
                existing.blocked = existing.blocked || entry.blocked
            } else {
                entries[newKey] = entry
            }
        }
        _state.value = entries.values.sortedByDescending { it.totalBytes }
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
