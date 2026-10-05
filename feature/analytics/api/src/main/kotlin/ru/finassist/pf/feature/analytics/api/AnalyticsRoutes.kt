package ru.finassist.pf.feature.analytics.api

import kotlinx.serialization.Serializable

/** Routes of the analytics feature. Stage 6 adds the tile and selection screens. */
object AnalyticsRoutes {
    /** Tab root «Аналитика». */
    @Serializable
    data object Home
}
