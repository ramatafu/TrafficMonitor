package com.ramatafu.trafficmonitor.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ramatafu.trafficmonitor.R
import com.ramatafu.trafficmonitor.vpn.ACTION_STOP_VPN
import com.ramatafu.trafficmonitor.vpn.LocalVpnService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var vpnSwitch: Switch

    // true, пока сами программно меняем switch, чтобы не словить свой же
    // listener и не запустить VPN повторно / не запросить разрешение зря
    private var suppressSwitchCallback = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            statusText.text = "Пользователь отказал в разрешении на VPN"
            setSwitchChecked(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        vpnSwitch = findViewById(R.id.vpnSwitch)

        vpnSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener
            if (checked) requestVpnPermissionAndStart() else stopVpnService()
        }

        setupBottomNav()
        if (savedInstanceState == null) {
            showFragment(ConnectionsFragment())
        }

        observeVpnState()
    }

    private fun setupBottomNav() {
        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
            .setOnItemSelectedListener { item ->
                val fragment: Fragment = when (item.itemId) {
                    R.id.nav_connections -> ConnectionsFragment()
                    R.id.nav_apps -> AppsFragment()
                    R.id.nav_settings -> SettingsFragment()
                    else -> return@setOnItemSelectedListener false
                }
                showFragment(fragment)
                true
            }
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
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

    private fun setSwitchChecked(checked: Boolean) {
        suppressSwitchCallback = true
        vpnSwitch.isChecked = checked
        suppressSwitchCallback = false
    }

    /** Держим переключатель и статус в актуальном состоянии независимо от того, кто именно запустил/остановил VPN. */
    private fun observeVpnState() {
        lifecycleScope.launch {
            LocalVpnService.isRunning.collect { running ->
                setSwitchChecked(running)
                statusText.text = if (running) "Мониторинг трафика..." else "VPN остановлен"
            }
        }
    }
}
