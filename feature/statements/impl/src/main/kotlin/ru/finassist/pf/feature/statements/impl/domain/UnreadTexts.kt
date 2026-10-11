package ru.finassist.pf.feature.statements.impl.domain

import ru.finassist.pf.core.common.time.RussianDates
import java.time.LocalDate

/** Texts of the unread lines list (mockup `ImportResultRepeatSheet`). */
object UnreadTexts {
    /**
     * «В файле operations_2026.ofx эти строки не прочитали — их нет в суммах. Остальные операции загружены.» Lines
     * of several uploads (opened from «Аналитика») name the files above each group instead.
     */
    fun intro(fileNames: List<String>): String =
        if (fileNames.size == 1) {
            "В файле ${fileNames[0]} эти строки не прочитали — их нет в суммах. Остальные операции загружены."
        } else {
            "Эти строки не прочитали — их нет в суммах. Остальные операции загружены."
        }

    /** «Строка 214 · 3 сентября», «Строка 251 · дата не прочитана». */
    fun line(number: Int, date: LocalDate?): String =
        "Строка $number · " + (date?.let { RussianDates.day(it) } ?: "дата не прочитана")
}
