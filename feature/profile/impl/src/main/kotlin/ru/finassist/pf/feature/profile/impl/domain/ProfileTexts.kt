package ru.finassist.pf.feature.profile.impl.domain

import ru.finassist.pf.core.api.model.StatementsSummary
import ru.finassist.pf.core.api.model.Theme
import ru.finassist.pf.core.common.time.countWithNoun

object ProfileTexts {
    /** `+79161234567` → `+7 916 123-45-67`; anything else is shown as is. */
    fun phone(e164: String): String {
        val d = e164.filter(Char::isDigit)
        if (d.length != 11 || !e164.startsWith("+7")) return e164
        return "+7 ${d.substring(1, 4)} ${d.substring(4, 7)}-${d.substring(7, 9)}-${d.substring(9, 11)}"
    }

    fun theme(t: Theme): String = when (t) {
        Theme.SYSTEM -> "Как в системе"
        Theme.LIGHT -> "Светлая"
        Theme.DARK -> "Тёмная"
    }

    /** «3 файла · 1 086 операций» or «Не загружена» (review ux-11). */
    fun statement(s: StatementsSummary?): String =
        if (s == null) "Не загружена"
        else "${countWithNoun(s.uploadCount, "файл", "файла", "файлов")} · ${countWithNoun(s.operationCount, "операция", "операции", "операций")}"

    /** Value of «Код-пароль и биометрия»; null when the device has no biometrics. */
    fun biometric(enabled: Boolean?): String? = when (enabled) {
        null -> null
        true -> "Биометрия включена"
        false -> "Биометрия выключена"
    }

    /** «Версия 0.1 · Данные хранятся в России»: build suffixes and a trailing `.0` patch are dropped. */
    fun footer(versionName: String?): String {
        val v = versionName?.substringBefore('-')?.removeSuffix(".0")?.takeIf { it.isNotBlank() }
        return listOfNotNull(v?.let { "Версия $it" }, "Данные хранятся в России").joinToString(" · ")
    }
}
