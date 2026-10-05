package ru.finassist.pf.feature.assistant.api

import kotlinx.serialization.Serializable

object AssistantRoutes {
    /** Chat; [transferMode] is the analytics mode to send with questions. */
    @Serializable data class Chat(val transferMode: String = "with")
}
