package ru.finassist.pf.feature.statements.impl.domain

import ru.finassist.pf.core.api.model.AnalyticsFeature
import ru.finassist.pf.core.api.model.ImportAccount
import ru.finassist.pf.core.api.model.ImportCoverage
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.api.model.IncompleteMonth
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.common.time.countWithNoun
import java.time.YearMonth

/**
 * Texts of the import result screen, built from server facts only (api.md 3.3: the client formulates, the
 * server decides thresholds). Pure functions — covered by unit tests.
 */
object ResultTexts {

    /** Headline: «Выписка загружена», «Новых операций нет», «Выписка загружена, кроме 5 строк». */
    fun title(r: ImportResult): String = when {
        r.operationCount == 0 -> "В выписке нет операций"
        r.newCount == 0 -> "Новых операций нет"
        r.unreadCount > 0 -> "Выписка загружена, кроме ${countWithNoun(r.unreadCount, "строки", "строк", "строк")}"
        else -> "Выписка загружена"
    }

    /** «1 086 новых операций · 1 апреля — 25 сентября». */
    fun subtitle(r: ImportResult): String? {
        val period = period(r)
        return when {
            r.operationCount == 0 -> "Проверьте период выгрузки в Т-Банке и загрузите файл ещё раз"
            r.newCount == 0 -> "Все ${countWithNoun(r.operationCount, "операция", "операции", "операций")} из файла уже были загружены — данные не изменились"
            else -> listOfNotNull(countWithNoun(r.newCount, "новая операция", "новые операции", "новых операций"), period).joinToString(" · ")
        }
    }

    fun period(r: ImportResult): String? {
        val from = r.firstOperationAt ?: return null
        val to = r.lastOperationAt ?: return null
        return RussianDates.dayRange(from.toLocalDate(), to.toLocalDate())
    }

    /** «312 операций уже были загружены — пропустили». */
    fun duplicates(r: ImportResult): String? =
        if (r.duplicateCount > 0 && r.newCount > 0) "${countWithNoun(r.duplicateCount, "операция уже была загружена", "операции уже были загружены", "операций уже были загружены")} — пропустили" else null

    /** «В выписке 3 счёта: текущий ··4821, накопительный ··0734 и счёт другого банка». */
    fun accounts(accounts: List<ImportAccount>): String? {
        if (accounts.isEmpty()) return null
        val parts = accounts.map { a ->
            val type = a.typeName.replaceFirstChar { it.lowercase() }
            if (a.mask != null) "$type ${a.mask}" else type
        }
        val list = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " и " + parts.last()
        return "В выписке ${countWithNoun(accounts.size, "счёт", "счёта", "счетов")}: $list"
    }

    /** «Категории проставлены у 1 052 операций. 24 из них — переводы между своими счетами: не считаем их тратами и доходами». */
    fun categories(r: ImportResult): String? {
        if (r.newCount == 0) return null
        val base = "Категории проставлены у ${countWithNoun(r.categorizedCount, "операции", "операций", "операций")}"
        return if (r.ownTransferCount > 0) {
            "$base. ${r.ownTransferCount} из них — переводы между своими счетами: не считаем их тратами и доходами"
        } else {
            base
        }
    }

    /** «34 без категории — разобрать» (the link part is rendered separately). */
    fun uncategorized(r: ImportResult): String? =
        if (r.uncategorizedCount > 0) "${countWithNoun(r.uncategorizedCount, "операция", "операции", "операций")} без категории" else null

    /** «Теперь есть 1 полный месяц — август». */
    fun newlyFull(c: ImportCoverage): String? {
        if (c.newlyFullMonths.isEmpty()) return null
        val months = c.newlyFullMonths.mapNotNull { runCatching { YearMonth.parse(it) }.getOrNull() }.sorted()
        if (months.isEmpty()) return null
        val names = if (months.size == 1) RussianDates.monthNominative(months[0].month) else RussianDates.monthRange(months.first(), months.last())
        return "Теперь есть ${countWithNoun(c.fullMonths, "полный месяц", "полных месяца", "полных месяцев")} — $names"
    }

    /** One line per incomplete month: in progress, starts late, ends early, gaps. */
    fun incomplete(m: IncompleteMonth): String {
        val ym = runCatching { YearMonth.parse(m.month) }.getOrNull()
        val name = ym?.let { RussianDates.monthNominative(it.month).replaceFirstChar { c -> c.uppercase() } } ?: m.month
        val monthStart = m.range.from.toLocalDate()
        val monthEnd = RussianDates.lastDayOf(m.range)
        val dataFrom = m.dataFrom.toLocalDate()
        val dataTo = m.dataTo.toLocalDate()
        return when {
            m.inProgress -> "$name ещё не закончился — в выписке операции по ${RussianDates.day(dataTo)}"
            m.gaps.isNotEmpty() -> {
                val g = m.gaps.first()
                "$name — неполный: нет данных за ${RussianDates.dayRange(g.from.toLocalDate(), RussianDates.lastDayOf(g))}"
            }
            dataFrom.isAfter(monthStart) -> "$name — неполный: выписка начинается с ${RussianDates.day(dataFrom)}"
            dataTo.isBefore(monthEnd) -> "$name — неполный: выписка заканчивается ${RussianDates.day(dataTo)}"
            else -> "$name — неполный"
        }
    }

    /** Closed features with a threshold: «Прогноз на год — нужно 12 полных месяцев, есть 5». */
    fun locked(c: ImportCoverage): List<String> = c.features.filter { !it.open && it.feature != AnalyticsFeature.UNKNOWN }.mapNotNull { f ->
        val name = featureName(f.feature) ?: return@mapNotNull null
        when {
            f.requiredFullMonths != null ->
                "$name — нужно ${countWithNoun(f.requiredFullMonths!!, "полный месяц", "полных месяца", "полных месяцев")}, есть ${c.fullMonths}"
            f.requiredMonthsWithData != null ->
                "$name — нужно ${countWithNoun(f.requiredMonthsWithData!!, "месяц", "месяца", "месяцев")} с данными"
            else -> name
        }
    }

    /** Features opened by this upload: «Открылось: сравнение с прошлым месяцем, регулярные платежи». */
    fun openedNow(c: ImportCoverage): String? {
        val names = c.features.filter { it.openedNow }.mapNotNull { featureName(it.feature)?.replaceFirstChar { ch -> ch.lowercase() } }
        return if (names.isEmpty()) null else "Открылось: " + names.joinToString(", ")
    }

    private fun featureName(f: AnalyticsFeature): String? = when (f) {
        AnalyticsFeature.COMPARISON -> "Сравнение с прошлым месяцем"
        AnalyticsFeature.MONTHLY_CHART -> "График по месяцам"
        AnalyticsFeature.TYPICAL -> "Обычные расходы за месяц"
        AnalyticsFeature.REGULAR_PAYMENTS -> "Регулярные платежи"
        AnalyticsFeature.NOTABLE_SPENDING -> "Заметные траты"
        AnalyticsFeature.SMALL_FREQUENT -> "Мелкие частые траты"
        AnalyticsFeature.YEAR_FORECAST -> "Прогноз на год"
        AnalyticsFeature.UNKNOWN -> null
    }
}
