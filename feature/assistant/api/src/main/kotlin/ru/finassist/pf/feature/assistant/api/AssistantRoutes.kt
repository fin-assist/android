package ru.finassist.pf.feature.assistant.api

import kotlinx.serialization.Serializable

object AssistantRoutes {
    /** Chat; [transferMode] is the analytics mode to send with questions. */
    @Serializable data class Chat(val transferMode: String = "with")
    /** Consent to send data to the assistant; shown before the first question and after a revoke. */
    @Serializable data object AiConsent
}
