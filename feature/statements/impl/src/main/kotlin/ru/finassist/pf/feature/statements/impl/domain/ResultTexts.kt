package ru.finassist.pf.feature.statements.impl.domain

import ru.finassist.pf.core.api.model.AnalyticsFeature
import ru.finassist.pf.core.api.model.ImportAccount
import ru.finassist.pf.core.api.model.ImportCoverage
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.api.model.IncompleteMonth
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.common.time.countWithNoun
import ru.finassist.pf.core.common.time.groupThousands
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

    /**
     * Under the headline (mockups `ImportResult*`): «1 086 операций · 1 апреля — 25 сентября 2026» for the first
     * import, «Новых: 214 · 1–25 сентября 2026» for a later one. `null` when nothing was added — then [nothingNew]
     * explains it.
     */
    fun subtitle(r: ImportResult): String? {
        if (r.operationCount == 0 || r.newCount == 0) return null
        val count = if (r.isFirstImport) countWithNoun(r.newCount, "операция", "операции", "операций") else "Новых: ${groupThousands(r.newCount)}"
        return listOfNotNull(count, period(r, withYear = true)).joinToString(" · ")
    }

    /** «Всё из этого файла уже было загружено: 1 086 операций пропустили». */
    fun nothingNew(r: ImportResult): String =
        "Всё из этого файла уже было загружено: ${countWithNoun(r.operationCount, "операцию", "операции", "операций")} пропустили"

    fun period(r: ImportResult, withYear: Boolean = false): String? {
        val from = r.firstOperationAt ?: return null
        val to = r.lastOperationAt ?: return null
        return RussianDates.dayRange(from.toLocalDate(), to.toLocalDate(), withYear)
    }

    /** «5 строк не прочитали — их нет в суммах» (followed by the «Подробнее» link). */
    fun unread(r: ImportResult): String? =
        if (r.unreadCount > 0) "${countWithNoun(r.unreadCount, "строку", "строки", "строк")} не прочитали — их нет в суммах." else null

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

    /** «Категории проставлены у 1 052 операций». */
    fun categories(r: ImportResult): String? {
        if (r.newCount == 0) return null
        return "Категории проставлены у ${countWithNoun(r.categorizedCount, "операции", "операций", "операций")}"
    }

    /** «24 из них — в категории «Между своими счетами»: не считаем их тратами и доходами». */
    fun ownTransfers(r: ImportResult): String? {
        if (r.newCount == 0 || r.ownTransferCount <= 0) return null
        return "${groupThousands(r.ownTransferCount)} из них — в категории «Между своими счетами»: не считаем их тратами и доходами"
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

    /** A notice about what the analytics can show now (mockups `ImportResultShort`, `ImportResultUnlocked`). */
    data class CoverageNotice(val positive: Boolean, val title: String, val text: String)

    /**
     * After a later upload that completed months: what opened and what is still to come («Теперь есть 1 полный
     * месяц — август»). Without a full month: what is closed and when it opens («Часть аналитики пока закрыта»).
     * Otherwise nothing — the first import of a long statement needs no explanation.
     */
    fun coverageNotice(r: ImportResult): CoverageNotice? {
        val c = r.coverage
        val locked = c.features.filter { !it.open && it.feature != AnalyticsFeature.UNKNOWN && it.requiredFullMonths != null }
        if (!r.isFirstImport && c.newlyFullMonths.isNotEmpty()) {
            val title = newlyFull(c) ?: return null
            val opened = c.features.filter { it.openedNow }.mapNotNull { featureForms(it.feature)?.name }
            val parts = mutableListOf<String>()
            if (opened.isNotEmpty()) {
                val verb = if (opened.size == 1) "открылось:" else "открылись"
                parts += "На «Аналитике» $verb ${joinAnd(opened)}."
            }
            locked.forEach { f ->
                val forms = featureForms(f.feature) ?: return@forEach
                parts += "${forms.name.replaceFirstChar { it.uppercase() }} ${forms.appears} с ${withFullMonths(f.requiredFullMonths!!)}."
            }
            return CoverageNotice(positive = true, title = title, text = parts.joinToString(" "))
        }
        if (c.fullMonths == 0 && locked.isNotEmpty()) {
            val current = c.incompleteMonths.firstOrNull { it.inProgress }
            val lines = mutableListOf<String>()
            current?.let { lines += incomplete(it) + "." }
            val phrases = locked.mapIndexedNotNull { i, f ->
                val forms = featureForms(f.feature) ?: return@mapIndexedNotNull null
                val previous = current?.let { runCatching { YearMonth.parse(it.month).minusMonths(1) }.getOrNull() }
                val condition = if (f.feature == AnalyticsFeature.COMPARISON && f.requiredFullMonths == 1 && previous != null) {
                    "с выпиской за ${RussianDates.monthNominative(previous.month)}"
                } else {
                    "с ${withFullMonths(f.requiredFullMonths!!)}"
                }
                if (i == 0) "${forms.name.replaceFirstChar { it.uppercase() }} ${forms.appears} $condition" else "${forms.name} — $condition"
            }
            if (phrases.isNotEmpty()) lines += phrases.joinToString(", ") + "."
            return CoverageNotice(positive = false, title = "Часть аналитики пока закрыта", text = lines.joinToString(" "))
        }
        return null
    }

    /** A feature in running text: its name and «появится» / «появятся» to agree with it. */
    private data class FeatureForms(val name: String, val appears: String)

    private fun featureForms(f: AnalyticsFeature): FeatureForms? = when (f) {
        AnalyticsFeature.COMPARISON -> FeatureForms("сравнение с прошлым месяцем", "появится")
        AnalyticsFeature.MONTHLY_CHART -> FeatureForms("графики по месяцам", "появятся")
        AnalyticsFeature.TYPICAL -> FeatureForms("обычные расходы за месяц", "появятся")
        AnalyticsFeature.REGULAR_PAYMENTS -> FeatureForms("регулярные платежи", "появятся")
        AnalyticsFeature.NOTABLE_SPENDING -> FeatureForms("заметные траты", "появятся")
        AnalyticsFeature.SMALL_FREQUENT -> FeatureForms("мелкие частые траты", "появятся")
        AnalyticsFeature.YEAR_FORECAST -> FeatureForms("прогноз на год", "появится")
        AnalyticsFeature.UNKNOWN -> null
    }

    /** «1 полным месяцем», «2 полными месяцами». */
    private fun withFullMonths(n: Int): String = if (n % 10 == 1 && n % 100 != 11) "$n полным месяцем" else "$n полными месяцами"

    private fun joinAnd(items: List<String>): String =
        if (items.size == 1) items[0] else items.dropLast(1).joinToString(", ") + " и " + items.last()
}
