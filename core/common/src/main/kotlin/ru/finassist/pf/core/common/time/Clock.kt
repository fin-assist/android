package ru.finassist.pf.core.common.time

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** Injectable clock so view models, the mock backend and tests agree on "now". */
interface Clock {
    fun now(): OffsetDateTime
    val zone: ZoneId

    companion object {
        val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")
    }
}

class SystemClock(override val zone: ZoneId = ZoneId.systemDefault()) : Clock {
    override fun now(): OffsetDateTime = OffsetDateTime.now(zone)
}

/**
 * "Now" that equals [start] at construction and then runs at real speed. UI tests pin the date this way so
 * periods and windows over a fixed statement stay the same whenever they run, while timeouts and delays
 * measured with this clock still elapse.
 */
class ShiftedClock(
    start: OffsetDateTime,
    override val zone: ZoneId = ZoneId.systemDefault(),
    private val realNow: () -> Instant = Instant::now,
) : Clock {
    private val offset: Duration = Duration.between(realNow(), start.toInstant())

    override fun now(): OffsetDateTime = OffsetDateTime.ofInstant(realNow().plus(offset), zone)
}
