package com.ramatafu.trafficmonitor.resolver

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import androidx.annotation.RequiresApi
import java.net.InetSocketAddress

data class AppInfo(val uid: Int, val packageName: String, val label: String)

/**
 * Находит приложение, которое владеет соединением, по UID.
 * getConnectionOwnerUid доступен с API 29 (Android 10) и требует,
 * чтобы наше приложение само было активным VPN — иначе вернёт INVALID_UID.
 */
class AppResolver(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val packageManager = context.packageManager

    // кэш, чтобы не дёргать PackageManager на каждый пакет
    private val uidCache = HashMap<Int, AppInfo>()

    @RequiresApi(Build.VERSION_CODES.Q)
    fun resolveByPorts(
        protocol: Int, // например ProtocolInfo.getProtocolNumber("tcp") — тут просто передаём Protocol.TCP/UDP.number
        localAddress: InetSocketAddress,
        remoteAddress: InetSocketAddress
    ): AppInfo? {
        val uid = try {
            connectivityManager.getConnectionOwnerUid(protocol, localAddress, remoteAddress)
        } catch (e: SecurityException) {
            -1
        }

        if (uid < 0) return null // ConnectivityManager.INVALID_UID или ошибка

        return uidCache.getOrPut(uid) {
            val packageName = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "uid:$uid"
            val label = try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                packageName
            }
            AppInfo(uid, packageName, label)
        }
    }

    fun clearCache() = uidCache.clear()
}
