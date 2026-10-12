package ru.finassist.pf.feature.statements.impl.domain

import ru.finassist.pf.core.api.model.ErrorCodes

/** Why a file was not accepted and what to do about it (mockups `ImportError*`). */
data class ImportErrorText(val reason: String, val advice: String)

object ImportErrorTexts {
    private const val DOWNLOAD_ADVICE =
        "Скачайте выписку кнопкой «Скачать за год» на экране загрузки или в кабинете Т-Банка выберите «Скачать в OFX»"

    /**
     * [code] from api.md 3.2 / 3.3. A file of the wrong format is named by its extension — «Это файл PDF, а нужен
     * OFX»; without one it is «Это не OFX».
     */
    fun of(code: String, fileName: String, maxFileSizeBytes: Long): ImportErrorText = when (code) {
        ErrorCodes.CSV_NOT_ACCEPTED -> ImportErrorText("Это CSV, а нужен OFX", DOWNLOAD_ADVICE)
        ErrorCodes.WRONG_FORMAT -> {
            val ext = fileName.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 5 && it != fileName }
            ImportErrorText(ext?.let { "Это файл ${it.uppercase()}, а нужен OFX" } ?: "Это не OFX", DOWNLOAD_ADVICE)
        }
        ErrorCodes.WRONG_BANK -> ImportErrorText(
            "Похоже, это выписка не Т-Банка",
            "Пока поддерживаем только выписки Т-Банка в формате OFX",
        )
        ErrorCodes.FILE_TOO_LARGE -> ImportErrorText(
            "Файл больше ${maxFileSizeBytes / (1024 * 1024)} МБ",
            "Скачайте выписку за период покороче — например, по полгода",
        )
        else -> ImportErrorText(
            "Не получилось разобрать операции в файле",
            "Скачайте выписку заново. Если не поможет — напишите нам, разберёмся",
        )
    }
}
