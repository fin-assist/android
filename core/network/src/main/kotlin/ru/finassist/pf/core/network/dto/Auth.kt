package ru.finassist.pf.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class StartPhoneVerificationRequestDto(val phone: String)

@Serializable
data class PhoneVerificationDto(val verificationToken: String, val callbackNumber: String, val expiresAt: String)

/** `status` event of `/v1/auth/phone/events`. */
@Serializable
data class PhoneVerificationStatusEventDto(
    val status: String,
    val isNewUser: Boolean? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val registrationToken: String? = null,
)

@Serializable
data class RegisterRequestDto(
    val registrationToken: String,
    val pdConsentAccepted: Boolean,
    val pdConsentVersion: String,
    val timezone: String? = null,
)

@Serializable
data class RegisterResponseDto(val accessToken: String, val refreshToken: String, val userId: String)

@Serializable
data class RefreshTokenRequestDto(val refreshToken: String)

@Serializable
data class TokenPairDto(val accessToken: String, val refreshToken: String)

@Serializable
data class ConsentDocumentDto(val type: String, val version: String, val title: String, val url: String)
