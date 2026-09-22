package com.ramatafu.trafficmonitor.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ramatafu.trafficmonitor.R
import com.ramatafu.trafficmonitor.vpn.ConnectionLog
import com.ramatafu.trafficmonitor.vpn.DomainBlockListStore
import kotlinx.coroutines.launch

class ConnectionsFragment : Fragment(R.layout.fragment_connections) {

    private val adapter = ConnectionAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        DomainBlockListStore.init(requireContext().applicationContext)

        view.findViewById<RecyclerView>(R.id.connectionsRecyclerView).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ConnectionsFragment.adapter
        }

        viewLifecycleOwner.lifecycleScope.launch {
            ConnectionLog.state.collect { entries ->
                adapter.submitList(entries)
            }
        }

        // Список нужно перерисовать и когда меняется набор заблокированных
        // доменов (иначе значок ⛔ появится только при следующем изменении трафика)
        viewLifecycleOwner.lifecycleScope.launch {
            DomainBlockListStore.blockedDomains.collect {
                adapter.submitList(ConnectionLog.state.value)
            }
        }
    }
}
