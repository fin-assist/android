package ru.finassist.pf.feature.assistant.api

import kotlinx.serialization.Serializable

/** Routes of the assistant feature. */
object AssistantRoutes {
    /**
     * The single continuous chat. [transferMode] — API code of the mode chosen on Analytics (`with` /
     * `without`), sent with each question (api.md 7.1); `null` means `with`. A plain string because the
     * enum has a custom open-set serializer that typed navigation cannot map.
     */
    @Serializable
    data class Chat(val transferMode: String? = null)

    /**
     * Consent to pass data to the assistant (from the chat or the profile switch). On success the previous
     * screen receives [CONSENT_RESULT] = "true" through the navigator's result mechanism.
     */
    @Serializable
    data object Consent

    const val CONSENT_RESULT = "assistant.consent"
}
