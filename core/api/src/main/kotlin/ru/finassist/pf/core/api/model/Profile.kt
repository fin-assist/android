package ru.finassist.pf.core.api.model

import kotlinx.serialization.Serializable
import ru.finassist.pf.core.common.time.ApiDateTime

/**
 * Open code (api.md 2.1, FIN-82): a theme this client does not know is read as [SYSTEM] — it must not break the whole
 * profile. The picker offers exactly these three.
 */
@Serializable(with = Theme.Serializer::class)
enum class Theme(override val code: String) : ApiCode {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    object Serializer : OpenEnumSerializer<Theme>("Theme", entries, SYSTEM)
}

@Serializable
data class Profile(
    val userId: String,
    /** Full E.164 number — it is the login and is shown in full (a11y-14); formatting is the client's job. */
    val phone: String,
    val theme: Theme,
    val timezone: String,
    /** One element per type (api.md 2.1, FIN-82); an element of an unknown type is kept but not used. */
    val consents: List<Consent> = emptyList(),
    val createdAt: ApiDateTime,
) {
    /** The assistant consent; no element — not given. */
    val assistantConsent: Consent? get() = consents.firstOrNull { it.type == ConsentType.ASSISTANT }

    /** The profile with [consent] in place of the element of its type. */
    fun withConsent(consent: Consent): Profile = copy(consents = consents.filter { it.type != consent.type } + consent)
}

/** An element of `profile.consents`; [type] is the same open code as in 1.6, an unknown one is never looked up. */
@Serializable
data class Consent(
    val type: ConsentType,
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
