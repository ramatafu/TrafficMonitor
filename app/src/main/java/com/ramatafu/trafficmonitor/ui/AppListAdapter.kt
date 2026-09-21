package com.ramatafu.trafficmonitor.ui

import android.app.AlertDialog
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
    val blocked: Boolean,
    val isSystem: Boolean = false // системное приложение / общий UID — блокировать с осторожностью
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
        holder.label.text = if (row.isSystem) "⚠️ ${row.label} (системное)" else row.label
        holder.packageText.text = row.packageName

        // снимаем слушатель перед программной установкой состояния,
        // чтобы не словить ложный вызов onToggle при переиспользовании ViewHolder
        holder.toggle.setOnCheckedChangeListener(null)
        holder.toggle.isChecked = row.blocked
        holder.toggle.setOnCheckedChangeListener { _, checked ->
            if (checked && row.isSystem) {
                // Блокировка системного приложения / общего UID может задеть
                // не только его одного (например, общий UID с телефонией или
                // сетевыми проверками системы) — переспрашиваем явно.
                confirmSystemBlock(holder, row)
            } else {
                onToggle(row.packageName, checked)
            }
        }
    }

    private fun confirmSystemBlock(holder: ViewHolder, row: AppRow) {
        AlertDialog.Builder(holder.itemView.context)
            .setTitle("Заблокировать системное приложение?")
            .setMessage(
                "«${row.label}» помечено как системное или использует общий UID с другими " +
                    "системными процессами. Блокировка может неожиданно повлиять на телефонию, " +
                    "сетевые проверки или другие системные функции, а не только на это приложение.\n\n" +
                    "Заблокировать всё равно?"
            )
            .setPositiveButton("Заблокировать") { _, _ -> onToggle(row.packageName, true) }
            .setNegativeButton("Отмена") { _, _ -> holder.toggle.isChecked = false }
            .setOnCancelListener { holder.toggle.isChecked = false }
            .show()
    }

    override fun getItemCount(): Int = rows.size
}
