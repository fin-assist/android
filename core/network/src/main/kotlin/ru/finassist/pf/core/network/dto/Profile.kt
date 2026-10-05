package ru.finassist.pf.core.network.dto

import kotlinx.serialization.Serializable

/** Closed set (OpenAPI `enum`). */
@Serializable
enum class ThemeDto { system, light, dark }

@Serializable
data class ProfileDto(
    val userId: String,
    val phone: String,
    val theme: ThemeDto,
    val timezone: String,
    val assistantConsent: AssistantConsentDto,
    val createdAt: String,
)

@Serializable
data class AssistantConsentDto(val granted: Boolean, val version: String? = null, val currentVersion: String)

@Serializable
data class ProfileUpdateDto(val theme: ThemeDto? = null, val timezone: String? = null)

@Serializable
data class AssistantConsentGrantDto(val version: String)
