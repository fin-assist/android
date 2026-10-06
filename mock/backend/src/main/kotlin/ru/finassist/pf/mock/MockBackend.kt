package ru.finassist.pf.mock

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.finassist.pf.core.api.ImportEvent
import ru.finassist.pf.core.api.model.AssistantConsent
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.Theme
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.mock.domain.AnalyticsEngine
import ru.finassist.pf.mock.domain.Catalog
import ru.finassist.pf.mock.domain.Coverage
import ru.finassist.pf.mock.domain.Ledger
import ru.finassist.pf.mock.domain.Scope
import ru.finassist.pf.mock.ofx.UnreadLine
import java.time.OffsetDateTime
import java.util.UUID

/**
 * In-memory state of the fake server for one user. Everything the real services would persist lives here:
 * ledger, uploads, profile, consents, assistant history and limit, idempotency cache. The API classes in
 * `api/` are thin adapters over this object. All mutation goes through [mutex].
 */
class MockBackend(
    @Volatile var config: MockConfig,
    private val clock: Clock,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val mutex = Mutex()

    /** Completed once the preloaded statement is imported; requests wait for it. */
    val ready = CompletableDeferred<Unit>()

    val catalog = Catalog()
    val ledger = Ledger(catalog)
    val operationScope = Scope(ledger, catalog, Clock.MOSCOW)

    class Upload(
        val id: String,
        val fileName: String,
        val uploadedAt: OffsetDateTime,
        val coverageFrom: OffsetDateTime,
        val coverageTo: OffsetDateTime,
        val unreadLines: List<UnreadLine>,
        var operationCount: Int = 0,
        var firstOperationAt: OffsetDateTime? = null,
        var lastOperationAt: OffsetDateTime? = null,
        var result: ImportResult? = null,
        val events: MutableStateFlow<ImportEvent> = MutableStateFlow(ImportEvent.Progress(ru.finassist.pf.core.api.model.ImportProgress(ru.finassist.pf.core.api.model.ImportStage.PARSING, 0))),
    ) {
        val isDone: Boolean get() = result != null
        val isFailed: Boolean get() = events.value is ImportEvent.Error
        val isProcessing: Boolean get() = !isDone && !isFailed
    }

    /** Uploads newest first. Failed/cancelled ones are dropped from the list. */
    val uploads = ArrayList<Upload>()

    var profile: Profile = emptyProfile("")
    val dismissedPayments = HashSet<String>()
    val assistant = AssistantStore()

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
    )

    fun now(): OffsetDateTime = clock.now()

    /** [including] — an upload whose import is finishing right now (not yet marked done). */
    fun coverage(including: Upload? = null): Coverage = Coverage(
        uploads.filter { it.isDone || it === including }.map { it.coverageFrom to it.coverageTo },
        Clock.MOSCOW,
        now().atZoneSameInstant(Clock.MOSCOW).toLocalDate(),
    )

    fun unreadLines(): List<UnreadLine> = uploads.filter { it.isDone }.flatMap { it.unreadLines }

    /** Simulated latency + the "offline" switch. Called at the top of every API method. */
    suspend fun simulateNetwork() {
        ready.await()
        if (config.offline) throw AppError.Offline()
        if (config.networkDelayMs > 0) delay(config.networkDelayMs)
    }

    /** Runs [block] once per key; a repeat with the same key returns the cached result (api.md, idempotency). */
    suspend fun <T : Any> idempotent(key: String, block: suspend () -> T): T {
        @Suppress("UNCHECKED_CAST")
        mutex.withLock { idempotency[key] as? T }?.let { return it }
        val result = block()
        mutex.withLock { idempotency[key] = result }
        return result
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
        assistantConsent = AssistantConsent(granted = false, currentVersion = CONSENT_VERSION_ASSISTANT),
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
