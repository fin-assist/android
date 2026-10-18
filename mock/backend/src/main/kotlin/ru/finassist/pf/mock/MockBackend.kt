package ru.finassist.pf.mock

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.finassist.pf.core.api.ImportEvent
import ru.finassist.pf.core.api.model.Consent
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.Theme
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.mock.domain.AnalyticsEngine
import ru.finassist.pf.mock.domain.Catalog
import ru.finassist.pf.mock.domain.Coverage
import ru.finassist.pf.mock.domain.DataChanges
import ru.finassist.pf.mock.domain.Ledger
import ru.finassist.pf.mock.domain.Scope
import ru.finassist.pf.mock.domain.UnreadResolver
import ru.finassist.pf.mock.ofx.StatementPeriod
import ru.finassist.pf.mock.ofx.UnreadLine
import java.time.DateTimeException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * In-memory state of the fake server for one user. Everything the real services would persist lives here:
 * ledger, uploads, profile, consents, assistant history and limit, idempotency cache. The API classes in
 * `api/` are thin adapters over this object. All mutation goes through [mutex].
 */
class MockBackend(
    @Volatile var config: MockConfig,
    private val clock: Clock,
    /** Where background work (imports, answer generation) runs; tests pass one they control. */
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    val catalog: Catalog = Catalog(),
) {
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val mutex = Mutex()

    /** Completed once the preloaded statement is imported; requests wait for it. */
    val ready = CompletableDeferred<Unit>()

    val ledger = Ledger(catalog)
    val operationScope = Scope(ledger, catalog) { profileZone() }
    /** Days of lines and statement periods are compared in the profile zone at the time of the check (FIN-79, FIN-92). */
    val unreadResolver = UnreadResolver(ledger) { profileZone() }

    class Upload(
        val id: String,
        val fileName: String,
        val uploadedAt: OffsetDateTime,
        val coverageFrom: OffsetDateTime,
        val coverageTo: OffsetDateTime,
        val unreadLines: List<UnreadLine>,
        /** `DTSTART`/`DTEND` per account (FIN-79). */
        val accountPeriods: Map<String, StatementPeriod> = emptyMap(),
        var operationCount: Int = 0,
        var firstOperationAt: OffsetDateTime? = null,
        var lastOperationAt: OffsetDateTime? = null,
        var result: ImportResult? = null,
        val events: MutableStateFlow<ImportEvent> = MutableStateFlow(ImportEvent.Progress(ru.finassist.pf.core.api.model.ImportProgress(ru.finassist.pf.core.api.model.ImportStage.PARSING, 0))),
    ) {
        /**
         * Index of the record in [unreadLines] → the later upload that closed it (api.md 3.6, «Закрытие строк»; FIN-79).
         * Not the line number: several `<STMTTRN>` of a compact OFX may start on one physical line.
         */
        val closedBy: MutableMap<Int, Closure> = HashMap()

        /** Unread lines no later upload has closed: the only ones the API shows. */
        val openUnreadLines: List<UnreadLine> get() = unreadLines.filterIndexed { i, _ -> i !in closedBy }

        /** Closures where the line was read, not replaced by a record of the later upload. */
        val readClosures: List<Closure> get() = closedBy.values.filter { it.read }

        val isDone: Boolean get() = result != null
        val isFailed: Boolean get() = events.value is ImportEvent.Error
        val isProcessing: Boolean get() = !isDone && !isFailed
    }

    /** [uploadId] closed an unread line: it read it ([read]), or failed to read it again and its record replaces it. */
    data class Closure(val uploadId: String, val read: Boolean)

    /** Uploads newest first. Failed/cancelled ones are dropped from the list. */
    val uploads = ArrayList<Upload>()

    var profile: Profile = emptyProfile("")
    val dismissedPayments = HashSet<String>()
    /** Category changes and statement uploads/deletions, for `changed_since` of the assistant's answers (api.md 7.2). */
    val dataChanges = DataChanges()
    val assistant = AssistantStore(dataChanges)

    /** `Idempotency-Key` → cached response for the endpoints api.md marks idempotent. */
    val idempotency = HashMap<String, Any>()

    val analytics = AnalyticsEngine(
        ledger = ledger,
        catalog = catalog,
        scope = operationScope,
        coverage = ::coverage,
        dismissedPayments = { dismissedPayments },
        unreadLinesIn = { from, to -> unreadLines().count { l -> l.date != null && !l.date.isBefore(from) && l.date.isBefore(to) } },
        now = clock::now,
        expenseKind = { if (config.unknownCodes) MockCodes.EXPENSE_UNKNOWN else ru.finassist.pf.core.api.model.OperationKindFilter.EXPENSE },
    )

    fun now(): OffsetDateTime = clock.now()

    /**
     * Days, months and "today" of the analytics and the feed are counted in `profile.timezone` (api.md 2.2, FIN-92);
     * Moscow before sign-in and for an id Java does not know.
     */
    fun profileZone(): ZoneId = try {
        ZoneId.of(profile.timezone)
    } catch (e: DateTimeException) {
        Clock.MOSCOW
    }

    /** [including] — an upload whose import is finishing right now (not yet marked done). */
    fun coverage(including: Upload? = null): Coverage {
        val zone = profileZone()
        return Coverage(
            uploads.filter { it.isDone || it === including }.map { it.coverageFrom to it.coverageTo },
            zone,
            now().atZoneSameInstant(zone).toLocalDate(),
        )
    }

    /** Open unread lines of every finished upload (FIN-79: closed ones are not counted). */
    fun unreadLines(): List<UnreadLine> = uploads.filter { it.isDone }.flatMap { it.openUnreadLines }

    /** Simulated latency + the "offline" switch. Called at the top of every API method. */
    suspend fun simulateNetwork() {
        ready.await()
        if (config.offline) throw AppError.Offline()
        if (config.networkDelayMs > 0) delay(config.networkDelayMs)
    }

    private val dropLock = Any()

    /**
     * [stream] as one opening of an SSE stream: when [MockConfig.streamDrops] is above zero and the first event is not
     * terminal, the opening ends right after that event (a drop, api.md «SSE») and the counter goes down by one.
     */
    fun <T> droppable(stream: Flow<T>, isTerminal: (T) -> Boolean): Flow<T> = flow {
        var drop: Boolean? = null
        emitAll(
            stream.transformWhile { event ->
                emit(event)
                if (drop == null) drop = !isTerminal(event) && takeDrop()
                drop != true
            },
        )
    }

    private fun takeDrop(): Boolean = synchronized(dropLock) {
        val left = config.streamDrops
        if (left <= 0) return@synchronized false
        config = config.copy(streamDrops = left - 1)
        true
    }

    /** Keys whose first request is still running. */
    private val inFlight = HashSet<String>()

    /**
     * Runs [block] once per key; a repeat with the same key returns the cached result, and a repeat while the
     * first request is still running gets 409 REQUEST_IN_PROGRESS (api.md, idempotency). A failed [block] is not
     * stored: the key can be used again. [onRepeat] runs before a cached result is returned: a [block] that
     * simulates the network itself passes the same checks here, so a repeat still meets offline and a lost session.
     */
    suspend fun <T : Any> idempotent(key: String, onRepeat: suspend () -> Unit = {}, block: suspend () -> T): T {
        val cached = mutex.withLock {
            @Suppress("UNCHECKED_CAST")
            (idempotency[key] as? T) ?: run {
                if (!inFlight.add(key)) throw AppError.InProgress(retryAfterSeconds = 1)
                null
            }
        }
        if (cached != null) {
            onRepeat()
            return cached
        }
        try {
            val result = block()
            mutex.withLock { idempotency[key] = result }
            return result
        } finally {
            // Also when the caller was cancelled mid-request: the lock may be busy, and a cancellable wait for it
            // would leave the key «in progress» for good.
            withContext(NonCancellable) { mutex.withLock { inFlight.remove(key) } }
        }
    }

    fun requireSession() {
        if (profile.userId.isEmpty()) throw AppError.Unauthorized(ErrorCodes.INVALID_TOKEN)
    }

    fun newUserId(): String = UUID.randomUUID().toString()

    fun emptyProfile(phone: String, userId: String = "") = Profile(
        userId = userId,
        phone = phone,
        theme = Theme.SYSTEM,
        timezone = "Europe/Moscow",
        consents = listOf(Consent(ConsentType.ASSISTANT, granted = false, currentVersion = CONSENT_VERSION_ASSISTANT)),
        createdAt = now(),
    )

    /** Deleting the account wipes everything; the next sign-in starts from scratch. */
    suspend fun wipe() = mutex.withLock {
        ledger.clear()
        uploads.clear()
        dismissedPayments.clear()
        assistant.clear()
        idempotency.clear()
        profile = emptyProfile("")
    }

    companion object {
        const val CONSENT_VERSION_PD = "2026-09-28"
        const val CONSENT_VERSION_ASSISTANT = "2026-09-28"
        const val SOURCE_NAME = "Выписка Т-Банка"
    }
}
