package ru.finassist.pf.feature.analytics.api

import kotlinx.serialization.Serializable
import ru.finassist.pf.core.api.model.AnalyticsParams

/** Routes of the analytics feature. */
object AnalyticsRoutes {
    /** Tab root «Аналитика»; opens on the last period with data. */
    @Serializable
    data object Home

    /**
     * Analytics for given parameters — from an assistant chip. Register with `typeMap = PfNavTypes.MAP`.
     * Shown as a pushed screen with a back arrow, so the chat stays underneath.
     */
    @Serializable
    data class Period(val params: AnalyticsParams)
}
