package ru.finassist.pf.mock

import org.junit.Test
import ru.finassist.pf.core.common.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

/** Days and months of the fake server follow `profile.timezone`, like analytics on the real one (FIN-92). */
class ProfileZoneTest {
    private val backend = testBackend(clock = TestClock("2026-10-06T23:30:00+03:00")).apply {
        load(statement("2026-09-01", "2026-09-30", trn("late", "2026-09-30T23:30", 500, "OZON", "Маркетплейсы")))
    }
    private val late get() = backend.ledger.all.single { it.fitId == "late" }

    @Test fun `an operation late in the evening in Moscow is the next day in the profile zone`() {
        assertEquals(LocalDate.of(2026, 9, 30), backend.operationScope.dayOf(late))

        backend.profile = backend.profile.copy(timezone = "Asia/Vladivostok")
        assertEquals(ZoneId.of("Asia/Vladivostok"), backend.operationScope.zone)
        assertEquals(LocalDate.of(2026, 10, 1), backend.operationScope.dayOf(late))
    }

    @Test fun `an unknown profile zone falls back to Moscow`() {
        backend.profile = backend.profile.copy(timezone = "Mars/Olympus_Mons")
        assertEquals(Clock.MOSCOW, backend.profileZone())
    }
}
