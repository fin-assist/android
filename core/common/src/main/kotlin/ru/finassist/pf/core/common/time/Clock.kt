package ru.finassist.pf.core.common.time

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Injectable clock so view models, screens, the mock backend and tests agree on "now". Never `LocalDate.now()` or
 * `OffsetDateTime.now()` in app code: the e2e build pins this clock (`E2E_NOW`), not the device's (FIN-71).
 *
 * In the app [zone] is the report zone ([ReportZone]): `profile.timezone` once known, so days, months and "today"
 * match analytics, not the device (FIN-92). It may change while the process lives; read it when needed, do not keep it.
 */
interface Clock {
    fun now(): OffsetDateTime
    val zone: ZoneId

    /** Today in [zone]. */
    fun today(): LocalDate = now().toLocalDate()

    companion object {
        val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")
    }
}

/** [zoneOf] is read on every call: the zone may change while the process lives. */
class SystemClock(private val zoneOf: () -> ZoneId = ZoneId::systemDefault) : Clock {
    constructor(zone: ZoneId) : this({ zone })

    override val zone: ZoneId get() = zoneOf()
    override fun now(): OffsetDateTime = OffsetDateTime.now(zone)
}

/**
 * "Now" that equals [start] at construction and then runs at real speed. UI tests pin the date this way so
 * periods and windows over a fixed statement stay the same whenever they run, while timeouts and delays
 * measured with this clock still elapse.
 */
class ShiftedClock(
    start: OffsetDateTime,
    private val zoneOf: () -> ZoneId = ZoneId::systemDefault,
    private val realNow: () -> Instant = Instant::now,
) : Clock {
    constructor(start: OffsetDateTime, zone: ZoneId, realNow: () -> Instant = Instant::now) : this(start, { zone }, realNow)

    private val offset: Duration = Duration.between(realNow(), start.toInstant())

    override val zone: ZoneId get() = zoneOf()

    override fun now(): OffsetDateTime = OffsetDateTime.ofInstant(realNow().plus(offset), zone)
}

/**
 * The same "now" in a fixed [zone]. Composables get the clock this way (`LocalPfClock`): a new zone gives a new
 * value of the composition local, so what was grouped by day is regrouped.
 */
fun Clock.withZone(zone: ZoneId): Clock {
    val base = this
    return object : Clock {
        override val zone: ZoneId = zone
        override fun now(): OffsetDateTime = base.now().atZoneSameInstant(zone).toOffsetDateTime()
    }
}
