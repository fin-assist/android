package ru.finassist.pf.core.api.model

import kotlinx.serialization.Serializable
import ru.finassist.pf.core.common.time.ApiDateTime

@Serializable
data class StartPhoneVerificationRequest(val phone: String)

@Serializable
data class PhoneVerification(
    /** Secret of the verification request; kept in memory only and sent in `X-Verification-Token`. */
    val verificationToken: String,
    /** Toll-free number the user calls from their phone (E.164). Comes from the server, never hard-coded. */
    val callbackNumber: String,
    val expiresAt: ApiDateTime,
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
