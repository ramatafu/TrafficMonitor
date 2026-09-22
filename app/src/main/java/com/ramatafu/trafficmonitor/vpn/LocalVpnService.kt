package com.ramatafu.trafficmonitor.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ramatafu.trafficmonitor.parser.Protocol
import com.ramatafu.trafficmonitor.resolver.AppResolver
import com.ramatafu.trafficmonitor.vpn.nat.DnsSinkhole
import com.ramatafu.trafficmonitor.vpn.nat.TcpForwarder
import com.ramatafu.trafficmonitor.vpn.nat.UdpForwarder
import com.ramatafu.trafficmonitor.vpn.nat.UdpPacketBuilder
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
import java.net.InetAddress
import java.net.InetSocketAddress

private const val TAG = "LocalVpnService"
private const val NOTIFICATION_CHANNEL_ID = "traffic_monitor_channel"
private const val NOTIFICATION_ID = 1
const val ACTION_STOP_VPN = "com.ramatafu.trafficmonitor.STOP_VPN"

/**
 * Поднимает tun-интерфейс, читает пакеты, форвардит TCP и UDP через
 * реальные сокеты (см. UdpForwarder/TcpForwarder), логирует соединения
 * по приложениям в ConnectionLog для отображения в UI.
 *
 * Важно: сокеты форвардеров привязываются к реальной сети через
 * ConnectivityManager.bindSocket() (см. UnderlyingNetworkProvider), а не
 * через VpnService.protect() — на ряде устройств protect() стабильно
 * возвращает false, тогда как bindSocket() работает надёжно.
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
        BlockListStore.init(this)
        KnownDomainsStore.init(this)
        DomainBlockListStore.init(this)
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

            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                (parsed.protocol == Protocol.TCP || parsed.protocol == Protocol.UDP)
            ) {
                appResolver.resolveByPorts(parsed.protocol.number, local, remote)
            } else null

            val appLabel = appInfo?.label ?: "Неизвестно (UID недоступен)"
            val packageName = appInfo?.packageName ?: ""
            val isBlocked = appInfo != null && BlockListStore.isBlocked(packageName)

            if (isBlocked) {
                // приложение в чёрном списке — просто не форвардим, но отмечаем в логе,
                // чтобы было видно, что именно заблокировано
                ConnectionLog.markBlocked(appLabel, packageName, parsed.destIp, parsed.destPort, parsed.protocol.name)
                return@TunPacketReader
            }

            when (parsed.protocol) {
                Protocol.UDP -> {
                    // UDP-заголовок фиксированной длины 8 байт, дальше — полезная нагрузка
                    if (transportSegment.size > 8) {
                        val udpPayload = transportSegment.copyOfRange(8, transportSegment.size)

                        // DNS-sinkhole: если это запрос на порт 53 и запрошенный домен
                        // в чёрном списке — отвечаем NXDOMAIN сами, не пересылая запрос
                        // реальному DNS-серверу вообще. Так домен блокируется ещё до
                        // того, как приложение узнает его IP.
                        if (parsed.destPort == 53) {
                            val queryName = DnsSinkhole.extractQueryName(udpPayload)
                            if (queryName != null && DomainBlockListStore.isBlocked(queryName)) {
                                Log.i(TAG, "DNS-sinkhole: $queryName заблокирован, отвечаем NXDOMAIN")
                                val nxResponse = DnsSinkhole.buildNxDomainResponse(udpPayload)
                                if (nxResponse != null) {
                                    val packet = UdpPacketBuilder.build(
                                        sourceIp = InetAddress.getByName(parsed.destIp), sourcePort = parsed.destPort,
                                        destIp = InetAddress.getByName(parsed.sourceIp), destPort = parsed.sourcePort,
                                        payload = nxResponse
                                    )
                                    synchronized(output) { output.write(packet) }
                                }
                                return@TunPacketReader
                            }
                        }

                        udpForwarder?.forward(
                            clientIp = parsed.sourceIp, clientPort = parsed.sourcePort,
                            remoteIp = parsed.destIp, remotePort = parsed.destPort,
                            payload = udpPayload,
                            packageName = packageName,
                            appLabel = appLabel
                        )
                    }
                }
                Protocol.TCP -> {
                    tcpForwarder?.handle(
                        clientIp = parsed.sourceIp, serverIp = parsed.destIp,
                        tcpSegmentBytes = transportSegment,
                        packageName = packageName,
                        appLabel = appLabel
                    )
                }
                else -> { /* ICMP и прочее пока не обрабатываем */ }
            }
        }
        packetReader?.start(serviceScope)

        // Периодически закрываем "молчащие" сессии, иначе сокеты будут копиться.
        // Заодно, для подстраховки, повторно закрываем сессии уже заблокированных
        // приложений — на случай, если разовое реактивное закрытие ниже почему-то
        // не сработало (например, гонка при разрешении пакета для shared UID).
        serviceScope.launch {
            while (true) {
                delay(30_000)
                udpForwarder?.cleanupIdleSessions()
                tcpForwarder?.cleanupIdleSessions()

                BlockListStore.blockedPackages.value.forEach { packageName ->
                    tcpForwarder?.closeSessionsForPackage(packageName)
                    udpForwarder?.closeSessionsForPackage(packageName)
                }
            }
        }

        // Как только пользователь включает блокировку приложения — сразу рвём
        // его уже открытые соединения, а не ждём следующего пакета или таймаута
        // (без этого долгоживущие сессии вроде XMPP продолжали бы работать).
        serviceScope.launch {
            var previouslyBlocked = BlockListStore.blockedPackages.value
            BlockListStore.blockedPackages.collect { current ->
                val newlyBlocked = current - previouslyBlocked
                newlyBlocked.forEach { packageName ->
                    Log.i(TAG, "Приложение $packageName заблокировано — рвём активные соединения")
                    tcpForwarder?.closeSessionsForPackage(packageName)
                    udpForwarder?.closeSessionsForPackage(packageName)
                }
                previouslyBlocked = current
            }
        }

        Log.i(TAG, "VPN интерфейс поднят, форвардим TCP и UDP")
        _isRunning.value = true
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
            .setContentTitle("Traffic Monitor активен")
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
