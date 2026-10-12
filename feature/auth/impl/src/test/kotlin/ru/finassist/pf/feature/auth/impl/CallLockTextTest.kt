package ru.finassist.pf.feature.auth.impl

import ru.finassist.pf.feature.auth.impl.domain.CallLockText
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class CallLockTextTest {
    private val moscow = ZoneId.of("Europe/Moscow")
    private val now = OffsetDateTime.parse("2026-09-25T14:22:00+03:00")

    @Test fun `minutes left and the local time`() = assertEquals(
        "Слишком много попыток. Позвонить можно будет через 10 минут, в 14:32",
        CallLockText.of(now.plusMinutes(10), now, moscow),
    )

    @Test fun `partial minutes round up and the noun agrees`() {
        assertEquals("Слишком много попыток. Позвонить можно будет через 1 минуту, в 14:22", CallLockText.of(now.plusSeconds(20), now, moscow))
        assertEquals("Слишком много попыток. Позвонить можно будет через 2 минуты, в 14:23", CallLockText.of(now.plusSeconds(61), now, moscow))
    }

    @Test fun `a time already past still says one minute`() =
        assertEquals("Слишком много попыток. Позвонить можно будет через 1 минуту, в 14:21", CallLockText.of(now.minusMinutes(1), now, moscow))

    @Test fun `the time is shown in the given zone`() = assertEquals(
        "Слишком много попыток. Позвонить можно будет через 10 минут, в 14:32",
        CallLockText.of(OffsetDateTime.parse("2026-09-25T11:32:00Z"), now, moscow),
    )

    @Test fun `without a retry time`() =
        assertEquals("Слишком много попыток. Позвонить можно будет позже", CallLockText.of(null, now, moscow))
}
