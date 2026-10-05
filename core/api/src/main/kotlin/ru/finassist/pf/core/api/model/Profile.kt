package ru.finassist.pf.core.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.finassist.pf.core.common.time.ApiDateTime

/** Closed set. */
@Serializable
enum class Theme {
    @SerialName("system") SYSTEM,
    @SerialName("light") LIGHT,
    @SerialName("dark") DARK,
}

@Serializable
data class Profile(
    val userId: String,
    /** Full E.164 number — it is the login and is shown in full (a11y-14); formatting is the client's job. */
    val phone: String,
    val theme: Theme,
    val timezone: String,
    val assistantConsent: AssistantConsent,
    val createdAt: ApiDateTime,
)

@Serializable
data class AssistantConsent(
    val granted: Boolean,
    val version: String? = null,
    val currentVersion: String,
) {
    /** Consent must be (re)given when it was never granted or the document version moved on. */
    val needsRenewal: Boolean get() = !granted || (version != null && version != currentVersion)
}

@Serializable
data class ProfileUpdate(
    val theme: Theme? = null,
    val timezone: String? = null,
)

@Serializable
data class AssistantConsentGrant(val version: String)
