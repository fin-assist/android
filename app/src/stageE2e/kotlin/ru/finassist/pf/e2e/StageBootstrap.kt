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
) {
    suspend fun setup(phone: String, mode: String) = withTimeout(timeoutMs) {
        require(mode in MODES)
        require(Regex("\\+7000000[0-9]{4}").matches(phone))
        check(session.state.first { it != SessionState.Unknown } == SessionState.SignedOut) { "Bootstrap requires a signed-out client" }
        // A cold lock begins as Locked until the signed-out store/reset has loaded; wait for that real state.
        appLock.state.first { it == LockState.NotConfigured }
        val document = auth.getConsentDocument(ConsentType.PERSONAL_DATA)
        check(document.type == ConsentType.PERSONAL_DATA && document.version.isNotBlank()) { "Missing current PD document" }
        val started = auth.startPhoneVerification(IdempotencyKey.random(), StartPhoneVerificationRequest(phone, true, document.version, true))
        val verified = reopeningStream<PhoneVerificationStatusEvent>(isTerminal = { it.status != VerificationStatus.PENDING }) {
            auth.streamPhoneVerification(started.verificationToken)
        }.first { it.status != VerificationStatus.PENDING }
        // Never adopt an existing user's tokens, profile or data, even if a random synthetic phone collides.
        check(verified.status == VerificationStatus.VERIFIED && verified.isNewUser == true) { "Bootstrap candidate is not a new account" }
        val registration = requireNotNull(verified.registrationToken?.takeIf { it.isNotBlank() }) { "Missing registration receipt" }
        val registered = auth.register(IdempotencyKey.random(), RegisterRequest(registration, true, document.version, "Europe/Moscow"))
        startSession(registered)
        session.state.first { it is SessionState.SignedIn && it.userId == registered.userId }
        val current = profile.getProfile()
        check(current.userId == registered.userId && current.phone == phone) { "Bootstrap profile identity mismatch" }
        val bytes = fixture().let { if (mode == "fresh") StageBootstrapFixture.fresh(it, now()) else it }
        val accepted = statements.uploadStatement(IdempotencyKey.random(), UploadFile("fixture.ofx", bytes.size.toLong()) { bytes.inputStream() })
        val completed = reopeningStream<ImportEvent>(isTerminal = { it !is ImportEvent.Progress }) {
            statements.streamProgress(accepted.uploadId)
        }.first { it !is ImportEvent.Progress }
        check(completed is ImportEvent.Complete && completed.result.newCount > 0) { "Bootstrap import did not complete with new operations" }
        while (true) {
            val overview = analytics.getAnalytics(period = PeriodTypeCode.MONTH)
            val periods = analytics.listPeriods(period = PeriodTypeCode.MONTH)
            if (overview.hasData && overview.state?.recalculating == false && periods.periods.isNotEmpty()) break
            // Readiness of asynchronous public data, never a phone limit bypass or a second Phone Start.
            delay(500)
        }
        if (mode != "seeded_needs_code") {
            appLock.setPasscode("1234")
            appLock.state.first { it == LockState.Unlocked }
        }
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
