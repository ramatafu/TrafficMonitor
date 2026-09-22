package com.ramatafu.trafficmonitor.vpn

/**
 * Эвристика, а не полноценный блокировщик рекламы (для этого нужны списки
 * из тысяч доменов вроде AdGuard/pi-hole). Две проверки:
 * 1) точный список известных трекеров/OEM-телеметрии;
 * 2) токены в самом домене (analytics, telemetry, track и т.п.).
 */
object TrackerDomains {

    private val KNOWN_TRACKERS = setOf(
        "doubleclick.net", "googlesyndication.com", "google-analytics.com",
        "googletagmanager.com", "googleadservices.com", "appsflyer.com",
        "adjust.com", "facebook.com", "graph.facebook.com", "branch.io",
        "amplitude.com", "mixpanel.com", "flurry.com", "adcolony.com",
        "unityads.unity3d.com", "applovin.com", "mopub.com", "supersonicads.com",
        "vungle.com", "mc.yandex.ru",
        // OEM-телеметрия (встречается у Realme/OPPO и похожих прошивок)
        "heytapmobile.com", "heytapdl.com", "volces.com", "fengkongcloud.com"
    )

    private val TOKENS = listOf(
        "analytics", "telemetry", "track", "ads", "beacon",
        "collect", "report", "metrics"
    )

    fun isTracker(domain: String): Boolean {
        val lower = domain.lowercase()
        if (KNOWN_TRACKERS.any { lower == it || lower.endsWith(".$it") }) return true
        return TOKENS.any { lower.contains(it) }
    }
}
