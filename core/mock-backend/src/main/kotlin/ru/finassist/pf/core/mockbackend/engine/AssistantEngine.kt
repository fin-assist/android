package ru.finassist.pf.core.mockbackend.engine

import ru.finassist.pf.core.network.dto.*
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * Canned-but-data-driven assistant: understands «сколько я трачу на <продавца или категорию>», «доходы»,
 * «траты/расходы» and answers from the ledger. Numbers in chart and rows come from the same computation —
 * the contract's rule that figures never come from model text. Anything else → CANNOT_ANSWER.
 */
class AssistantEngine(private val ledger: Ledger, private val zone: ZoneId, private val wire: Wire) {

    class Answer(val blocks: List<BlockDto>, val chips: List<ChipDto>, val source: AnswerSourceDto, val charged: Boolean)

    sealed interface Outcome {
        data class Ok(val answer: Answer) : Outcome
        data object CannotAnswer : Outcome
    }

    fun answer(question: String, transferMode: String, now: OffsetDateTime): Outcome {
        val view = AccountingView(ledger, zone, transferMode != "without")
        if (view.lines.isEmpty()) return Outcome.CannotAnswer
        val q = question.lowercase().replace('ё', 'е')
        val today = wire.localDate(now)
        val months = (5 downTo 0).map { YearMonth.from(today).minusMonths(it.toLong()) }
        val source = AnswerSourceDto(wire.time(ledger.lastUploadChangeAt ?: now), wire.dayRange(months.first().atDay(1), months.last().atEndOfMonth()), transferMode, emptyList())
        val analyticsChip = ChipDto("Аналитика за ${RuMonths.nominative[today.monthValue - 1]}", "analytics", params = AnalyticsParamsDto("month", wire.month(YearMonth.from(today)), transferMode))

        // 1) merchant: longest operation name that occurs in the question
        val names = view.lines.map { it.op.tx.name }.distinct()
        val merchant = names.filter { it.length >= 3 && q.contains(it.lowercase().replace('ё', 'е')) }.maxByOrNull { it.length }
        if (merchant != null) {
            val lines = view.lines.filter { it.op.tx.name.equals(merchant, true) && it.isExpense && YearMonth.from(it.day) in months }
            if (lines.isEmpty()) return Outcome.CannotAnswer
            return Outcome.Ok(series(merchant, lines, months, today, transferMode, source, analyticsChip,
                ChipDto("$merchant · ${lines.size} ${plural(lines.size.toLong(), "операция", "операции", "операций")}", "operations",
                    filters = OperationsFilterDto(selection = Selections.merchant(merchant), selectionName = merchant, from = wire.dayStartStr(months.first().atDay(1)), to = wire.dayStartStr(months.last().plusMonths(1).atDay(1)), transferMode = transferMode))))
        }
        // 2) category by name
        val category = Categories.all.filter { !it.isSystem && q.contains(it.name.lowercase().replace('ё', 'е')) }.maxByOrNull { it.name.length }
        if (category != null) {
            val lines = view.lines.filter { it.categoryId == category.id && it.isExpense && YearMonth.from(it.day) in months }
            if (lines.isEmpty()) return Outcome.CannotAnswer
            return Outcome.Ok(series(category.name, lines, months, today, transferMode, source, analyticsChip,
                ChipDto("Категория «${category.name}»", "operations", filters = OperationsFilterDto(categoryId = category.id, kind = OperationKindFilterDto.expense, from = wire.dayStartStr(months.first().atDay(1)), to = wire.dayStartStr(months.last().plusMonths(1).atDay(1)), transferMode = transferMode))))
        }
        // 3) income / expenses overview for the last month with operations
        val lastMonth = view.lines.maxOfOrNull { it.day }?.let { YearMonth.from(it) } ?: YearMonth.from(today)
        val monthLines = view.inMonth(lastMonth)
        if (q.contains("доход")) {
            val income = view.income(monthLines)
            val text = "Доходы за ${RuMonths.nominative[lastMonth.monthValue - 1]} — **${money(income)}**. " +
                "Больше всего: " + monthLines.filter { !it.isExpense }.groupBy { it.categoryId }.maxByOrNull { e -> e.value.sumOf { it.signedAmount } }?.let { (c, g) -> "${Categories.byId(c!!)?.name} (${money(g.sumOf { it.signedAmount })})" }.orEmpty()
            return Outcome.Ok(Answer(listOf(BlockDto("text", text = text)), listOf(analyticsChip), source, charged = true))
        }
        if (q.contains("трат") || q.contains("трач") || q.contains("расход")) {
            val expense = view.expense(monthLines)
            val top = monthLines.filter { it.isExpense }.groupBy { it.categoryId }.map { (c, g) -> (c?.let { Categories.byId(it)?.name } ?: "Без категории") to g.sumOf { it.signedAmount } }.sortedByDescending { it.second }.take(3)
            val text = "Расходы за ${RuMonths.nominative[lastMonth.monthValue - 1]} — **${money(expense)}**.\n\n" + top.joinToString("\n") { "- ${it.first} — ${money(it.second)}" }
            val rows = BlockDto("rows", altText = top.joinToString(". ") { "${it.first}: ${money(it.second)}" }, rows = top.map { BlockRowDto(it.first, amount = it.second) })
            return Outcome.Ok(Answer(listOf(BlockDto("text", text = text), rows), listOf(analyticsChip), source, charged = true))
        }
        return Outcome.CannotAnswer
    }

    private fun series(title: String, lines: List<AccountingView.Line>, months: List<YearMonth>, today: java.time.LocalDate, transferMode: String, source: AnswerSourceDto, analyticsChip: ChipDto, dataChip: ChipDto): Answer {
        val coverage = Coverage(ledger.uploads, zone, today)
        val byMonth = months.map { ym -> ym to lines.filter { YearMonth.from(it.day) == ym }.sumOf { it.signedAmount } }
        val total = byMonth.sumOf { it.second }
        val monthsWithSpend = byMonth.filter { it.second > 0 }
        val peak = byMonth.maxByOrNull { it.second }
        val firstMonth = monthsWithSpend.firstOrNull()?.first
        val avg = if (monthsWithSpend.isNotEmpty()) total / monthsWithSpend.size else 0
        val count = lines.size
        val avgCheck = if (count > 0) (lines.sumOf { it.signedAmount }.toDouble() / count).roundToLong() else 0
        val rangeText = "${RuMonths.nominative[months.first().monthValue - 1]} — ${RuMonths.nominative[months.last().monthValue - 1]}"
        val text = buildString {
            append("За $rangeText на «$title» ушло **${money(total)}**")
            if (firstMonth != null && firstMonth != months.first()) append(", все операции — с ${RuMonths.genitive[firstMonth.monthValue - 1]}")
            append(". В среднем ${money(avg)} в месяц")
            if (peak != null && peak.second > 0) append(", больше всего в ${prepositional(peak.first)}: ${money(peak.second)}")
            append(".")
        }
        val points = byMonth.map { (ym, v) ->
            val has = coverage.hasData(ym)
            ChartPointDto(wire.monthRange(ym), if (has) v.toDouble() else null,
                when { !has -> "no_data"; coverage.isFullMonth(ym) -> "complete"; else -> "partial" },
                dataTo = if (has && !coverage.isFullMonth(ym)) lines.filter { YearMonth.from(it.day) == ym }.maxOfOrNull { it.op.tx.postedAt }?.let(wire::time) else null)
        }
        val alt = "$title по месяцам: " + byMonth.joinToString(", ") { "${RuMonths.nominative[it.first.monthValue - 1]} ${money(it.second)}" }
        val chart = BlockDto("chart", altText = alt, kind = "bar", title = "$title по месяцам", valueKind = "amount", points = points, highlightIndex = peak?.let { byMonth.indexOf(it) })
        val rows = BlockDto("rows", altText = "Операций: $count. Средний чек: ${money(avgCheck)}", rows = listOf(BlockRowDto("Операций", count = count), BlockRowDto("Средний чек", amount = avgCheck)))
        return Answer(listOf(BlockDto("text", text = text), chart, rows), listOf(dataChip, analyticsChip), source, charged = true)
    }

    private fun prepositional(ym: YearMonth) = listOf("январе", "феврале", "марте", "апреле", "мае", "июне", "июле", "августе", "сентябре", "октябре", "ноябре", "декабре")[ym.monthValue - 1]

    private fun money(kopecks: Long): String {
        val whole = kopecks / 100
        val s = kotlin.math.abs(whole).toString().reversed().chunked(3).joinToString(" ").reversed()
        return (if (whole < 0) "−" else "") + s + " ₽"
    }

    private fun plural(n: Long, one: String, few: String, many: String): String {
        val m10 = n % 10; val m100 = n % 100
        return when { m100 in 11..14 -> many; m10 == 1L -> one; m10 in 2..4 -> few; else -> many }
    }
}
