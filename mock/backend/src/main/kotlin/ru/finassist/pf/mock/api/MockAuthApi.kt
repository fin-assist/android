package ru.finassist.pf.mock.api

import ru.finassist.pf.core.api.model.StartPhoneVerificationRequest

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.withLock
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.FieldErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.PhoneVerification
import ru.finassist.pf.core.api.model.PhoneVerificationStatusEvent
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.RegisterResponse
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.VerificationStatus
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.ResponseTiming
import ru.finassist.pf.mock.MockErrorOperation
import ru.finassist.pf.mock.idempotentResponse
import ru.finassist.pf.mock.MockBackend
import ru.finassist.pf.mock.apiError
import ru.finassist.pf.mock.unauthorizedError
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/**
 * Sign-in by callback (api.md 1). The "call" happens by itself [MockConfig.callVerifyDelayMs] after the
 * request is created; the only existing account is [MockConfig.existingPhone].
 */
class MockAuthApi(private val backend: MockBackend) : AuthApi {

    internal data class ConsentReceipt(
        val request: StartPhoneVerificationRequest,
        val consentAcceptedAt: OffsetDateTime,
        val ageConfirmedAt: OffsetDateTime,
        var accountId: String? = null,
    )
    /** In-process server-side evidence, separate from profile and verification working state. */
    internal val consentReceipts = mutableListOf<ConsentReceipt>()
    internal var personalDataConsentVersion: String = MockBackend.CONSENT_VERSION_PD

    private class Verification(val receipt: ConsentReceipt, val createdAt: OffsetDateTime, val expiresAt: OffsetDateTime) {
        val phone get() = receipt.request.phone
        var registrationToken: String? = null
        var verifiedAt: OffsetDateTime? = null
        var consumed = false
    }

    private val verifications = HashMap<String, Verification>()
    private val registrationTokens = HashMap<String, Verification>()
    private val refreshTokens = HashSet<String>()
    private val random = SecureRandom()

    private fun secret(prefix: String): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    override suspend fun startPhoneVerification(key: IdempotencyKey, request: StartPhoneVerificationRequest): PhoneVerification {
        val phone = request.phone
        backend.simulateNetwork()
        if (!PHONE.matches(phone)) {
            throw backend.apiError(
                ErrorCodes.VALIDATION_ERROR, 400,
                details = listOf(AppError.FieldError(PHONE_FIELD, FieldErrorCodes.INVALID_FORMAT, "Номер — +7 и 10 цифр")),
            )
        }
        if (request.pdConsentVersion.count { it !in '\uDC00'..'\uDFFF' } !in 1..64) {
            throw backend.apiError(ErrorCodes.VALIDATION_ERROR, 400,
                details = listOf(AppError.FieldError("pd_consent_version", FieldErrorCodes.INVALID_FORMAT, "Нужна версия согласия")))
        }
        val stored = backend.idempotentResponse("auth.phone:${key.value}", request = request) {
            if (!request.ageConfirmed) throw backend.apiError(ErrorCodes.VALIDATION_ERROR, 400,
                details = listOf(AppError.FieldError("age_confirmed", "REQUIRED", "Подтвердите возраст 14+")))
            if (!request.pdConsentAccepted) throw backend.apiError(ErrorCodes.CONSENT_REQUIRED, 403)
            if (request.pdConsentVersion != personalDataConsentVersion) throw backend.apiError(ErrorCodes.CONSENT_OUTDATED, 422)
            backend.maybeInjectError(MockErrorOperation.PHONE_START)
            val now = backend.now()
            val token = secret("vt_")
            val expiresAt = now.plusNanos(backend.config.verificationTtlMs * 1_000_000)
            backend.mutex.withLock {
                val receipt = ConsentReceipt(request, now, now)
                consentReceipts += receipt
                verifications[token] = Verification(receipt, now, expiresAt)
            }
            PhoneVerification(verificationToken = token, callbackNumber = CALLBACK_NUMBER, expiresAt = expiresAt)
        }
        // `expires_in` is computed on every send, a replay by the same key included (api.md 1.1): floor, not below 0.
        val sentAt = backend.now().toInstant()
        val left = java.time.Duration.between(sentAt, stored.expiresAt.toInstant()).seconds.coerceAtLeast(0)
        return stored.copy(
            expiresIn = left.toInt(), serverTime = sentAt,
            responseTiming = ResponseTiming(serverDate = sentAt, receivedElapsedMillis = backend.elapsedClock?.nowMillis()),
        )
    }

    override fun streamPhoneVerification(verificationToken: String): Flow<PhoneVerificationStatusEvent> =
        backend.droppable(verificationEvents(verificationToken)) { it.status != VerificationStatus.PENDING }

    private fun verificationEvents(verificationToken: String): Flow<PhoneVerificationStatusEvent> = flow {
        val v = backend.mutex.withLock { verifications[verificationToken] }
            ?: throw backend.unauthorizedError(ErrorCodes.INVALID_TOKEN)
        if (v.consumed) throw backend.unauthorizedError(ErrorCodes.INVALID_TOKEN)
        val verifiedAt = v.createdAt.plusNanos(backend.config.callVerifyDelayMs * 1_000_000)
        val now = backend.now()
        if (v.verifiedAt == null && !now.isBefore(v.expiresAt)) {
            emit(PhoneVerificationStatusEvent(status = VerificationStatus.EXPIRED))
            return@flow
        }
        if (now.isBefore(verifiedAt)) {
            emit(PhoneVerificationStatusEvent(status = VerificationStatus.PENDING))
            // A call that would come after the request's end never comes: the open stream ends with `expired`.
            if (verifiedAt.isAfter(v.expiresAt)) {
                delay(java.time.Duration.between(now, v.expiresAt).toMillis() + 1)
                emit(PhoneVerificationStatusEvent(status = VerificationStatus.EXPIRED))
                return@flow
            }
            delay(java.time.Duration.between(now, verifiedAt).toMillis())
        }
        emit(verified(v))
    }

    private suspend fun verified(v: Verification): PhoneVerificationStatusEvent {
        backend.mutex.withLock { if (v.verifiedAt == null) v.verifiedAt = backend.now() }
        if (!backend.now().isBefore(checkNotNull(v.verifiedAt).plusMinutes(15))) throw backend.unauthorizedError(ErrorCodes.INVALID_TOKEN)
        val isNew = v.phone != backend.config.existingPhone && backend.profile.phone != v.phone
        return if (isNew) {
            val token = backend.mutex.withLock {
                v.registrationToken ?: secret("reg_").also { t -> v.registrationToken = t; registrationTokens[t] = v }
            }
            PhoneVerificationStatusEvent(status = VerificationStatus.VERIFIED, isNewUser = true, registrationToken = token)
        } else {
            val tokens = issueTokens()
            backend.mutex.withLock {
                if (backend.profile.userId.isEmpty()) backend.profile = backend.emptyProfile(v.phone, backend.newUserId())
                v.receipt.accountId = backend.profile.userId
            }
            PhoneVerificationStatusEvent(
                status = VerificationStatus.VERIFIED, isNewUser = false,
                accessToken = tokens.accessToken, refreshToken = tokens.refreshToken,
            )
        }
    }

    private suspend fun issueTokens(): TokenPair {
        val refresh = secret("rt_")
        backend.mutex.withLock { refreshTokens += refresh }
        return TokenPair(accessToken = "mock.${UUID.randomUUID()}", refreshToken = refresh)
    }

    override suspend fun register(key: IdempotencyKey, request: RegisterRequest): RegisterResponse {
        backend.simulateNetwork()
        return backend.idempotent("auth.register:${key.value}", request = request) {
            backend.mutex.withLock {
                val verification = registrationTokens[request.registrationToken]
                    ?: throw backend.unauthorizedError(ErrorCodes.INVALID_TOKEN)
                if (!backend.now().isBefore(checkNotNull(verification.verifiedAt).plusMinutes(15))) {
                    throw backend.unauthorizedError(ErrorCodes.TOKEN_EXPIRED)
                }
                if (!request.pdConsentAccepted) throw backend.apiError(ErrorCodes.CONSENT_REQUIRED, 403)
                if (request.pdConsentVersion != verification.receipt.request.pdConsentVersion) throw backend.apiError(ErrorCodes.CONSENT_OUTDATED, 422)
                val phone = verification.phone
                val refresh = secret("rt_")
                val access = "mock.${UUID.randomUUID()}"
                val userId = backend.newUserId()
                refreshTokens += refresh
                registrationTokens.remove(request.registrationToken)
                verifications.values.filter { it.phone == phone }.forEach { it.consumed = true }
                backend.profile = backend.emptyProfile(phone, userId).copy(timezone = request.timezone ?: "Europe/Moscow")
                verification.receipt.accountId = userId
                RegisterResponse(accessToken = access, refreshToken = refresh, userId = userId)
            }
        }
    }

    override suspend fun refreshTokens(key: IdempotencyKey, refreshToken: String): TokenPair {
        backend.simulateNetwork()
        return backend.idempotent("auth.token:${key.value}", request = refreshToken) {
            val known = backend.mutex.withLock { refreshTokens.remove(refreshToken) }
            if (!known) throw backend.unauthorizedError(ErrorCodes.TOKEN_REVOKED)
            issueTokens()
        }
    }

    override suspend fun logout(accessToken: String, refreshToken: String) {
        backend.simulateNetwork()
        backend.mutex.withLock { refreshTokens.remove(refreshToken) }
    }

    override suspend fun getConsentDocument(type: ConsentType): ConsentDocument {
        backend.simulateNetwork()
        return when (type) {
            ConsentType.PERSONAL_DATA -> ConsentDocument(type, personalDataConsentVersion, "Согласие на обработку персональных данных", "https://example.ru/legal/pd-consent/$personalDataConsentVersion")
            ConsentType.ASSISTANT -> ConsentDocument(type, MockBackend.CONSENT_VERSION_ASSISTANT, "Согласие на передачу данных помощнику", "https://example.ru/legal/assistant-consent/${MockBackend.CONSENT_VERSION_ASSISTANT}")
            ConsentType.UNKNOWN -> throw backend.apiError(ErrorCodes.NOT_FOUND, 404)
        }
    }

    private companion object {
        val PHONE = Regex("""^\+7\d{10}$""")
        const val PHONE_FIELD = "phone"
        const val CALLBACK_NUMBER = "+78001234567"
    }
}
