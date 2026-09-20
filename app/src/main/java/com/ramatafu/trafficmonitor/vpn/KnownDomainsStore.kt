package com.ramatafu.trafficmonitor.vpn

import android.content.Context
import com.ramatafu.trafficmonitor.data.AppDatabase
import com.ramatafu.trafficmonitor.data.KnownDomainDao
import com.ramatafu.trafficmonitor.data.KnownDomainEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Домен, однажды увиденный через SNI для конкретного ip:port, запоминается
 * навсегда (пока не переустановишь приложение) — так что даже если в этом
 * сеансе TLS ClientHello почему-то не поймали, домен всё равно покажется.
 */
object KnownDomainsStore {
    private lateinit var dao: KnownDomainDao
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ключ "ip:port" -> домен. Кэш в памяти, чтобы не ходить в базу на каждый пакет.
    private val cache = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        if (::dao.isInitialized) return
        dao = AppDatabase.getInstance(context).knownDomainDao()

        storeScope.launch {
            dao.getAll().forEach { entity ->
                cache["${entity.ip}:${entity.port}"] = entity.domain
            }
        }
    }

    fun get(ip: String, port: Int): String? = cache["$ip:$port"]

    fun record(ip: String, port: Int, domain: String) {
        val key = "$ip:$port"
        if (cache.put(key, domain) != domain) { // новое значение или изменилось — сохраняем
            if (::dao.isInitialized) {
                storeScope.launch {
                    dao.insert(KnownDomainEntity(ip, port, domain))
                }
            }
        }
    }
}
