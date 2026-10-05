package ru.finassist.pf.mock.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.withLock
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.PhoneVerification
import ru.finassist.pf.core.api.model.PhoneVerificationStatusEvent
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.RegisterResponse
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.VerificationStatus
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.mock.MockBackend
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/**
 * Sign-in by callback (api.md 1). The "call" happens by itself [MockConfig.callVerifyDelayMs] after the
 * request is created; the only existing account is [MockConfig.existingPhone].
 */
class MockAuthApi(private val backend: MockBackend) : AuthApi {

    private class Verification(val phone: String, val createdAt: OffsetDateTime, val expiresAt: OffsetDateTime) {
        var registrationToken: String? = null
        var consumed = false
    }

    private val verifications = HashMap<String, Verification>()
    private val registrationTokens = HashMap<String, String>() // token → phone
    private val refreshTokens = HashSet<String>()
    private val random = SecureRandom()

    private fun secret(prefix: String): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    override suspend fun startPhoneVerification(key: IdempotencyKey, phone: String): PhoneVerification {
        backend.simulateNetwork()
        if (!PHONE.matches(phone)) throw AppError.Api(ErrorCodes.INVALID_PHONE, 400)
        return backend.idempotent("auth.phone:${key.value}") {
            val now = backend.now()
            val token = secret("vt_")
            backend.mutex.withLock { verifications[token] = Verification(phone, now, now.plusMinutes(3)) }
            PhoneVerification(verificationToken = token, callbackNumber = CALLBACK_NUMBER, expiresAt = now.plusMinutes(3))
        }
    }

    override fun streamPhoneVerification(verificationToken: String): Flow<PhoneVerificationStatusEvent> = flow {
        val v = backend.mutex.withLock { verifications[verificationToken] }
            ?: throw AppError.Unauthorized(ErrorCodes.INVALID_TOKEN)
        if (v.consumed) throw AppError.Unauthorized(ErrorCodes.INVALID_TOKEN)
        val verifiedAt = v.createdAt.plusNanos(backend.config.callVerifyDelayMs * 1_000_000)
        val now = backend.now()
        if (now.isAfter(v.expiresAt)) {
            emit(PhoneVerificationStatusEvent(status = VerificationStatus.EXPIRED))
            return@flow
        }
        if (now.isBefore(verifiedAt)) {
            emit(PhoneVerificationStatusEvent(status = VerificationStatus.PENDING))
            delay(java.time.Duration.between(now, verifiedAt).toMillis())
        }
        emit(verified(v))
    }

    private suspend fun verified(v: Verification): PhoneVerificationStatusEvent {
        val isNew = v.phone != backend.config.existingPhone && backend.profile.phone != v.phone
        return if (isNew) {
            val token = v.registrationToken ?: secret("reg_").also { t ->
                v.registrationToken = t
                backend.mutex.withLock { registrationTokens[t] = v.phone }
            }
            PhoneVerificationStatusEvent(status = VerificationStatus.VERIFIED, isNewUser = true, registrationToken = token)
        } else {
            val tokens = issueTokens()
            backend.mutex.withLock {
                if (backend.profile.userId.isEmpty()) backend.profile = backend.emptyProfile(v.phone, backend.newUserId())
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
        return backend.idempotent("auth.register:${key.value}") {
            val phone = backend.mutex.withLock { registrationTokens[request.registrationToken] }
                ?: throw AppError.Unauthorized(ErrorCodes.INVALID_TOKEN)
            if (!request.pdConsentAccepted) throw AppError.Api(ErrorCodes.CONSENT_REQUIRED, 403)
            if (request.pdConsentVersion != MockBackend.CONSENT_VERSION_PD) throw AppError.Api(ErrorCodes.CONSENT_OUTDATED, 422)
            val tokens = issueTokens()
            val userId = backend.newUserId()
            backend.mutex.withLock {
                registrationTokens.remove(request.registrationToken)
                verifications.values.filter { it.phone == phone }.forEach { it.consumed = true }
                backend.profile = backend.emptyProfile(phone, userId).copy(timezone = request.timezone ?: "Europe/Moscow")
            }
            RegisterResponse(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken, userId = userId)
        }
    }

    override suspend fun refreshTokens(key: IdempotencyKey, refreshToken: String): TokenPair {
        backend.simulateNetwork()
        return backend.idempotent("auth.token:${key.value}") {
            val known = backend.mutex.withLock { refreshTokens.remove(refreshToken) }
            if (!known) throw AppError.Unauthorized(ErrorCodes.TOKEN_REVOKED)
            issueTokens()
        }
    }

    override suspend fun logout(refreshToken: String) {
        backend.simulateNetwork()
        backend.mutex.withLock { refreshTokens.remove(refreshToken) }
    }

    override suspend fun getConsentDocument(type: ConsentType): ConsentDocument {
        backend.simulateNetwork()
        return when (type) {
            ConsentType.PERSONAL_DATA -> ConsentDocument(type, MockBackend.CONSENT_VERSION_PD, "Согласие на обработку персональных данных", "https://example.ru/legal/pd-consent/${MockBackend.CONSENT_VERSION_PD}")
            ConsentType.ASSISTANT -> ConsentDocument(type, MockBackend.CONSENT_VERSION_ASSISTANT, "Согласие на передачу данных помощнику", "https://example.ru/legal/assistant-consent/${MockBackend.CONSENT_VERSION_ASSISTANT}")
            ConsentType.UNKNOWN -> throw AppError.Api(ErrorCodes.NOT_FOUND, 404)
        }
    }

    private companion object {
        val PHONE = Regex("""^\+7\d{10}$""")
        const val CALLBACK_NUMBER = "+78001234567"
    }
}
