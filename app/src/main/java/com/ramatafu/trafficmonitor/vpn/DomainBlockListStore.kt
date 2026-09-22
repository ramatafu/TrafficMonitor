package com.ramatafu.trafficmonitor.vpn

import android.content.Context
import com.ramatafu.trafficmonitor.data.AppDatabase
import com.ramatafu.trafficmonitor.data.BlockedDomainDao
import com.ramatafu.trafficmonitor.data.BlockedDomainEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Список заблокированных ПО ДОМЕНУ адресов — отдельно от блокировки по
 * приложению (BlockListStore). Заблокировать домен можно тапом по строке
 * трекера в списке соединений; действует на ВСЕ приложения сразу.
 *
 * Упрощение: блокировка вступает в силу для следующих попыток соединения
 * (DNS-запрос или новый TLS ClientHello с этим SNI) — уже открытую сессию
 * этим способом мгновенно не убить, поскольку домен не привязан к
 * конкретной TCP-сессии до тех пор, пока мы его не увидели. На практике
 * трекеры переподключаются очень часто, так что это не критично.
 */
object DomainBlockListStore {
    private lateinit var dao: BlockedDomainDao
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _blockedDomains = MutableStateFlow<Set<String>>(emptySet())
    val blockedDomains = _blockedDomains.asStateFlow()

    fun init(context: Context) {
        if (::dao.isInitialized) return
        dao = AppDatabase.getInstance(context).blockedDomainDao()

        storeScope.launch {
            dao.observeAll().collect { domains ->
                _blockedDomains.value = domains.toSet()
            }
        }
    }

    /** true, если домен явно заблокирован или является поддоменом заблокированного. */
    fun isBlocked(domain: String): Boolean {
        val lower = domain.lowercase()
        return _blockedDomains.value.any { blocked -> lower == blocked || lower.endsWith(".$blocked") }
    }

    fun setBlocked(domain: String, blocked: Boolean) {
        val lower = domain.lowercase()
        val current = _blockedDomains.value.toMutableSet()
        if (blocked) current.add(lower) else current.remove(lower)
        _blockedDomains.value = current

        if (::dao.isInitialized) {
            storeScope.launch {
                if (blocked) dao.insert(BlockedDomainEntity(lower)) else dao.delete(lower)
            }
        }
    }
}
