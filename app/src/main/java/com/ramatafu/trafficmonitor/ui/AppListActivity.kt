package com.ramatafu.trafficmonitor.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ramatafu.trafficmonitor.R
import com.ramatafu.trafficmonitor.vpn.BlockListStore
import com.ramatafu.trafficmonitor.vpn.ConnectionLog
import com.ramatafu.trafficmonitor.vpn.KnownDomainsStore
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Список приложений, которые уже засветились в трафике (см. ConnectionLog),
 * с переключателями блокировки. Реальная блокировка происходит в
 * LocalVpnService — он просто не форвардит пакеты приложений из BlockListStore.
 */
class AppListActivity : AppCompatActivity() {

    private lateinit var adapter: AppListAdapter
    private val iconCache = HashMap<String, Drawable?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)

        // на случай, если этот экран открыли раньше первого запуска VPN
        BlockListStore.init(this)
        KnownDomainsStore.init(this)

        adapter = AppListAdapter { packageName, blocked ->
            BlockListStore.setBlocked(packageName, blocked)
        }

        findViewById<RecyclerView>(R.id.appRecyclerView).apply {
            layoutManager = LinearLayoutManager(this@AppListActivity)
            adapter = this@AppListActivity.adapter
        }

        // Перестраиваем список при любом изменении: либо появилось новое
        // приложение в трафике, либо поменялся набор заблокированных.
        lifecycleScope.launch {
            combine(ConnectionLog.state, BlockListStore.blockedPackages) { _, blocked ->
                blocked
            }.collect { blocked ->
                refreshList(blocked)
            }
        }
    }

    private fun refreshList(blockedPackages: Set<String>) {
        val rows = ConnectionLog.distinctApps().map { (packageName, label) ->
            AppRow(
                packageName = packageName,
                label = label,
                icon = loadIcon(packageName),
                blocked = packageName in blockedPackages
            )
        }.sortedBy { it.label.lowercase() }

        adapter.submitList(rows)
    }

    private fun loadIcon(packageName: String): Drawable? {
        return iconCache.getOrPut(packageName) {
            try {
                packageManager.getApplicationIcon(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                null // приложение могло быть удалено уже после того, как засветилось в трафике
            }
        }
    }
}
