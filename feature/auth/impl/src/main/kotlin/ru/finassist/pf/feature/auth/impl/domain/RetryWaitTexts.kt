package ru.finassist.pf.feature.auth.impl.domain

import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

internal object RetryWaitTexts {
    fun of(remainingMillis: Long?, retryAt: OffsetDateTime?, zone: ZoneId): String? = when (remainingMillis) {
        0L -> null
        null -> retryAt?.let { "Время повтора по данным сервера — ${CallDeadline.clockText(it.toInstant(), zone)}" }
            ?: "Точное время ожидания неизвестно"
        else -> "Повторить можно через ${durationText(remainingMillis)}"
    }

    private fun durationText(remainingMillis: Long): String {
        val seconds = (remainingMillis - 1L) / 1000L + 1L
        val hours = seconds / 3600L
        val minutes = seconds / 60L % 60L
        val secondsPart = seconds % 60L
        return if (hours == 0L) "%d:%02d".format(Locale.ROOT, minutes, secondsPart)
        else "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, secondsPart)
    }
}
