package ru.finassist.pf.core.common.time

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
