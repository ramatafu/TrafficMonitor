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
    var blocked: Boolean = false
) {
    val totalBytes: Long get() = bytesSent + bytesReceived
    val isTracker: Boolean get() = domain?.let { TrackerDomains.isTracker(it) } ?: false
}

/**
 * Журнал соединений, сгруппированный по (приложение, назначение, порт).
 * Сами соединения не переживают перезапуск (это текущая сессия монитора),
 * а вот известные домены и список блокировок — уже в Room, см.
 * KnownDomainsStore и BlockListStore.
 */
object ConnectionLog {

    private val entries = LinkedHashMap<String, ConnectionEntry>()
    private val _state = MutableStateFlow<List<ConnectionEntry>>(emptyList())
    val state = _state.asStateFlow()

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
        val key = "$appLabel|$destIp|$destPort|$protocol"
        val entry = entries.getOrPut(key) {
            ConnectionEntry(
                appLabel = appLabel,
                packageName = packageName,
                destIp = destIp,
                destPort = destPort,
                protocol = protocol,
                domain = KnownDomainsStore.get(destIp, destPort)
            )
        }
        entry.bytesSent += sentDelta
        entry.bytesReceived += receivedDelta
        if (newSession) entry.sessionCount += 1
        entry.blocked = blocked
        entry.lastActivityMs = System.currentTimeMillis()

        _state.value = entries.values.sortedByDescending { it.totalBytes }
    }

    /** Помечает соединение заблокированным — без изменения счётчиков байт/сессий. */
    fun markBlocked(appLabel: String, packageName: String, destIp: String, destPort: Int, protocol: String) {
        record(appLabel, packageName, destIp, destPort, protocol, blocked = true)
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
            _state.value = entries.values.sortedByDescending { it.totalBytes }
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
