package com.ramatafu.trafficmonitor.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ramatafu.trafficmonitor.R
import com.ramatafu.trafficmonitor.vpn.ConnectionEntry

class ConnectionAdapter : RecyclerView.Adapter<ConnectionAdapter.ViewHolder>() {

    private val rows = mutableListOf<ConnectionEntry>()

    fun submitList(newRows: List<ConnectionEntry>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged() // список умеренного размера, простого обновления достаточно
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.connIcon)
        val appLabel: TextView = itemView.findViewById(R.id.connAppLabel)
        val packageText: TextView = itemView.findViewById(R.id.connPackage)
        val destination: TextView = itemView.findViewById(R.id.connDestination)
        val protocol: TextView = itemView.findViewById(R.id.connProtocol)
        val data: TextView = itemView.findViewById(R.id.connData)
        val time: TextView = itemView.findViewById(R.id.connTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_connection_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = rows[position]
        val context = holder.itemView.context

        holder.icon.setImageDrawable(IconCache.get(context, entry.packageName))

        val markers = buildString {
            if (entry.blocked) append("🚫 ")
            if (entry.isTracker) append("⚠️ ")
        }
        holder.appLabel.text = "$markers${entry.appLabel}"
        holder.packageText.text = entry.packageName.ifEmpty { "неизвестный UID" }

        holder.destination.text = "${entry.domain ?: entry.destIp}:${entry.destPort}"
        holder.protocol.text = entry.protocol

        if (entry.blocked) {
            // При блокировке байты не растут (мы не форвардим) — вместо этого
            // явно показываем, сколько раз приложение уже пыталось достучаться,
            // чтобы не путать "заблокировано" с "почему-то ничего не происходит".
            holder.data.text = "попыток: ${entry.blockedAttempts}"
        } else {
            holder.data.text = "↑${formatBytes(entry.bytesSent)} ↓${formatBytes(entry.bytesReceived)}"
        }
        holder.time.text = formatRelativeTime(entry.lastActivityMs)
    }

    override fun getItemCount(): Int = rows.size

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000 -> String.format("%.1fМБ", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.0fКБ", bytes / 1_000.0)
            else -> "${bytes}Б"
        }
    }

    private fun formatRelativeTime(timestampMs: Long): String {
        val diffSec = (System.currentTimeMillis() - timestampMs) / 1000
        return when {
            diffSec < 5 -> "сейчас"
            diffSec < 60 -> "${diffSec}с назад"
            diffSec < 3600 -> "${diffSec / 60}м назад"
            else -> "${diffSec / 3600}ч назад"
        }
    }
}
