package com.ramatafu.trafficmonitor.vpn

import android.content.Context
import com.ramatafu.trafficmonitor.data.AppDatabase
import com.ramatafu.trafficmonitor.data.BlockedAppDao
import com.ramatafu.trafficmonitor.data.BlockedAppEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Список заблокированных пакетов — хранится в Room, переживает перезапуск
 * сервиса и самого приложения. Наружу отдаём тот же интерфейс, что был
 * с SharedPreferences (isBlocked/setBlocked/blockedPackages), так что
 * LocalVpnService и AppListActivity менять не нужно.
 */
object BlockListStore {
    private lateinit var dao: BlockedAppDao
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _blockedPackages = MutableStateFlow<Set<String>>(emptySet())
    val blockedPackages = _blockedPackages.asStateFlow()

    fun init(context: Context) {
        if (::dao.isInitialized) return
        dao = AppDatabase.getInstance(context).blockedAppDao()

        // Держим StateFlow синхронизированным с базой — любые изменения
        // (в том числе из другого места приложения) сразу видны везде.
        storeScope.launch {
            dao.observeAll().collect { packages ->
                _blockedPackages.value = packages.toSet()
            }
        }
    }

    fun isBlocked(packageName: String): Boolean = _blockedPackages.value.contains(packageName)

    fun setBlocked(packageName: String, blocked: Boolean) {
        // Оптимистично обновляем StateFlow сразу, не дожидаясь записи в базу —
        // блокировка должна сработать мгновенно, а не через асинхронную задержку.
        val current = _blockedPackages.value.toMutableSet()
        if (blocked) current.add(packageName) else current.remove(packageName)
        _blockedPackages.value = current

        if (::dao.isInitialized) {
            storeScope.launch {
                if (blocked) {
                    dao.insert(BlockedAppEntity(packageName))
                } else {
                    dao.delete(packageName)
                }
            }
        }
    }
}
