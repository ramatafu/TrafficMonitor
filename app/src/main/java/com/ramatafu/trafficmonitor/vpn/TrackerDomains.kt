package com.ramatafu.trafficmonitor.vpn

/**
 * Небольшой курируемый список известных рекламных/трекинговых доменов.
 * Не претендует на полноту — это эвристика для привлечения внимания
 * пользователя, а не полноценный блокировщик рекламы (для этого нужны
 * списки из тысяч доменов вроде тех, что использует AdGuard/pi-hole).
 */
object TrackerDomains {

    private val KNOWN_TRACKERS = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googleadservices.com",
        "appsflyer.com",
        "adjust.com",
        "facebook.com",
        "graph.facebook.com",
        "branch.io",
        "amplitude.com",
        "mixpanel.com",
        "flurry.com",
        "adcolony.com",
        "unityads.unity3d.com",
        "applovin.com",
        "mopub.com",
        "supersonicads.com",
        "vungle.com",
        "yandex.ru/clck",
        "mc.yandex.ru"
    )

    /** true, если домен совпадает с известным трекером или является его поддоменом. */
    fun isTracker(domain: String): Boolean {
        val lower = domain.lowercase()
        return KNOWN_TRACKERS.any { known ->
            lower == known || lower.endsWith(".$known")
        }
    }
}
