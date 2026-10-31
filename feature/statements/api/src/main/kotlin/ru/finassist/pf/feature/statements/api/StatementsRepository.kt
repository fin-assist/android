package ru.finassist.pf.feature.statements.api

import kotlinx.coroutines.flow.Flow
import ru.finassist.pf.core.api.model.ImportResult

/** What other features need to know about statements: when the data set changed. */
sealed interface StatementsEvent {
    data class ImportCompleted(val uploadId: String, val result: ImportResult) : StatementsEvent
    data class UploadDeleted(val uploadId: String) : StatementsEvent
}

interface StatementsRepository {
    /** Hot stream: the feed and analytics reload on every event. */
    val events: Flow<StatementsEvent>

    /**
     * Opaque local token of a completed non-empty import whose analytics has not been observed yet.
     * Scoped to the current user; survives creation of a new Analytics view model in this process.
     * A successful import can precede publication of its analytics projection.
     */
    suspend fun pendingAnalyticsImport(): Long? = null

    /** Acknowledge only the observed token; a newer import must remain pending. */
    suspend fun analyticsImportReady(revision: Long) = Unit

    /** Result of an import finished in this process, for the result screen; `null` after process death. */
    fun lastResult(uploadId: String): ImportResult?

    /**
     * True once after the user's first import (persisted): Analytics then shows its contextual hints
     * (mvp-scope «контекстные подсказки после первого импорта»).
     */
    suspend fun consumeFirstImportHints(): Boolean
}
