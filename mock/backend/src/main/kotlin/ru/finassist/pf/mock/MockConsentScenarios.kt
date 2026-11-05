package ru.finassist.pf.mock

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.common.error.AppError

/** Bounded consent fixtures and request evidence for mock UI tests; never contains phone numbers or tokens. */
class MockConsentScenarios(private val backend: MockBackend) {
    enum class DocumentMode { READY, OFFLINE, RATE_LIMITED, HELD }
    enum class RegisterRejection { OUTDATED, REQUIRED }

    data class DocumentSnapshot(
        val reads: Int,
        val activeReads: Int,
        val maxConcurrentReads: Int,
        val waitRemainingMillis: Long?,
    )

    data class Snapshot(
        val personalData: DocumentSnapshot,
        val assistant: DocumentSnapshot,
        val startVersions: List<String>,
        val startKeyCount: Int,
        val registerVersions: List<String>,
        val grantVersions: List<String>,
        val grantedVersion: String?,
        val overflow: Boolean,
    )

    data class DocumentSettings(
        val versionOverride: String? = null,
        val mode: DocumentMode = DocumentMode.READY,
        val retrySeconds: Int = 3,
        val retryable: Boolean = false,
    )

    private class DocumentState(var published: String) {
        var plan = DocumentSettings()
        var reads = 0
        var active = 0
        var maxActive = 0
        var retryDeadline: Long? = null
        val held = mutableListOf<CompletableDeferred<Unit>>()
    }

    private val lock = Mutex()
    private val pd = DocumentState(MockBackend.CONSENT_VERSION_PD)
    private val assistant = DocumentState(MockBackend.CONSENT_VERSION_ASSISTANT)
    private val starts = mutableListOf<String>()
    private val startKeys = mutableSetOf<String>()
    private val registrations = mutableListOf<String>()
    private val grants = mutableListOf<String>()
    private var grantedVersion: String? = null
    private var overflow = false
    private var registerRejection: RegisterRejection? = null

    /** Changes server validation independently from a stale or delayed document response. */
    suspend fun publish(type: ConsentType, version: String) {
        validateVersion(version)
        backend.mutex.withLock {
            lock.withLock { state(type).published = version }
            backend.profile = backend.profile.copy(consents = backend.profile.consents.map {
                if (it.type == type) it.copy(currentVersion = version) else it
            })
        }
    }

    /** A held read captures its response immediately and completes only through [releaseDocuments]. */
    suspend fun setDocument(
        type: ConsentType,
        versionOverride: String? = null,
        mode: DocumentMode = DocumentMode.READY,
        retrySeconds: Int = 3,
        retryable: Boolean = false,
    ) {
        versionOverride?.let(::validateVersion)
        require(retrySeconds in 1..60) { "Consent retry must be within 1..60 seconds" }
        lock.withLock { state(type).plan = DocumentSettings(versionOverride, mode, retrySeconds, retryable) }
    }

    suspend fun documentSettings(type: ConsentType): DocumentSettings = lock.withLock { state(type).plan }

    suspend fun releaseDocuments(type: ConsentType) {
        val held = lock.withLock {
            val state = state(type)
            if (state.plan.mode == DocumentMode.HELD) state.plan = state.plan.copy(mode = DocumentMode.READY)
            state.held.toList().also { state.held.clear() }
        }
        held.forEach { it.complete(Unit) }
    }

    suspend fun setRegisterRejection(rejection: RegisterRejection?) {
        lock.withLock { registerRejection = rejection }
    }

    internal suspend fun rejectRegisterIfConfigured() {
        when (lock.withLock { registerRejection }) {
            RegisterRejection.OUTDATED -> throw backend.apiError(ErrorCodes.CONSENT_OUTDATED, 422)
            RegisterRejection.REQUIRED -> throw backend.apiError(ErrorCodes.CONSENT_REQUIRED, 403)
            null -> Unit
        }
    }

    internal suspend fun publishedVersion(type: ConsentType): String = lock.withLock { state(type).published }

    internal suspend fun recordStart(version: String, key: String) = lock.withLock {
        record(starts, version)
        if (startKeys.size < MAX_EVIDENCE) startKeys += key else if (key !in startKeys) overflow = true
    }

    internal suspend fun recordRegister(version: String) = lock.withLock { record(registrations, version) }
    internal suspend fun recordGrant(version: String) = lock.withLock { record(grants, version) }
    internal suspend fun granted(version: String) = lock.withLock { grantedVersion = version }

    internal suspend fun readDocument(type: ConsentType): ConsentDocument {
        if (type == ConsentType.UNKNOWN) {
            backend.simulateNetwork()
            throw backend.apiError(ErrorCodes.NOT_FOUND, 404)
        }
        val (plan, version, held) = lock.withLock {
            val state = state(type)
            state.reads += 1
            val plan = state.plan
            val held = if (plan.mode == DocumentMode.HELD) {
                if (state.held.size >= MAX_EVIDENCE) {
                    overflow = true
                    throw backend.apiError(ErrorCodes.REQUEST_IN_PROGRESS, 409)
                }
                CompletableDeferred<Unit>().also { state.held += it }
            } else null
            state.active += 1
            state.maxActive = maxOf(state.maxActive, state.active)
            Triple(plan, plan.versionOverride ?: state.published, held)
        }
        try {
            backend.simulateNetwork()
            held?.await()
            when (plan.mode) {
                DocumentMode.OFFLINE -> throw AppError.Offline()
                DocumentMode.RATE_LIMITED -> {
                    lock.withLock { state(type).retryDeadline = elapsedMillis() + plan.retrySeconds * 1_000L }
                    throw backend.rateLimitedError(backend.now().plusSeconds(plan.retrySeconds.toLong()),
                        plan.retrySeconds.toLong(), requestRetryable = plan.retryable)
                }
                DocumentMode.READY, DocumentMode.HELD -> Unit
            }
            return when (type) {
                ConsentType.PERSONAL_DATA -> ConsentDocument(type, version, "Согласие на обработку персональных данных", "https://example.ru/legal/pd-consent/$version")
                ConsentType.ASSISTANT -> ConsentDocument(type, version, "Согласие на передачу данных помощнику", "https://example.ru/legal/assistant-consent/$version")
                ConsentType.UNKNOWN -> error("Unknown consent cannot reach the fixture")
            }
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    state(type).active -= 1
                    if (held != null) state(type).held.remove(held)
                }
            }
        }
    }

    suspend fun snapshot(): Snapshot = lock.withLock {
        Snapshot(documentSnapshot(pd), documentSnapshot(assistant), starts.toList(), startKeys.size,
            registrations.toList(), grants.toList(), grantedVersion, overflow)
    }

    private fun documentSnapshot(state: DocumentState) = DocumentSnapshot(state.reads, state.active, state.maxActive,
        state.retryDeadline?.let { (it - elapsedMillis()).coerceAtLeast(0) })

    private fun elapsedMillis(): Long = backend.elapsedClock?.nowMillis() ?: backend.now().toInstant().toEpochMilli()

    private fun state(type: ConsentType): DocumentState = when (type) {
        ConsentType.PERSONAL_DATA -> pd
        ConsentType.ASSISTANT -> assistant
        ConsentType.UNKNOWN -> error("Unknown consent fixture")
    }

    private fun record(values: MutableList<String>, version: String) {
        if (values.size < MAX_EVIDENCE) values += version.take(64) else overflow = true
    }

    private fun validateVersion(version: String) {
        require(version.length in 1..64 && version.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" }) {
            "Invalid consent fixture version"
        }
    }

    private companion object { const val MAX_EVIDENCE = 128 }
}
