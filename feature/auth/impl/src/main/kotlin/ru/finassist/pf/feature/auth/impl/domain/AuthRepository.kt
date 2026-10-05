package ru.finassist.pf.feature.auth.impl.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.network.api.PfApi
import ru.finassist.pf.core.network.client.PfJson
import ru.finassist.pf.core.network.client.apiCall
import ru.finassist.pf.core.network.codes.ConsentType
import ru.finassist.pf.core.network.codes.VerificationStatus
import ru.finassist.pf.core.network.dto.ConsentDocumentDto
import ru.finassist.pf.core.network.dto.PhoneVerificationStatusEventDto
import ru.finassist.pf.core.network.dto.RegisterRequestDto
import ru.finassist.pf.core.network.dto.StartPhoneVerificationRequestDto
import ru.finassist.pf.core.network.dto.TokenPairDto
import ru.finassist.pf.core.network.sse.SseClient
import ru.finassist.pf.core.network.sse.asStreamError
import java.time.OffsetDateTime
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class Verification(val token: String, val callbackNumber: String, val expiresAt: OffsetDateTime)

sealed interface VerificationEvent {
    data object Pending : VerificationEvent
    data class LoggedIn(val userId: String) : VerificationEvent
    data class NewUser(val registrationToken: String) : VerificationEvent
    data object Expired : VerificationEvent
    data class Failed(val error: AppError) : VerificationEvent
}

/** api.md §1: phone → call-back → tokens or registration. */
@Singleton
class AuthRepository @Inject constructor(
    private val api: PfApi,
    private val sse: SseClient,
    private val session: SessionRepositoryImpl,
) {
    /** One idempotency key per logical action; retried on transport failure, replaced when the input changes. */
    suspend fun startVerification(phone: String, idempotencyKey: String): Verification = withContext(Dispatchers.IO) {
        apiCall {
            val r = api.startPhoneVerification(idempotencyKey, StartPhoneVerificationRequestDto(phone))
            Verification(r.verificationToken, r.callbackNumber, OffsetDateTime.parse(r.expiresAt))
        }
    }

    /**
     * Status stream with reconnection: every reconnect gets the full state first (contract «SSE»), so a dropped
     * connection only costs a retry. Terminates on verified / expired / permanent error.
     */
    fun observeVerification(verification: Verification, phone: String): Flow<VerificationEvent> = flow {
        var attempt = 0
        while (true) {
            var terminal = false
            try {
                sse.stream("v1/auth/phone/events", headers = mapOf("X-Verification-Token" to verification.token), noAuth = true).collect { event ->
                    when (event.name) {
                        "status" -> {
                            val s = PfJson.decodeFromString(PhoneVerificationStatusEventDto.serializer(), event.data)
                            when (VerificationStatus.fromWire(s.status)) {
                                VerificationStatus.Pending -> emit(VerificationEvent.Pending)
                                VerificationStatus.Expired -> { terminal = true; emit(VerificationEvent.Expired) }
                                VerificationStatus.Verified -> {
                                    terminal = true
                                    val registrationToken = s.registrationToken
                                    val access = s.accessToken
                                    val refresh = s.refreshToken
                                    when {
                                        s.isNewUser == true && registrationToken != null -> emit(VerificationEvent.NewUser(registrationToken))
                                        access != null && refresh != null -> emit(VerificationEvent.LoggedIn(finishLogin(phone, TokenPairDto(access, refresh))))
                                        else -> emit(VerificationEvent.Failed(AppError.Malformed(IllegalStateException("verified without tokens"))))
                                    }
                                }
                                VerificationStatus.Unknown -> { terminal = true; emit(VerificationEvent.Failed(AppError.Http(200, s.status, "unknown status"))) }
                            }
                        }
                        "error" -> { terminal = true; val e = event.asStreamError(); emit(VerificationEvent.Failed(AppError.Http(200, e.code, e.message))) }
                    }
                }
            } catch (e: AppError) {
                // 401 → the request is gone (consumed or expired on the server) — do not loop.
                if (e is AppError.Unauthorized) { emit(VerificationEvent.Expired); return@flow }
                if (!e.isRetryable && e !is AppError.Offline) { emit(VerificationEvent.Failed(e)); return@flow }
            }
            if (terminal) return@flow
            if (OffsetDateTime.now().isAfter(verification.expiresAt)) { emit(VerificationEvent.Expired); return@flow }
            attempt++
            delay(minOf(1000L shl minOf(attempt, 4), 10_000L))
        }
    }

    /** Tokens come without a user id (api.md 1.2): fetch the profile to learn it. */
    private suspend fun finishLogin(phone: String, pair: TokenPairDto): String = withContext(Dispatchers.IO) {
        session.storeTokens(pair)
        val profile = apiCall { api.getProfile() }
        session.onLoggedIn(profile.userId, profile.phone, pair)
        profile.userId
    }

    suspend fun consentDocument(): ConsentDocumentDto = withContext(Dispatchers.IO) { apiCall { api.getConsentDocument(ConsentType.PERSONAL_DATA) } }

    suspend fun register(registrationToken: String, consentVersion: String, phone: String, idempotencyKey: String): String = withContext(Dispatchers.IO) {
        apiCall {
            val r = api.register(idempotencyKey, RegisterRequestDto(registrationToken, pdConsentAccepted = true, pdConsentVersion = consentVersion, timezone = TimeZone.getDefault().id))
            session.onLoggedIn(r.userId, phone, TokenPairDto(r.accessToken, r.refreshToken))
            r.userId
        }
    }

    fun newKey(): String = UUID.randomUUID().toString()
}
