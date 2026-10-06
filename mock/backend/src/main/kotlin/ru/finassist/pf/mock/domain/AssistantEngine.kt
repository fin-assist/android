package ru.finassist.pf.mock.domain

import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockRow
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.ChartKind
import ru.finassist.pf.core.api.model.ChartPoint
import ru.finassist.pf.core.api.model.ChartValueKind
import ru.finassist.pf.core.api.model.Chip
import ru.finassist.pf.core.api.model.ChipScreen
import ru.finassist.pf.core.api.model.Coverage as ApiCoverage
import ru.finassist.pf.core.api.model.OperationKindFilter
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.common.time.countWithNoun
import java.time.LocalDate
import java.time.YearMonth

/**
 * Canned "LLM": understands «сколько я трачу на X» for a merchant or a category, and general spending
 * questions; everything else is CANNOT_ANSWER. Numbers in charts and rows are computed from the ledger, as
 * the real service does from function results.
 */
class AssistantEngine(
    private val ledger: Ledger,
    private val catalog: Catalog,
    private val scope: Scope,
    private val coverage: () -> Coverage,
) {
    class Answer(
        val text: String,
        val blocks: List<Block>,
        val chips: List<Chip>,
        val dataRange: DateRange?,
        /** false when the data could not fully answer (api.md 7: the attempt is refunded). */
        val charged: Boolean,
    )

    sealed interface Outcome {
        data class Answered(val answer: Answer) : Outcome
        data object CannotAnswer : Outcome
    }

    fun answer(question: String, mode: TransferMode, today: LocalDate): Outcome {
        val q = question.lowercase().replace('ё', 'е')
        if (NONSENSE.any { q.contains(it) }) return Outcome.CannotAnswer
        val visible = scope.visible(mode)
        val cov = coverage()
        if (cov.isEmpty) return Outcome.CannotAnswer
        val lastMonth = cov.monthsWithData.last()
        val months = (5 downTo 0).map { lastMonth.minusMonths(it.toLong()) }.filter { !it.atDay(1).isBefore(cov.firstDay!!.withDayOfMonth(1)) }
        val from = months.first().atDay(1)
        val toExcl = months.last().plusMonths(1).atDay(1)
        val range = DateRange(from.atStartOfDay(scope.zone).toOffsetDateTime(), toExcl.atStartOfDay(scope.zone).toOffsetDateTime())

        val merchant = ledger.all.map { it.name }.distinct()
            .filter { it.length >= 3 }
            .filter { q.contains(it.lowercase().replace('ё', 'е')) }
            .maxByOrNull { it.length }
        val category = catalog.categories.filter { !it.isSystem }
            .filter { q.contains(it.name.lowercase().replace('ё', 'е').replace(' ', ' ')) }
            .maxByOrNull { it.name.length }

        val subjectOps: List<Operation>
        val title: String
        val filter: OperationsFilter
        when {
            merchant != null -> {
                subjectOps = scope.inDays(visible, from, toExcl).filter { it.name == merchant && (it.isDebit || it.isRefund) }
                title = merchant
                filter = OperationsFilter(from = range.from, to = range.to, transferMode = mode, selection = "s_merchant_" + Catalog.slugOf(merchant).take(40), selectionName = merchant)
            }
            category != null -> {
                subjectOps = scope.inDays(visible, from, toExcl).filter { scope.expenses(listOf(it)).any { e -> e.category?.id == category.id } }
                title = category.name
                filter = OperationsFilter(from = range.from, to = range.to, transferMode = mode, kind = OperationKindFilter.EXPENSE, categoryId = category.id)
            }
            SPENDING_WORDS.any { q.contains(it) } -> {
                subjectOps = scope.inDays(visible, from, toExcl).filter { it.isDebit || it.isRefund }
                title = "Расходы"
                filter = OperationsFilter(from = range.from, to = range.to, transferMode = mode, kind = OperationKindFilter.EXPENSE)
            }
            else -> return Outcome.CannotAnswer
        }

        val perMonth = months.map { m ->
            val ops = subjectOps.filter { YearMonth.from(scope.dayOf(it)) == m }
            m to scope.expenseTotal(ops)
        }
        val total = perMonth.sumOf { it.second }
        val count = subjectOps.count { it.isDebit }
        val periodLabel = RussianDates.monthRange(months.first(), months.last())
        if (count == 0) {
            val text = "За $periodLabel операций «$title» не нашли. Возможно, они проходят под другим названием — " +
                "посмотрите «Операции» или уточните вопрос."
            return Outcome.Answered(Answer(text, listOf(Block(type = BlockType.TEXT, text = text)), emptyList(), range, charged = false))
        }
        val firstMonthWithOps = perMonth.first { it.second != 0L }.first
        val peak = perMonth.maxBy { it.second }
        val monthsWithOps = perMonth.count { it.second != 0L }.coerceAtLeast(1)
        val avg = total / monthsWithOps
        val text = buildString {
            append("За $periodLabel на «$title» ушло **${Money(total).format()}**")
            if (firstMonthWithOps != months.first()) append(", все операции — с ${RussianDates.monthGenitive(firstMonthWithOps.month)}")
            append(". В среднем ${Money(avg).format()} в месяц, больше всего в ${RussianDates.monthPrepositional(peak.first.month)}: ${Money(peak.second).format()}.")
        }
        val points = perMonth.map { (m, v) ->
            val mFrom = m.atDay(1)
            val mTo = m.plusMonths(1).atDay(1)
            val bounds = cov.dataBounds(mFrom, mTo)
            ChartPoint(
                range = DateRange(mFrom.atStartOfDay(scope.zone).toOffsetDateTime(), mTo.atStartOfDay(scope.zone).toOffsetDateTime()),
                value = if (cov.hasData(m)) v.toDouble() else null,
                coverage = when (cov.coverageOf(mFrom, mTo)) { "complete" -> ApiCoverage.COMPLETE; "no_data" -> ApiCoverage.NO_DATA; else -> ApiCoverage.PARTIAL },
                dataFrom = bounds?.first?.atStartOfDay(scope.zone)?.toOffsetDateTime(),
                dataTo = bounds?.second?.plusDays(1)?.atStartOfDay(scope.zone)?.toOffsetDateTime(),
            )
        }
        val chart = Block(
            type = BlockType.CHART, kind = ChartKind.BAR, title = "$title по месяцам", valueKind = ChartValueKind.AMOUNT,
            points = points, highlightIndex = perMonth.indexOf(peak),
            altText = "$title по месяцам: " + perMonth.joinToString(", ") { (m, v) -> "${RussianDates.monthNominative(m.month)} ${Money(v).format()}" },
        )
        val rows = Block(
            type = BlockType.ROWS,
            altText = "Операций: $count. Средний чек: ${Money(total / count).format()}",
            rows = listOf(BlockRow(label = "Операций", count = count), BlockRow(label = "Средний чек", amount = Money(total / count))),
        )
        val chips = listOf(
            Chip(label = "$title · ${countWithNoun(count, "операция", "операции", "операций")}", screen = ChipScreen.OPERATIONS, filters = filter),
            Chip(label = "Аналитика за ${RussianDates.monthNominative(lastMonth.month)}", screen = ChipScreen.ANALYTICS, params = AnalyticsParams(PeriodTypeCode.MONTH, PeriodKey.ofMonth(lastMonth), mode)),
        )
        return Outcome.Answered(Answer(text, listOf(Block(type = BlockType.TEXT, text = text), chart, rows), chips, range, charged = true))
    }

    private companion object {
        val NONSENSE = listOf("марс", "следующем году", "курс доллара", "погода")
        val SPENDING_WORDS = listOf("трат", "расход", "потрат", "ушло", "сколько")
    }
}
