package com.ramatafu.trafficmonitor.vpn.nat

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * На некоторых устройствах/прошивках VpnService.protect() стабильно
 * возвращает false (подтверждено логами на нескольких телефонах), из-за
 * чего сокеты форвардеров сами утекают в наш же tun и никогда не
 * подключаются к реальному интернету.
 *
 * Рабочая альтернатива — явно найти "настоящую" (не-VPN) сеть через
 * ConnectivityManager и привязать к ней сокет через Network.bindSocket().
 * Это другой системный механизм, не зависящий от protect().
 */
object UnderlyingNetworkProvider {

    fun find(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network)
            caps != null &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
