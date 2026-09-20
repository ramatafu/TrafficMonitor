package com.ramatafu.trafficmonitor.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ramatafu.trafficmonitor.R
import com.ramatafu.trafficmonitor.vpn.ACTION_STOP_VPN
import com.ramatafu.trafficmonitor.vpn.ConnectionEntry
import com.ramatafu.trafficmonitor.vpn.ConnectionLog
import com.ramatafu.trafficmonitor.vpn.LocalVpnService
import kotlinx.coroutines.launch

// Порог для "детектора слива": если приложение отправило больше этого —
// стоит присмотреться, что оно вообще передаёт наружу.
private const val DATA_LEAK_THRESHOLD_BYTES = 1_000_000L

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            statusText.text = "Пользователь отказал в разрешении на VPN"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.startButton)
        val appListButton = findViewById<Button>(R.id.appListButton)

        toggleButton.setOnClickListener {
            if (LocalVpnService.isRunning.value) {
                stopVpnService()
            } else {
                requestVpnPermissionAndStart()
            }
        }

        appListButton.setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }

        observeVpnState()
        observeConnectionLog()
    }

    private fun requestVpnPermissionAndStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val serviceIntent = Intent(this, LocalVpnService::class.java)
        startForegroundService(serviceIntent)
    }

    private fun stopVpnService() {
        val stopIntent = Intent(this, LocalVpnService::class.java).apply {
            action = ACTION_STOP_VPN
        }
        startService(stopIntent)
    }

    /** Меняем текст кнопки в зависимости от того, реально ли сейчас поднят VPN. */
    private fun observeVpnState() {
        lifecycleScope.launch {
            LocalVpnService.isRunning.collect { running ->
                toggleButton.text = if (running) "Остановить мониторинг" else "Запустить мониторинг"
                if (!running) {
                    statusText.text = "VPN остановлен. Нажмите кнопку, чтобы начать"
                }
            }
        }
    }

    private fun observeConnectionLog() {
        lifecycleScope.launch {
            ConnectionLog.state.collect { entries ->
                if (!LocalVpnService.isRunning.value && entries.isEmpty()) return@collect
                statusText.text = buildString {
                    append("Соединений: ${entries.size}\n\n")
                    entries.take(20).forEach { entry -> append(formatEntry(entry)) }
                }
            }
        }
    }

    private fun formatEntry(entry: ConnectionEntry): String {
        val destination = entry.domain ?: entry.destIp

        // Маркеры-предупреждения — по мотивам плана: трекер, заблокировано, подозрительная отдача
        val markers = buildString {
            if (entry.blocked) append("🚫 ")
            if (entry.isTracker) append("⚠️трекер ")
            if (entry.bytesSent > DATA_LEAK_THRESHOLD_BYTES) append("📤слив? ")
        }

        return buildString {
            append(markers)
            append("${entry.appLabel} → $destination:${entry.destPort} [${entry.protocol}]\n")
            append("  ↑${formatBytes(entry.bytesSent)}  ↓${formatBytes(entry.bytesReceived)}")
            append("  · ${entry.sessionCount} сесс.")
            append("  · ${formatRelativeTime(entry.lastActivityMs)}\n")
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000 -> String.format("%.1f МБ", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.1f КБ", bytes / 1_000.0)
            else -> "$bytes Б"
        }
    }

    private fun formatRelativeTime(timestampMs: Long): String {
        val diffSec = (System.currentTimeMillis() - timestampMs) / 1000
        return when {
            diffSec < 5 -> "только что"
            diffSec < 60 -> "$diffSec сек назад"
            diffSec < 3600 -> "${diffSec / 60} мин назад"
            else -> "${diffSec / 3600} ч назад"
        }
    }
}
