package com.ramatafu.trafficmonitor.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.ramatafu.trafficmonitor.R

/** Пока просто заглушка с информацией о приложении — сюда позже можно добавить реальные настройки. */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val versionName = try {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

        view.findViewById<TextView>(R.id.settingsVersion).text = "Версия $versionName"
    }
}
