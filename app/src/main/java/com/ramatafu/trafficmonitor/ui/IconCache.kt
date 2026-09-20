package com.ramatafu.trafficmonitor.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** Простой кэш иконок приложений в памяти — PackageManager.getApplicationIcon не бесплатный. */
object IconCache {
    private val cache = HashMap<String, Drawable?>()

    fun get(context: Context, packageName: String): Drawable? {
        if (packageName.isEmpty()) return null
        return cache.getOrPut(packageName) {
            try {
                context.packageManager.getApplicationIcon(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
    }
}
