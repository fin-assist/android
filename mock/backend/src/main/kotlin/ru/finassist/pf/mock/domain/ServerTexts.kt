package ru.finassist.pf.mock.domain

import ru.finassist.pf.core.api.model.AnalyticsFeature

/**
 * Texts the server sends next to open codes (api.md «Нотация», FIN-82): the client shows them for codes it does not
 * know. On the real backend `bank-bff` builds them (grpc-api.md 5.3).
 */
object ServerTexts {
    const val EMPTY_STATEMENT = "В выписке нет операций"
    const val NO_EXPENSES = "В выписке нет расходов — возможно, выгружен не тот счёт"

    fun featureName(f: AnalyticsFeature): String = when (f) {
        AnalyticsFeature.COMPARISON -> "Сравнение с прошлым месяцем"
        AnalyticsFeature.MONTHLY_CHART -> "Графики по месяцам"
        AnalyticsFeature.TYPICAL -> "Обычные расходы за месяц"
        AnalyticsFeature.REGULAR_PAYMENTS -> "Регулярные платежи"
        AnalyticsFeature.NOTABLE_SPENDING -> "Заметные траты"
        AnalyticsFeature.SMALL_FREQUENT -> "Мелкие частые траты"
        AnalyticsFeature.YEAR_FORECAST -> "Прогноз на год"
        AnalyticsFeature.UNKNOWN -> "Аналитика"
    }

    /** «Прогноз на год — нужно 12 полных месяцев, есть 5». */
    fun lockedFeature(f: AnalyticsFeature, requiredFull: Int?, requiredWithData: Int?, full: Int, withData: Int): String =
        when {
            requiredFull != null -> "${featureName(f)} — нужно ${months(requiredFull, "полный")}, есть $full"
            requiredWithData != null -> "${featureName(f)} — нужно ${months(requiredWithData, "с данными")}, есть $withData"
            else -> featureName(f)
        }

    /** «1 полный месяц», «3 полных месяца», «2 месяца с данными». */
    fun months(n: Int, kind: String): String {
        val noun = plural(n, "месяц", "месяца", "месяцев")
        return if (kind == "полный") "$n ${plural(n, "полный", "полных", "полных")} $noun" else "$n $noun $kind"
    }

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
    }
}
