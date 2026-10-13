package ru.finassist.pf.feature.statements.impl

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import ru.finassist.pf.core.api.ImportEvent
import ru.finassist.pf.core.api.StatementsApi
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.UploadFile
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.ImportConfig
import ru.finassist.pf.core.api.model.ImportCoverage
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.api.model.StatementsList
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.UnreadLinesList
import ru.finassist.pf.core.api.model.Upload
import ru.finassist.pf.core.api.model.UploadAccepted
import ru.finassist.pf.core.api.model.UploadStatus
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.tracking.Event
import ru.finassist.pf.core.tracking.Tracker
import java.io.File
import java.nio.file.Files
import java.time.OffsetDateTime

/* Shared fakes of the statements tests: the API, tokens, tracker and a throwaway DataStore. */

internal val T0: OffsetDateTime = OffsetDateTime.parse("2026-10-08T12:00:00+03:00")

internal fun importResult(newCount: Int = 12, isFirstImport: Boolean = false) = ImportResult(
    operationCount = newCount, newCount = newCount, duplicateCount = 0, unreadCount = 0,
    isFirstImport = isFirstImport, accounts = emptyList(), categorizedCount = newCount, ownTransferCount = 0,
    uncategorizedCount = 0, coverage = ImportCoverage(0, 0, emptyList(), emptyList(), emptyList()), notices = emptyList(),
)

internal fun upload(id: String, status: UploadStatus = UploadStatus.DONE) = Upload(
    uploadId = id, fileName = "$id.ofx", status = status, uploadedAt = T0, operationCount = 10, unreadCount = 0,
    sourceName = "Т-Банк",
)

internal fun testDataStore(): DataStore<Preferences> {
    val dir = Files.createTempDirectory("statements-test").toFile()
    return PreferenceDataStoreFactory.create { File(dir, "test.preferences_pb") }
}

internal class FakeTokens(var userId: String?) : TokenStore {
    override val session: Flow<TokenStore.Session?> = flowOf(null)
    override suspend fun current(): TokenStore.Session? = userId?.let { TokenStore.Session(it, TokenPair("a", "r")) }
    override suspend fun save(session: TokenStore.Session) = Unit
    override suspend fun updateTokens(expectedRefreshToken: String, tokens: TokenPair) = false
    override suspend fun clear() = Unit
}

internal class RecordingTracker : Tracker {
    val events = mutableListOf<Pair<Event, Map<String, String>>>()
    override fun track(event: Event, params: Map<String, String>) {
        synchronized(events) { events += event to params }
    }
    override fun setUserId(userId: String?) = Unit
    fun names(): List<Event> = synchronized(events) { events.map { it.first } }
}

/** A scriptable [StatementsApi]: each list / stream call takes the next scripted outcome. */
internal class FakeStatementsApi : StatementsApi {
    /** Each `listStatements` takes the next outcome ([StatementsList] or [AppError]); the last one repeats. */
    val listOutcomes = ArrayDeque<Any>()
    var listCalls = 0

    /** Per upload: each opening of the progress stream takes the next flow; none left — a stream that stays open. */
    val streams = HashMap<String, ArrayDeque<Flow<ImportEvent>>>()
    val streamOpens = mutableListOf<String>()

    var deleteFailure: AppError? = null
    val deleted = mutableListOf<String>()

    var unreadResult: Any = UnreadLinesList(emptyList())
    val unreadCalls = mutableListOf<Any>()

    override suspend fun getImportConfig(): ImportConfig = error("unused")
    override suspend fun uploadStatement(key: IdempotencyKey, file: UploadFile): UploadAccepted = error("unused")

    override fun streamProgress(uploadId: String): Flow<ImportEvent> {
        synchronized(streamOpens) { streamOpens += uploadId }
        return streams[uploadId]?.removeFirstOrNull() ?: flow { awaitCancellation() }
    }

    override suspend fun listStatements(): StatementsList {
        listCalls++
        val o = if (listOutcomes.size > 1) listOutcomes.removeFirst() else listOutcomes.first()
        if (o is AppError) throw o
        return o as StatementsList
    }

    override suspend fun deleteStatement(uploadId: String) {
        deleted += uploadId
        deleteFailure?.let { throw it }
    }

    override suspend fun listUnreadLines(uploadId: String): UnreadLinesList = unread(uploadId)
    override suspend fun listUnreadLines(from: OffsetDateTime, to: OffsetDateTime): UnreadLinesList = unread(from to to)

    private fun unread(call: Any): UnreadLinesList {
        unreadCalls += call
        return when (val r = unreadResult) {
            is AppError -> throw r
            else -> r as UnreadLinesList
        }
    }

    fun script(uploadId: String, vararg flows: Flow<ImportEvent>) {
        streams.getOrPut(uploadId) { ArrayDeque() }.addAll(flows)
    }
}
