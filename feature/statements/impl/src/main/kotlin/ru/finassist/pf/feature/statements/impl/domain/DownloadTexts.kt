package ru.finassist.pf.feature.statements.impl.domain

import ru.finassist.pf.core.api.model.ImportConfig
import ru.finassist.pf.core.common.time.RussianDates

/** Download copy follows the server's loaded state and half-open suggested range. */
internal object DownloadTexts {
    fun label(config: ImportConfig): String =
        if ((config.loaded?.operationCount ?: 0) > 0) "Скачать новые данные" else "Скачать за год"

    fun instruction(config: ImportConfig): String {
        val range = config.suggestedPeriod
        val from = range.from.toLocalDate()
        val last = RussianDates.lastDayOf(range)
        if (last < from) return "Нажмите «${label(config)}» — файл сохранится в «Загрузки»"
        val period = RussianDates.dayRange(from, last, withYear = true)
        return "Нажмите «${label(config)}» — файл за $period сохранится в «Загрузки»"
    }
}
