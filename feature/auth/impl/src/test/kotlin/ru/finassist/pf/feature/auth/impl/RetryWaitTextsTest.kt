package ru.finassist.pf.feature.auth.impl

import ru.finassist.pf.feature.auth.impl.domain.RetryWaitTexts
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetryWaitTextsTest {
    private val zone = ZoneId.of("Europe/Moscow")

    @Test fun `partial seconds round up until the countdown ends`() {
        assertEquals("Повторить можно через 0:01", RetryWaitTexts.of(1L, null, zone))
        assertEquals("Повторить можно через 0:01", RetryWaitTexts.of(1_000L, null, zone))
        assertEquals("Повторить можно через 0:02", RetryWaitTexts.of(1_001L, null, zone))
        assertEquals("Повторить можно через 1:00", RetryWaitTexts.of(59_999L, null, zone))
        assertNull(RetryWaitTexts.of(0L, null, zone))
    }

    @Test fun `waits of an hour or longer show hours`() {
        assertEquals("Повторить можно через 59:59", RetryWaitTexts.of(3_599_000L, null, zone))
        assertEquals("Повторить можно через 1:00:00", RetryWaitTexts.of(3_599_001L, null, zone))
        assertEquals("Повторить можно через 1:00:00", RetryWaitTexts.of(3_600_000L, null, zone))
        assertEquals("Повторить можно через 24:00:00", RetryWaitTexts.of(86_400_000L, null, zone))
    }

    @Test fun `an unanchored deadline uses the server time or explains that the wait is unknown`() {
        val retryAt = OffsetDateTime.parse("2026-11-26T12:23:00Z")
        assertEquals("Время повтора по данным сервера — 15:23", RetryWaitTexts.of(null, retryAt, zone))
        assertEquals("Точное время ожидания неизвестно", RetryWaitTexts.of(null, null, zone))
    }
}
