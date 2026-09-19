package com.ramatafu.trafficmonitor.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ramatafu.trafficmonitor.parser.Protocol
import com.ramatafu.trafficmonitor.resolver.AppResolver
import com.ramatafu.trafficmonitor.vpn.nat.TcpForwarder
import com.ramatafu.trafficmonitor.vpn.nat.UdpForwarder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "LocalVpnService"
private const val NOTIFICATION_CHANNEL_ID = "traffic_monitor_channel"
private const val NOTIFICATION_ID = 1
const val ACTION_STOP_VPN = "com.ramatafu.trafficmonitor.STOP_VPN"

/**
 * MVP-версия: поднимает tun-интерфейс, читает пакеты, логирует их.
 * Пакеты НЕ форвардятся дальше — на этом этапе цель только увидеть,
 * какие приложения и куда стучатся. Реальный интернет на устройстве
 * при активном сервисе работать не будет (это добавится на Этапе 2).
 */
class LocalVpnService : VpnService() {

    companion object {
        // MainActivity наблюдает за этим, чтобы знать, показывать ли кнопку
        // "Запустить" или "Остановить" — без привязки (bind) к сервису
        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetReader: TunPacketReader? = null
    private var udpForwarder: UdpForwarder? = null
    private var tcpForwarder: TcpForwarder? = null
    private lateinit var appResolver: AppResolver
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appResolver = AppResolver(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_VPN) {
            Log.i(TAG, "Получена команда остановки")
            stopVpn()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(), foregroundServiceType())
        establishVpn()
        return START_STICKY
    }

    private fun establishVpn() {
        if (vpnInterface != null) return // уже запущен

        val builder = Builder()
            .setSession("Traffic Monitor")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)      // весь IPv4-трафик идёт через нас
            .addDnsServer("8.8.8.8")     // временно, чтобы DNS не падал в никуда
            .setMtu(1500)
            .setBlocking(true)

        vpnInterface = builder.establish()

        if (vpnInterface == null) {
            Log.e(TAG, "Не удалось создать VPN-интерфейс — возможно, разрешение не было дано")
            return
        }

        val input = FileInputStream(vpnInterface!!.fileDescriptor)
        val output = FileOutputStream(vpnInterface!!.fileDescriptor)
        udpForwarder = UdpForwarder(this, output)
        tcpForwarder = TcpForwarder(this, output)

        packetReader = TunPacketReader(input) { parsed, transportSegment ->
            val local = InetSocketAddress(parsed.sourceIp, parsed.sourcePort)
            val remote = InetSocketAddress(parsed.destIp, parsed.destPort)

            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appResolver.resolveByPorts(parsed.protocol.number, local, remote)
            } else null

            ConnectionLog.record(parsed, appInfo?.label ?: "Неизвестно (UID недоступен)")

            when (parsed.protocol) {
                Protocol.UDP -> {
                    // UDP-заголовок фиксированной длины 8 байт, дальше — полезная нагрузка
                    if (transportSegment.size > 8) {
                        udpForwarder?.forward(
                            clientIp = parsed.sourceIp, clientPort = parsed.sourcePort,
                            remoteIp = parsed.destIp, remotePort = parsed.destPort,
                            payload = transportSegment.copyOfRange(8, transportSegment.size)
                        )
                    }
                }
                Protocol.TCP -> {
                    tcpForwarder?.handle(
                        clientIp = parsed.sourceIp, serverIp = parsed.destIp,
                        tcpSegmentBytes = transportSegment
                    )
                }
                else -> { /* ICMP и прочее пока не обрабатываем */ }
            }
        }
        packetReader?.start(serviceScope)

        // Периодически закрываем "молчащие" сессии, иначе сокеты будут копиться
        serviceScope.launch {
            while (true) {
                delay(30_000)
                udpForwarder?.cleanupIdleSessions()
                tcpForwarder?.cleanupIdleSessions()
            }
        }

        Log.i(TAG, "VPN интерфейс поднят, форвардим TCP и UDP")
        _isRunning.value = true

        runProtectCanaryTest()
    }

    /**
     * Изолированная проверка: работает ли protect() вообще на этом устройстве,
     * без шума от реального трафика приложений. Подключается к Google DNS
     * (8.8.8.8:53 по TCP — этот порт почти никогда не блокируется) и явно
     * логирует единственный однозначный результат.
     */
    private fun runProtectCanaryTest() {
        Log.i(TAG, "[CANARY] Запускаю тест...")
        serviceScope.launch {
            Log.i(TAG, "[CANARY] Корутина стартовала")

            // Проверяем гипотезу про тайминг: пробуем protect() несколько раз
            // с паузой, вдруг сразу после establish() система ещё не готова.
            var protectedOk = false
            for (attempt in 1..5) {
                val testSocket = Socket()
                val result = protect(testSocket)
                Log.i(TAG, "[CANARY] Попытка $attempt: protect() вернул $result")
                testSocket.close()
                if (result) {
                    protectedOk = true
                    break
                }
                delay(1000)
            }

            if (!protectedOk) {
                Log.e(TAG, "[CANARY] protect() так и не вернул true после 5 попыток")

                // Альтернативный путь: явно привязать сокет к "родной" (не-VPN) сети
                // через ConnectivityManager. Это другой системный механизм —
                // если он сработает там, где protect() не сработал, будем знать,
                // что использовать в форвардерах вместо protect().
                try {
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val networks = cm.allNetworks
                    Log.i(TAG, "[CANARY] Доступно сетей через ConnectivityManager: ${networks.size}")
                    for (network in networks) {
                        val caps = cm.getNetworkCapabilities(network)
                        val isVpn = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) ?: false
                        Log.i(TAG, "[CANARY] Сеть $network, VPN=$isVpn, caps=$caps")
                        if (!isVpn) {
                            try {
                                val altSocket = Socket()
                                network.bindSocket(altSocket)
                                val start = System.currentTimeMillis()
                                altSocket.connect(InetSocketAddress("8.8.8.8", 53), 5000)
                                val elapsed = System.currentTimeMillis() - start
                                Log.i(TAG, "[CANARY] bindSocket()+connect через сеть $network УСПЕШНО за ${elapsed}мс")
                                altSocket.close()
                            } catch (e: Exception) {
                                Log.e(TAG, "[CANARY] bindSocket() через сеть $network ПРОВАЛИЛОСЬ: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[CANARY] Ошибка при работе с ConnectivityManager: ${e.javaClass.simpleName}: ${e.message}")
                }
                return@launch
            }

            val socket = Socket()
            try {
                protect(socket)
                val start = System.currentTimeMillis()
                socket.connect(InetSocketAddress("8.8.8.8", 53), 5000)
                val elapsed = System.currentTimeMillis() - start
                Log.i(TAG, "[CANARY] Подключение к 8.8.8.8:53 УСПЕШНО за ${elapsed}мс — protect() работает штатно")
            } catch (e: Exception) {
                Log.e(TAG, "[CANARY] Подключение к 8.8.8.8:53 ПРОВАЛИЛОСЬ: ${e.message}")
            } finally {
                try { socket.close() } catch (e: Exception) { }
            }
        }
    }

    private fun foregroundServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Мониторинг трафика",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Traffic Monitor активен [build: canary-test]")
            .setContentText("Анализ сетевого трафика приложений")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onRevoke() {
        // вызывается системой, если пользователь отозвал разрешение на VPN в настройках
        Log.w(TAG, "VPN разрешение отозвано системой")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stopVpn() {
        packetReader?.stop()
        packetReader = null
        udpForwarder?.stopAll()
        udpForwarder = null
        tcpForwarder?.stopAll()
        tcpForwarder = null
        vpnInterface?.close()
        vpnInterface = null
        ConnectionLog.clear()
        _isRunning.value = false
    }
}
