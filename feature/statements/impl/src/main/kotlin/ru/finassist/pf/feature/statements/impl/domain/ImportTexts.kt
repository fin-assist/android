package ru.finassist.pf.feature.statements.impl.domain

import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.money.MoneyFormat
import ru.finassist.pf.core.common.money.SignStyle
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.common.time.RussianDates.plural
import ru.finassist.pf.core.network.codes.ImportFeature
import ru.finassist.pf.core.network.codes.ImportNotice
import ru.finassist.pf.core.network.dto.ImportResultDto
import ru.finassist.pf.core.network.dto.LoadedSummaryDto
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth

private const val NBSP = ' '

/** Why an import failed and what to do (mockup ImportError). */
data class ImportFailure(val reason: String, val advice: String, val showGuideLink: Boolean = true) {
    companion object {
        private const val DOWNLOAD_ADVICE = "Скачайте выписку кнопкой «Скачать за год» на экране загрузки или в кабинете Т-Банка выберите «Скачать в OFX»"

        /** Synchronous HTTP errors of `POST /v1/statements`; [fileName] lets us name the real format (PDF, Excel). */
        fun fromError(e: AppError, fileName: String): ImportFailure = when (e) {
            AppError.FileTooLarge -> ImportFailure("Файл больше 10$NBSP" + "МБ", "Скачайте выписку за период покороче — например, по полгода", showGuideLink = false)
            AppError.CsvNotAccepted -> ImportFailure("Это CSV, а нужен OFX", DOWNLOAD_ADVICE)
            AppError.WrongBank -> ImportFailure("Похоже, это выписка не Т-Банка", "Пока поддерживаем только выписки Т-Банка в формате OFX", showGuideLink = false)
            AppError.WrongFormat -> ImportFailure("Это ${formatName(fileName)}, а нужен OFX", DOWNLOAD_ADVICE)
            else -> processing()
        }

        /** `error` event of the progress stream (PROCESSING_FAILED or an unknown code). */
        fun processing() = ImportFailure(
            "Не смогли разобрать операции в файле",
            "Скачайте выписку заново кнопкой «Скачать за год» и попробуйте ещё раз. Если не помогло — напишите нам, разберёмся",
        )

        private fun formatName(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "файл PDF"
            "xls", "xlsx" -> "файл Excel"
            "txt" -> "текстовый файл"
            "zip" -> "архив ZIP"
            "" -> "файл другого формата"
            else -> "файл ${fileName.substringAfterLast('.').uppercase()}"
        }
    }
}

/** Texts of the ImportResult screen derived from the contract's `complete` event. */
class ImportResultTexts(val r: ImportResultDto) {
    private val notices = r.notices.map(ImportNotice::fromWire)
    val nothingNew: Boolean get() = r.newCount == 0 && r.operationCount > 0
    val empty: Boolean get() = notices.contains(ImportNotice.EmptyStatement) || r.operationCount == 0
    val noExpenses: Boolean get() = notices.contains(ImportNotice.NoExpenses)
    val unreadText: String? get() = if (r.unreadCount > 0) "${plural(r.unreadCount.toLong(), "строку", "строки", "строк")} не прочитали — их нет в суммах." else null

    val heading: String get() = when {
        empty -> "В выписке нет операций"
        nothingNew -> "Новых операций нет"
        r.unreadCount > 0 -> "Выписка загружена, кроме ${plural(r.unreadCount.toLong(), "строки", "строк", "строк")}"
        else -> "Выписка загружена"
    }

    /** «1 086 операций · 1 апреля — 25 сентября 2026» / «Новых: 214 · 1 — 25 сентября 2026». */
    val subtitle: String get() {
        val range = dayRangeWithYear(r.firstOperationAt, r.lastOperationAt)
        val count = if (r.isFirstImport || nothingNew) plural(r.operationCount.toLong(), "операция", "операции", "операций") else "Новых: ${MoneyFormat.groupThousands(r.newCount.toLong())}"
        return if (range != null) "$count · $range" else count
    }

    val totalsPeriod: String? get() {
        val t = r.totals ?: return null
        val range = dayRangeWithYear(r.firstOperationAt, r.lastOperationAt, withYear = false)
        val scope = if (t.scope == "all") "Все операции" else "Новые операции"
        return if (range != null) "$scope · $range" else scope
    }
    val expenseText: String? get() = r.totals?.let { MoneyFormat.rub(Money(it.expense), SignStyle.Expense) }
    val incomeText: String? get() = r.totals?.let { MoneyFormat.rub(Money(it.income), SignStyle.Income) }

    val duplicatesText: String? get() = if (r.duplicateCount > 0 && !nothingNew) "${plural(r.duplicateCount.toLong(), "операция уже была загружена", "операции уже были загружены", "операций уже были загружены")} — пропустили" else null
    val nothingNewText: String get() = "Всё из этого файла уже было загружено: ${plural(r.duplicateCount.toLong(), "операцию", "операции", "операций")} пропустили"

    val accountsText: String? get() {
        if (r.accounts.isEmpty()) return null
        val names = r.accounts.map { a -> if (a.isOtherBank) "счёт другого банка" else listOfNotNull(a.typeName.lowercase(), a.mask).joinToString(" ") }
        return "В выписке ${plural(r.accounts.size.toLong(), "счёт", "счёта", "счетов")}: ${joinAnd(names)}"
    }
    val categorizedText: String get() = "Категории проставлены у ${plural(r.categorizedCount.toLong(), "операции", "операций", "операций")}"
    val transfersText: String? get() = if (r.ownTransferCount > 0) "${r.ownTransferCount} из них — в категории «Между своими счетами»: не считаем их тратами и доходами" else null
    val uncategorizedText: String? get() = if (r.uncategorizedCount > 0) "${plural(r.uncategorizedCount.toLong(), "операция", "операции", "операций")} без категории — " else null

    /** Coverage line: what opened on «Аналитика» and what is still locked (mockup ImportResult, «unlocked» / «short»). */
    val coverageText: String? get() {
        val c = r.coverage
        val features = c.features.associateBy { ImportFeature.fromWire(it.feature) }
        val opened = c.features.filter { it.openedNow }.mapNotNull { featureName(ImportFeature.fromWire(it.feature)) }
        val locked = listOf(ImportFeature.Comparison, ImportFeature.RegularPayments).mapNotNull { f -> features[f]?.takeIf { !it.open }?.let { f to it } }
        val inProgress = c.incompleteMonths.firstOrNull { it.inProgress }
        val parts = mutableListOf<String>()
        if (inProgress != null) {
            val ym = runCatching { YearMonth.parse(inProgress.month) }.getOrNull()
            val to = runCatching { OffsetDateTime.parse(inProgress.dataTo).toLocalDate() }.getOrNull()
            if (ym != null && to != null) parts += "${RussianDates.monthTitle(ym, withYear = false)} ещё не закончился — выписка по ${RussianDates.dayMonth(to)}."
        }
        if (opened.isNotEmpty()) parts += "На «Аналитике» ${if (opened.size == 1) "открылось" else "открылись"} ${joinAnd(opened)}."
        val currentMonth = inProgress?.let { runCatching { YearMonth.parse(it.month) }.getOrNull() }
        locked.forEach { (f, a) ->
            val need = a.requiredFullMonths
            val what = when (f) { ImportFeature.Comparison -> "Сравнение с прошлым месяцем появится"; else -> "Регулярные платежи появятся" }
            parts += when {
                // «с выпиской за август» — month names are masculine, accusative = nominative.
                need == 1 && currentMonth != null -> "$what с выпиской за ${RussianDates.monthNominative[currentMonth.minusMonths(1).monthValue - 1]}."
                need != null -> "$what с ${plural(need.toLong(), "полным месяцем", "полными месяцами", "полными месяцами")}."
                else -> "$what позже."
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** Suggest loading earlier months while comparison/regular payments are still locked. */
    val suggestMoreMonths: Boolean get() = r.coverage.features.any { ImportFeature.fromWire(it.feature) in setOf(ImportFeature.Comparison, ImportFeature.RegularPayments) && !it.open }

    private fun featureName(f: ImportFeature): String? = when (f) {
        ImportFeature.Comparison -> "сравнение с прошлым месяцем"
        ImportFeature.MonthlyChart -> "графики по месяцам"
        ImportFeature.RegularPayments -> "регулярные платежи"
        ImportFeature.NotableSpending -> "заметные траты"
        ImportFeature.Typical -> "типичный месяц"
        ImportFeature.YearForecast -> "прогноз на год"
        else -> null
    }
}

/** «a, b и c». */
private fun joinAnd(items: List<String>): String = if (items.size <= 1) items.joinToString() else items.dropLast(1).joinToString(", ") + " и " + items.last()

/** «1–25 сентября 2026» from two ISO date-times (inclusive last day); null when either is missing. */
fun dayRangeWithYear(fromIso: String?, toIso: String?, withYear: Boolean = true): String? {
    val from = fromIso?.let { runCatching { OffsetDateTime.parse(it).toLocalDate() }.getOrNull() } ?: return null
    val to = toIso?.let { runCatching { OffsetDateTime.parse(it).toLocalDate() }.getOrNull() } ?: return null
    val body = RussianDates.dayRange(from, to.plusDays(1))
    return if (withYear && from.year == to.year && !body.contains(to.year.toString())) "$body$NBSP${to.year}" else body
}

/** «1–25 сентября 2026 · 181 операция» for the «Сейчас загружено» card. */
fun loadedText(l: LoadedSummaryDto): String =
    listOfNotNull(dayRangeWithYear(l.firstOperationAt, l.lastOperationAt), plural(l.operationCount.toLong(), "операция", "операции", "операций")).joinToString(" · ")

/** «Загружено 25 сентября 2026». */
fun uploadedText(iso: String): String = runCatching { OffsetDateTime.parse(iso).toLocalDate() }.getOrNull()?.let { "Загружено ${RussianDates.dayMonth(it, withYear = true)}" } ?: "Загружено"

fun LocalDate.isoDay(): String = toString()
