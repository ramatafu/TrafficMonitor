package com.ramatafu.trafficmonitor.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ramatafu.trafficmonitor.R

data class AppRow(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val blocked: Boolean
)

class AppListAdapter(
    private val onToggle: (packageName: String, blocked: Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private val rows = mutableListOf<AppRow>()

    fun submitList(newRows: List<AppRow>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged() // список маленький (десятки строк), простого обновления достаточно
    }

    class ViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.appIcon)
        val label: TextView = itemView.findViewById(R.id.appLabel)
        val packageText: TextView = itemView.findViewById(R.id.appPackage)
        val toggle: Switch = itemView.findViewById(R.id.appBlockSwitch)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        holder.icon.setImageDrawable(row.icon)
        holder.label.text = row.label
        holder.packageText.text = row.packageName

        // снимаем слушатель перед программной установкой состояния,
        // чтобы не словить ложный вызов onToggle при переиспользовании ViewHolder
        holder.toggle.setOnCheckedChangeListener(null)
        holder.toggle.isChecked = row.blocked
        holder.toggle.setOnCheckedChangeListener { _, checked ->
            onToggle(row.packageName, checked)
        }
    }

    override fun getItemCount(): Int = rows.size
}
