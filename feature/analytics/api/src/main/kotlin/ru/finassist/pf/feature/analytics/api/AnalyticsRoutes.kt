package ru.finassist.pf.feature.analytics.api

import kotlinx.serialization.Serializable
import ru.finassist.pf.core.navigation.AnalyticsParams
import ru.finassist.pf.core.navigation.jsonNavTypeMap

object AnalyticsRoutes {
    /** «Аналитика» tab; [params] preselects period/date/mode (assistant chips). */
    @Serializable data class Analytics(val params: AnalyticsParams? = null) {
        companion object { val typeMap = jsonNavTypeMap(AnalyticsParams.serializer(), nullable = true) }
    }
}
