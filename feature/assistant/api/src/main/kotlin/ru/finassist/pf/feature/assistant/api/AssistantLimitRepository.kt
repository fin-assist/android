package ru.finassist.pf.feature.assistant.api

data class AssistantLimit(val remaining: Int, val dailyMax: Int, val resetsAt: String)

/** `/v1/assistant/limit` for the analytics card and the profile row. */
interface AssistantLimitRepository {
    suspend fun limit(): AssistantLimit
}
