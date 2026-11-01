package ru.finassist.pf.feature.statements.impl.domain

import ru.finassist.pf.core.api.model.ImportConfig
import ru.finassist.pf.core.common.time.RussianDates

/** Download copy follows the server's loaded state and half-open suggested range. */
internal object DownloadTexts {
    fun label(config: ImportConfig): String =
        if ((config.loaded?.operationCount ?: 0) > 0) "Скачать новые данные" else "Скачать за год"

    fun instruction(config: ImportConfig): String {
        val range = config.suggestedPeriod
        val period = RussianDates.dayRange(range.from.toLocalDate(), RussianDates.lastDayOf(range), withYear = true)
        return "Нажмите «${label(config)}» — файл за $period сохранится в «Загрузки»"
    }
}
