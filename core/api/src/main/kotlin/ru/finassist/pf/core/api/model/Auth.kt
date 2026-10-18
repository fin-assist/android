package ru.finassist.pf.core.api.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import ru.finassist.pf.core.common.time.ApiDateTime
import java.time.Instant

@Serializable
data class StartPhoneVerificationRequest(val phone: String)

@Serializable
data class PhoneVerification(
    /** Secret of the verification request; kept in memory only and sent in `X-Verification-Token`. */
    val verificationToken: String,
    /**
     * Number the user calls from their phone (E.164). Comes from the server, never hard-coded. The call is paid at
     * the subscriber's rate: the client never promises it is free (api.md 1.1).
     */
    val callbackNumber: String,
    /** End of the request by the server's clock. Not used for the countdown on the device (see [expiresIn]). */
    val expiresAt: ApiDateTime,
    /**
     * Seconds left at the moment the response was sent (≥ 0). The device deadline is the device time when the
     * response arrived plus this (api.md 1.1, «Срок на устройстве»). Null from an older server: then [serverTime].
     */
    val expiresIn: Int? = null,
    /**
     * Server time from the `Date` header of the response; filled by the HTTP client, not part of the body. Fallback
     * deadline: arrival + ([expiresAt] − this). Null when the header is missing or unreadable, and in the fake backend.
     */
    @Transient val serverTime: Instant? = null,
)

@Serializable(with = VerificationStatus.Serializer::class)
enum class VerificationStatus(override val code: String) : ApiCode {
    PENDING("pending"), VERIFIED("verified"), EXPIRED("expired"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<VerificationStatus>("VerificationStatus", entries, UNKNOWN)
}

/** `status` event of the phone verification stream. Unknown status = request failed, start over. */
@Serializable
data class PhoneVerificationStatusEvent(
    val status: VerificationStatus,
    val isNewUser: Boolean? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val registrationToken: String? = null,
)

@Serializable
data class RegisterRequest(
    val registrationToken: String,
    val pdConsentAccepted: Boolean,
    val pdConsentVersion: String,
    val timezone: String? = null,
)

@Serializable
data class RegisterResponse(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
)

@Serializable
data class RefreshTokenRequest(val refreshToken: String)

@Serializable
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
)

@Serializable(with = ConsentType.Serializer::class)
enum class ConsentType(override val code: String) : ApiCode {
    PERSONAL_DATA("personal_data"), ASSISTANT("assistant"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<ConsentType>("ConsentType", entries, UNKNOWN)
}

@Serializable
data class ConsentDocument(
    val type: ConsentType,
    val version: String,
    val title: String,
    val url: String,
)
