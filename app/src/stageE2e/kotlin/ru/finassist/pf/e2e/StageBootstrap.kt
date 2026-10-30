package ru.finassist.pf.e2e

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import ru.finassist.pf.core.api.AnalyticsApi
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.ImportEvent
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.StatementsApi
import ru.finassist.pf.core.api.UploadFile
import ru.finassist.pf.core.api.reopeningStream
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.PhoneVerificationStatusEvent
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.RegisterResponse
import ru.finassist.pf.core.api.model.StartPhoneVerificationRequest
import ru.finassist.pf.core.api.model.VerificationStatus
import ru.finassist.pf.feature.applock.api.AppLock
import ru.finassist.pf.feature.applock.api.LockState
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Test precondition using the first real session, not coverage of the registration screens. */
class StageBootstrap(
    private val auth: AuthApi,
    private val profile: ProfileApi,
    private val statements: StatementsApi,
    private val analytics: AnalyticsApi,
    private val session: SessionRepository,
    private val appLock: AppLock,
    private val startSession: suspend (RegisterResponse) -> Unit,
    private val fixture: () -> ByteArray,
    private val now: () -> OffsetDateTime = { OffsetDateTime.now(ZoneId.of("Europe/Moscow")) },
    private val timeoutMs: Long = 180_000,
    private val onPhase: (StageBootstrapPhase) -> Unit = {},
) {
    suspend fun setup(phone: String, mode: String) = withTimeout(timeoutMs) {
        require(mode in MODES)
        require(Regex("\\+7000000[0-9]{4}").matches(phone))
        onPhase(StageBootstrapPhase.SESSION)
        if (session.state.first { it != SessionState.Unknown } != SessionState.SignedOut) throw StageBootstrapFailure(StageBootstrapFailureCode.SESSION_STATE)
        // A cold lock begins as Locked until the signed-out store/reset has loaded; wait for that real state.
        onPhase(StageBootstrapPhase.LOCK)
        appLock.state.first { it == LockState.NotConfigured }
        onPhase(StageBootstrapPhase.DOCUMENT)
        val document = auth.getConsentDocument(ConsentType.PERSONAL_DATA)
        if (document.type != ConsentType.PERSONAL_DATA || document.version.isBlank()) throw StageBootstrapFailure(StageBootstrapFailureCode.DOCUMENT_INVALID)
        onPhase(StageBootstrapPhase.PHONE)
        val started = auth.startPhoneVerification(IdempotencyKey.random(), StartPhoneVerificationRequest(phone, true, document.version, true))
        onPhase(StageBootstrapPhase.VERIFICATION)
        val verified = reopeningStream<PhoneVerificationStatusEvent>(isTerminal = { it.status != VerificationStatus.PENDING }) {
            auth.streamPhoneVerification(started.verificationToken)
        }.first { it.status != VerificationStatus.PENDING }
        // Never adopt an existing user's tokens, profile or data, even if a random synthetic phone collides.
        if (verified.status != VerificationStatus.VERIFIED) throw StageBootstrapFailure(StageBootstrapFailureCode.VERIFICATION_NOT_VERIFIED)
        if (verified.isNewUser != true) throw StageBootstrapFailure(StageBootstrapFailureCode.CANDIDATE_NOT_NEW)
        val registration = verified.registrationToken?.takeIf { it.isNotBlank() }
            ?: throw StageBootstrapFailure(StageBootstrapFailureCode.REGISTRATION_RECEIPT_MISSING)
        onPhase(StageBootstrapPhase.REGISTER)
        val registered = auth.register(IdempotencyKey.random(), RegisterRequest(registration, true, document.version, "Europe/Moscow"))
        onPhase(StageBootstrapPhase.SESSION_COMMIT)
        startSession(registered)
        session.state.first { it is SessionState.SignedIn && it.userId == registered.userId }
        onPhase(StageBootstrapPhase.PROFILE)
        val current = profile.getProfile()
        if (current.userId != registered.userId || current.phone != phone) throw StageBootstrapFailure(StageBootstrapFailureCode.PROFILE_IDENTITY_MISMATCH)
        onPhase(StageBootstrapPhase.IMPORT)
        val bytes = fixture().let { if (mode == "fresh") StageBootstrapFixture.fresh(it, now()) else it }
        val accepted = statements.uploadStatement(IdempotencyKey.random(), UploadFile("fixture.ofx", bytes.size.toLong()) { bytes.inputStream() })
        onPhase(StageBootstrapPhase.IMPORT_PROGRESS)
        val completed = reopeningStream<ImportEvent>(isTerminal = { it !is ImportEvent.Progress }) {
            statements.streamProgress(accepted.uploadId)
        }.first { it !is ImportEvent.Progress }
        if (completed !is ImportEvent.Complete || completed.result.newCount <= 0) throw StageBootstrapFailure(StageBootstrapFailureCode.IMPORT_NOT_COMPLETE)
        onPhase(StageBootstrapPhase.ANALYTICS)
        while (true) {
            val overview = analytics.getAnalytics(period = PeriodTypeCode.MONTH)
            val periods = analytics.listPeriods(period = PeriodTypeCode.MONTH)
            if (overview.hasData && overview.state?.recalculating == false && periods.periods.isNotEmpty()) break
            // Readiness of asynchronous public data, never a phone limit bypass or a second Phone Start.
            delay(500)
        }
        if (mode != "seeded_needs_code") {
            onPhase(StageBootstrapPhase.PASSCODE)
            appLock.setPasscode("1234")
            appLock.state.first { it == LockState.Unlocked }
        }
        onPhase(StageBootstrapPhase.DONE)
    }

    companion object { val MODES = setOf("seeded", "fresh", "seeded_needs_code") }
}

/** Advances only the synthetic statement's collection timestamps; operation identity and dates stay unchanged. */
object StageBootstrapFixture {
    fun fresh(bytes: ByteArray, now: OffsetDateTime): ByteArray {
        val stamp = now.atZoneSameInstant(ZoneId.of("Europe/Moscow"))
            .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss.SSS'[+3:MSK]'"))
        var source = bytes.toString(Charsets.UTF_8)
        for (tag in listOf("DTSERVER", "DTEND")) {
            val pattern = Regex("(<$tag>)[^<]+(</$tag>)")
            require(pattern.containsMatchIn(source)) { "Missing closed fixture timestamp" }
            source = pattern.replace(source) { "${it.groupValues[1]}$stamp${it.groupValues[2]}" }
        }
        return source.toByteArray(Charsets.UTF_8)
    }
}
