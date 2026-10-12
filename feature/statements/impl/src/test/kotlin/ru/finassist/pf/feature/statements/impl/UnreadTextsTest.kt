package ru.finassist.pf.feature.statements.impl

import ru.finassist.pf.feature.statements.impl.domain.UnreadTexts
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class UnreadTextsTest {
    @Test fun `one file is named in the intro`() = assertEquals(
        "В файле operations_2026.ofx эти строки не прочитали — их нет в суммах. Остальные операции загружены.",
        UnreadTexts.intro(listOf("operations_2026.ofx")),
    )

    @Test fun `several files are named above their groups`() = assertEquals(
        "Эти строки не прочитали — их нет в суммах. Остальные операции загружены.",
        UnreadTexts.intro(listOf("a.ofx", "b.ofx")),
    )

    @Test fun `a file without a single read operation does not claim the rest was loaded`() = assertEquals(
        "В файле broken.ofx эти строки не прочитали — их нет в суммах.",
        UnreadTexts.intro(listOf("broken.ofx"), othersLoaded = false),
    )

    @Test fun `a line with and without its date`() {
        assertEquals("Строка 214 · 3 сентября", UnreadTexts.line(214, LocalDate.of(2026, 9, 3)).replace(' ', ' '))
        assertEquals("Строка 251 · дата не прочитана", UnreadTexts.line(251, null))
    }
}
