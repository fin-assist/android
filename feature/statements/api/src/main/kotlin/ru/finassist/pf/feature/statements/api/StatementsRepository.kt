package ru.finassist.pf.feature.statements.api

import kotlinx.coroutines.flow.Flow

data class UploadsSummary(val uploadCount: Int, val operationCount: Int)

/** What other features need to know about uploads: the profile row and «data changed» refreshes. */
interface StatementsRepository {
    /** Null when nothing was uploaded. */
    suspend fun summary(): UploadsSummary?
    /** Emits after an upload completes or is deleted — operations and analytics reload on it. */
    val dataChanged: Flow<Unit>
}
