package ru.finassist.pf.feature.statements.api

import kotlinx.serialization.Serializable

/** Routes of the statements feature (upload guide, progress, result, history, unread lines). */
object StatementsRoutes {
    /**
     * Upload guide + file picker + progress. [firstRun] — step 4 of 4 right after registration (no back,
     * «Загружу позже» link); otherwise opened from the feed, analytics or the history.
     */
    @Serializable
    data class Upload(val firstRun: Boolean = false)

    /** Import result of a finished upload; the result itself is kept by [StatementsRepository]. */
    @Serializable
    data class Result(val uploadId: String)

    /** Profile → «История загрузок». */
    @Serializable
    data object History

    /**
     * Unread lines of one upload ([uploadId]) or of a period ([from]/[to] as API date-time strings). Exactly
     * one of the two forms is used (api.md 3.6).
     */
    @Serializable
    data class UnreadLines(val uploadId: String? = null, val from: String? = null, val to: String? = null)
}
