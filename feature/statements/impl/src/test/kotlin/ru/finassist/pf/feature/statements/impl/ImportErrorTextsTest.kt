package ru.finassist.pf.feature.statements.impl

import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.feature.statements.impl.domain.ImportErrorTexts
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportErrorTextsTest {
    private val tenMb = 10L * 1024 * 1024
    private val download = "Скачайте выписку на экране загрузки или в кабинете Т-Банка выберите «Скачать в OFX»"

    @Test fun `a wrong format is named by the extension`() {
        val t = ImportErrorTexts.of(ErrorCodes.WRONG_FORMAT, "statement_09_2026.pdf", tenMb)
        assertEquals("Это файл PDF, а нужен OFX", t.reason)
        assertEquals(download, t.advice)
        assertEquals("Это файл TXT, а нужен OFX", ImportErrorTexts.of(ErrorCodes.WRONG_FORMAT, "notes.txt", tenMb).reason)
    }

    @Test fun `a wrong format without a usable extension`() {
        assertEquals("Это не OFX", ImportErrorTexts.of(ErrorCodes.WRONG_FORMAT, "statement", tenMb).reason)
        assertEquals("Это не OFX", ImportErrorTexts.of(ErrorCodes.WRONG_FORMAT, "statement.", tenMb).reason)
        assertEquals("Это не OFX", ImportErrorTexts.of(ErrorCodes.WRONG_FORMAT, "archive.backup2026", tenMb).reason)
    }

    @Test fun `csv, another bank and size`() {
        assertEquals("Это CSV, а нужен OFX", ImportErrorTexts.of(ErrorCodes.CSV_NOT_ACCEPTED, "a.csv", tenMb).reason)
        assertEquals(download, ImportErrorTexts.of(ErrorCodes.CSV_NOT_ACCEPTED, "a.csv", tenMb).advice)
        val bank = ImportErrorTexts.of(ErrorCodes.WRONG_BANK, "a.ofx", tenMb)
        assertEquals("Похоже, это выписка не Т-Банка", bank.reason)
        assertEquals("Пока поддерживаем только выписки Т-Банка в формате OFX", bank.advice)
        val size = ImportErrorTexts.of(ErrorCodes.FILE_TOO_LARGE, "a.ofx", tenMb)
        assertEquals("Файл больше 10 МБ", size.reason)
        assertEquals("Скачайте выписку за период покороче — например, по полгода", size.advice)
        assertEquals("Файл больше 20 МБ", ImportErrorTexts.of(ErrorCodes.FILE_TOO_LARGE, "a.ofx", 2 * tenMb).reason)
    }

    @Test fun `any other failure`() {
        val t = ImportErrorTexts.of(ErrorCodes.PROCESSING_FAILED, "a.ofx", tenMb)
        assertEquals("Не получилось разобрать операции в файле", t.reason)
    }
}
