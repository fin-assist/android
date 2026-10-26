package ru.finassist.pf.feature.auth.impl.domain

import ru.finassist.pf.core.common.time.pluralize
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/** Text of the «too many attempts» state on the call screen (`CodeLocked`). */
object CallLockText {
    /** Remaining duration is anchored to the received response; without it show no precise countdown. */
    fun withRemaining(retryAt: OffsetDateTime?, remainingMillis: Long?, zone: ZoneId): String {
        val base = "Слишком много попыток. Позвонить можно будет"
        val local = retryAt?.atZoneSameInstant(zone)
        val absolute = local?.let { ", в %02d:%02d".format(Locale.ROOT, it.hour, it.minute) }.orEmpty()
        if (remainingMillis == null) return "$base позже$absolute"
        val minutes = ((remainingMillis.coerceAtLeast(0) + 59_999) / 60_000).coerceAtLeast(1)
        return "$base через $minutes ${pluralize(minutes.toInt(), "минуту", "минуты", "минут")}$absolute"
    }


    /**
     * «Слишком много попыток. Позвонить можно будет через 10 минут, в 14:32»: minutes left rounded up (at least
     * one) and the local time of [retryAt]. Without [retryAt] (the server did not say) — «…можно будет позже».
     */
    fun of(retryAt: OffsetDateTime?, now: OffsetDateTime, zone: ZoneId): String {
        val base = "Слишком много попыток. Позвонить можно будет"
        if (retryAt == null) return "$base позже"
        val seconds = Duration.between(now, retryAt).seconds.coerceAtLeast(0)
        val minutes = ((seconds + 59) / 60).toInt().coerceAtLeast(1)
        val local = retryAt.atZoneSameInstant(zone)
        val time = "%02d:%02d".format(Locale.ROOT, local.hour, local.minute)
        return "$base через $minutes ${pluralize(minutes, "минуту", "минуты", "минут")}, в $time"
    }
}
