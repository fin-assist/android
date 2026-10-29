package ru.finassist.pf.e2e

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.VerificationStatus
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.feature.applock.api.AppLock
import ru.finassist.pf.feature.applock.api.LockState
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState

/**
 * Sign-in without the screens, mock e2e build only (`pfe2e://config?sign_in=existing`, FIN-180): for flows that
 * check something after signing in, not the sign-in itself (`.maestro/subflows/fast_login.yaml`).
 *
 * It takes the path the sign-in screens take, through the same code under them, and skips only the typing: the phone
 * verification request and its stream on the fake server (the «call» arrives by itself, as for the screens), the
 * session saved by the auth feature's repository ([startSession] — `SessionRepositoryImpl.start`, as the call screen
 * does for an existing user), then the passcode set by the app lock, as the setup screen does. The app ends signed in
 * and unlocked exactly as after the screens; biometrics stay off, as after «Не сейчас».
 *
 * Only an existing account: a new number needs the personal data consent, which only the screens give — the hook
 * never registers anyone.
 */
class E2eSignIn(
    private val authApi: AuthApi,
    private val session: SessionRepository,
    private val startSession: suspend (TokenPair) -> Unit,
    private val appLock: AppLock,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    /** Signs [phone] in and sets [passcode]; returns null on success, otherwise what went wrong (shown to the flow). */
    suspend fun signIn(phone: String, passcode: String): String? = try {
        withTimeout(timeoutMs) { attempt(phone, passcode) }
    } catch (e: TimeoutCancellationException) {
        "sign-in did not finish in $timeoutMs ms"
    } catch (e: NoSuchElementException) {
        "the phone verification stream ended without a result"
    } catch (e: AppError) {
        // No message: an error of the fake server may carry request data. Its type is what the flow needs.
        "sign-in failed: ${e::class.simpleName}"
    }

    private suspend fun attempt(phone: String, passcode: String): String? {
        if (session.state.first() is SessionState.SignedIn) return "already signed in: start the flow with clear_state.yaml"
        val document = authApi.getConsentDocument(ru.finassist.pf.core.api.model.ConsentType.PERSONAL_DATA)
        val verification = authApi.startPhoneVerification(IdempotencyKey.random(),
            ru.finassist.pf.core.api.model.StartPhoneVerificationRequest(phone, true, document.version, true))
        val result = authApi.streamPhoneVerification(verification.verificationToken)
            .first { it.status != VerificationStatus.PENDING }
        if (result.status != VerificationStatus.VERIFIED) return "phone verification ended as ${result.status.code}"
        if (result.isNewUser != false) return "not an existing account: a new number signs up through the screens (consent)"
        val access = result.accessToken
        val refresh = result.refreshToken
        if (access == null || refresh == null) return "verified without tokens"
        startSession(TokenPair(access, refresh))
        session.state.first { it is SessionState.SignedIn }
        appLock.setPasscode(passcode)
        appLock.state.first { it == LockState.Unlocked }
        return null
    }

    private companion object {
        /** The call takes 1.5 s in the e2e build; the rest are a few fake requests and the Keystore. */
        const val TIMEOUT_MS = 30_000L
    }
}
