package ru.finassist.pf.feature.assistant.impl.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.finassist.pf.core.api.model.AssistantLimit
import java.time.OffsetDateTime
import java.time.ZoneId

class AssistantLimitSummaryTest {
    private val moscow = ZoneId.of("Europe/Moscow")
    private val reset = OffsetDateTime.parse("2026-10-10T00:00:00+03:00")

    @Test
    fun usesLatestRemainderAndServerDailyMaximum() {
        val limit = AssistantLimit(remaining = 5, dailyMax = 7, resetsAt = reset)
        assertEquals(
            "Осталось 3 из 7 вопросов. Лимит обновится в 00:00 по Москве (02:00 по вашему времени).",
            assistantLimitSummary(3, limit, ZoneId.of("Asia/Yekaterinburg")),
        )
    }

    @Test
    fun keepsKnownRemainderWhileWaitingForLimitMetadata() {
        assertEquals(
            "Осталось вопросов: 0. Суточный лимит и время обновления пока недоступны.",
            assistantLimitSummary(0, null, moscow),
        )
    }
}
