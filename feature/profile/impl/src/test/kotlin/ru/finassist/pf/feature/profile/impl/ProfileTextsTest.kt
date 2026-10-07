package ru.finassist.pf.feature.profile.impl

import ru.finassist.pf.feature.profile.impl.domain.ProfileTexts
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileTextsTest {
    @Test
    fun phone() {
        assertEquals("+7 916 123-45-67", ProfileTexts.phone("+79161234567"))
        assertEquals("+447700900123", ProfileTexts.phone("+447700900123"))
        assertEquals("Не загружена", ProfileTexts.statement(null))
        assertEquals("Версия 0.1 · Данные хранятся в России", ProfileTexts.footer("0.1.0-mock"))
        assertEquals("Версия 0.1.2 · Данные хранятся в России", ProfileTexts.footer("0.1.2"))
        assertEquals("Данные хранятся в России", ProfileTexts.footer(null))
    }
}
