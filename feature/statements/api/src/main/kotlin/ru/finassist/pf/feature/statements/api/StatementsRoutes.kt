package ru.finassist.pf.feature.statements.api

import kotlinx.serialization.Serializable

object StatementsRoutes {
    /** Upload screen; [first] = no uploads yet (back goes to the feed, «Загружу позже» link). */
    @Serializable data class ImportGuide(val first: Boolean = false)
    @Serializable data object UploadHistory
    /** Unread lines sheet: by upload or by period (ISO date-times). */
    @Serializable data class UnreadLines(val uploadId: String? = null, val from: String? = null, val to: String? = null)
}
