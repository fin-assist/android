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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import ru.finassist.pf.core.common.availability.ClientVersion
import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.common.time.ElapsedClock
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
    /** What the app sends as `X-Client-Version`: the fake server checks it like the real one (FIN-20). */
    private val clientVersion: ClientVersion = ClientVersion("0.0.0", 0),
    /** Where the update and maintenance answers go, as the network layer reports them in `prod` (FIN-22). */
    private val gate: ServiceGate = ServiceGate(),
    internal val elapsedClock: ElapsedClock? = null,
) {
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val mutex = Mutex()
    private val injectedErrors = MutableStateFlow(0)
    /** Cumulative: clearing the scenario does not erase evidence of an accidental resend. */
    val injectedErrorCount: StateFlow<Int> = injectedErrors.asStateFlow()

    internal suspend fun maybeInjectError(operation: MockErrorOperation) {
        val scenario = mutex.withLock {
            if (config.errorOperation != operation) return@withLock null
            injectedErrors.value += 1
            config.errorScenario
        } ?: return
        throw injectedError(scenario)
    }


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
    /** Write receipts and delayed publication are separate; guarded by [mutex], mock-only test control. */
    internal val unpublishedDismissals = mutableMapOf<String, Int>()
    var dismissalWriteCount: Int = 0
        internal set
    /** Category changes and statement uploads/deletions, for `changed_since` of the assistant's answers (api.md 7.2). */
    val dataChanges = DataChanges()
    val assistant = AssistantStore(dataChanges)

    /** `Idempotency-Key` → cached response for the endpoints api.md marks idempotent. */
    val idempotency = HashMap<String, Stored>()

    /** A cached response and the request it answered ([request] null: the endpoint does not compare bodies yet). */
    class Stored(val request: Any?, val result: Any, val expiresAt: OffsetDateTime? = null)

    val analytics = AnalyticsEngine(
        ledger = ledger,
        catalog = catalog,
        scope = operationScope,
        coverage = ::coverage,
        dismissedPayments = { dismissedPayments - unpublishedDismissals.keys },
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
        checkAvailability()
    }

    /**
     * What the real server checks before anything else, in its order (api.md «Ошибки»): maintenance (503
     * `MAINTENANCE`), then the client version (400 `CLIENT_VERSION_UNSUPPORTED`). Both go to the [gate] as the
     * network layer reports them in `prod`; a request that passes ends a maintenance the gate still shows. Every
     * plain call ([simulateNetwork]) and every opening of a stream, re-opens included ([droppable]), runs it.
     */
    private fun checkAvailability() {
        val c = config
        if (c.maintenance) {
            val error = maintenanceError(c.maintenanceUntil, c.maintenanceRetryAfterSeconds)
            gate.maintenance(c.maintenanceUntil, c.maintenanceRetryAfterSeconds, now(), response = error.response)
            throw error
        }
        if (clientVersion.isBelow(c.minVersion)) {
            val error = updateRequiredError()
            gate.updateRequired(error.presentation)
            throw error
        }
        gate.reopen()
    }

    /** Guards the drop and 429 counters of [MockConfig] (a coroutine mutex: `synchronized` is JVM-only). */
    private val dropLock = Mutex()

    /** How many stream openings have been turned away with 429 ([MockConfig.streamRateLimits]); for UI tests. */
    val streamRateLimited = MutableStateFlow(0)

    /** The last opening of a stream was dropped: the next one is a re-open ([MockConfig.streamRateLimits]). */
    private var afterDrop = false

    /**
     * [stream] as one opening of an SSE stream: maintenance and the client version are checked first
     * ([checkAvailability]), as for a plain call; then, when [MockConfig.streamDrops] is above zero and the first event is not
     * terminal, the opening ends right after that event (a drop, api.md «SSE») and the counter goes down by one. An
     * opening right after a drop answers 429 while [MockConfig.streamRateLimits] is above zero, counting it down. An
     * opening still open when maintenance starts ends at its next event without it, like a drop.
     */
    fun <T> droppable(stream: Flow<T>, isTerminal: (T) -> Boolean): Flow<T> = flow {
        // Before the stream starts, as an HTTP answer (api.md «SSE»: a re-open during maintenance gets 503).
        checkAvailability()
        takeRateLimit()?.let { throw it }
        var drop: Boolean? = null
        emitAll(
            stream.transformWhile { event ->
                // An open stream is cut when maintenance starts (api.md «SSE»); the re-open then gets 503.
                if (config.maintenance) return@transformWhile false
                emit(event)
                if (drop == null) drop = !isTerminal(event) && takeDrop()
                drop != true
            },
        )
    }

    private suspend fun takeDrop(): Boolean = dropLock.withLock {
        val left = config.streamDrops
        if (left <= 0) return@withLock false
        config = config.copy(streamDrops = left - 1)
        afterDrop = true
        true
    }

    private suspend fun takeRateLimit(): AppError.RateLimited? = dropLock.withLock {
        val left = config.streamRateLimits
        if (!afterDrop || left <= 0) {
            afterDrop = false
            return@withLock null
        }
        config = config.copy(streamRateLimits = left - 1)
        val seconds = config.streamRetrySeconds
        streamRateLimited.value += 1
        rateLimitedError(retryAt = now().plusSeconds(seconds), retryAfterSeconds = seconds, requestRetryable = true)
    }

    /** Keys whose first request is still running. */
    private val inFlight = HashSet<String>()

    /**
     * Runs [block] once per key; a repeat with the same key returns the cached result, and a repeat while the
     * first request is still running gets 409 REQUEST_IN_PROGRESS (api.md, idempotency). A repeat with another
     * [request] (the request body, compared by equality) gets 409 IDEMPOTENCY_CONFLICT; a call without [request]
     * is not compared. A failed [block] is not stored: the key can be used again. [onRepeat] runs before a cached
     * result is returned: a [block] that simulates the network itself passes the same checks here, so a repeat
     * still meets offline and a lost session. [expiresAt] optionally bounds the cache lifetime; the response is
     * discarded at that instant, and a replay never extends it.
     */
    suspend fun <T : Any> idempotent(
        key: String,
        request: Any? = null,
        onRepeat: suspend () -> Unit = {},
        expiresAt: OffsetDateTime? = null,
        block: suspend () -> T,
    ): T {
        val cached = mutex.withLock {
            val stored = idempotency[key]?.takeUnless { saved ->
                saved.expiresAt?.let { !now().isBefore(it) } == true
            }
            if (stored == null) idempotency.remove(key)
            if (stored != null && request != null && stored.request != null && stored.request != request) {
                throw apiError(ErrorCodes.IDEMPOTENCY_CONFLICT, 409, message = "Idempotency-Key was used with another request")
            }
            @Suppress("UNCHECKED_CAST")
            (stored?.result as? T) ?: run {
                if (!inFlight.add(key)) throw inProgressError(retryAfterSeconds = 1)
                null
            }
        }
        if (cached != null) {
            onRepeat()
            return cached
        }
        try {
            val result = block()
            mutex.withLock { idempotency[key] = Stored(request, result, expiresAt) }
            return result
        } finally {
            // Also when the caller was cancelled mid-request: the lock may be busy, and a cancellable wait for it
            // would leave the key «in progress» for good.
            withContext(NonCancellable) { mutex.withLock { inFlight.remove(key) } }
        }
    }

    fun requireSession() {
        if (profile.userId.isEmpty()) throw unauthorizedError(ErrorCodes.INVALID_TOKEN)
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
        unpublishedDismissals.clear()
        dismissalWriteCount = 0
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
