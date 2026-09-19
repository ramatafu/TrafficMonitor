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
import com.ramatafu.trafficmonitor.vpn.ConnectionLog
import com.ramatafu.trafficmonitor.vpn.LocalVpnService
import kotlinx.coroutines.launch

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

        toggleButton.setOnClickListener {
            if (LocalVpnService.isRunning.value) {
                stopVpnService()
            } else {
                requestVpnPermissionAndStart()
            }
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
                    entries.take(20).forEach { entry ->
                        append("${entry.appLabel} → ${entry.destIp}:${entry.destPort} ")
                        append("[${entry.protocol}] ${entry.bytes} байт (${entry.packetCount} пак.)\n")
                    }
                }
            }
        }
    }
}
