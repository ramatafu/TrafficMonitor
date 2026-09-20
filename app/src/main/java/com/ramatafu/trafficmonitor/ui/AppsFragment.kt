package com.ramatafu.trafficmonitor.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
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
class AppsFragment : Fragment(R.layout.fragment_apps) {

    private val adapter = AppListAdapter { packageName, blocked ->
        BlockListStore.setBlocked(packageName, blocked)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // на случай, если этот экран открыли раньше первого запуска VPN
        BlockListStore.init(requireContext().applicationContext)
        KnownDomainsStore.init(requireContext().applicationContext)

        view.findViewById<RecyclerView>(R.id.appRecyclerView).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@AppsFragment.adapter
        }

        viewLifecycleOwner.lifecycleScope.launch {
            combine(ConnectionLog.state, BlockListStore.blockedPackages) { _, blocked -> blocked }
                .collect { blocked -> refreshList(blocked) }
        }
    }

    private fun refreshList(blockedPackages: Set<String>) {
        val rows = ConnectionLog.distinctApps().map { (packageName, label) ->
            AppRow(
                packageName = packageName,
                label = label,
                icon = IconCache.get(requireContext(), packageName),
                blocked = packageName in blockedPackages
            )
        }.sortedBy { it.label.lowercase() }

        adapter.submitList(rows)
    }
}
