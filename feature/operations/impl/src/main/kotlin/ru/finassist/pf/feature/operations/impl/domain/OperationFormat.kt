package ru.finassist.pf.feature.operations.impl.domain

import ru.finassist.pf.core.api.model.Account
import ru.finassist.pf.core.api.model.OperationItem
import ru.finassist.pf.core.api.model.OperationKind
import ru.finassist.pf.core.api.model.OperationStatus
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.RussianDates
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** How an operation row looks; pure, so the rules (sign, colour, subtitle) are unit-tested. */
data class RowModel(
    val id: String,
    val icon: String,
    val title: String,
    val subtitle: String,
    val amount: String,
    val income: Boolean,
    val muted: Boolean,
    val note: String?,
)

object OperationFormat {

    fun account(a: Account?): String? = a?.let { listOfNotNull(it.typeName, it.mask).joinToString(" ") }

    /**
     * Row: expense `−2 340 ₽`, income `+15 000 ₽` (positive colour), own transfer without sign and muted, unknown
     * kind — no sign, no colour (api.md 4.1). Pending holds get «холд» in the subtitle.
     */
    fun row(item: OperationItem): RowModel {
        val money = item.amount
        val amount = when (item.kind) {
            OperationKind.EXPENSE -> (-money).format()
            OperationKind.INCOME -> money.format(Money.Sign.ALWAYS)
            OperationKind.OWN_TRANSFER, OperationKind.UNKNOWN -> money.format(Money.Sign.NONE)
        }
        val subtitleParts = buildList {
            add(item.categoryName)
            if (item.kind == OperationKind.OWN_TRANSFER) {
                val from = account(item.fromAccount)
                val to = account(item.toAccount)
                if (from != null && to != null) add("$from → $to")
            }
            if (item.status == OperationStatus.PENDING) add("холд")
        }
        return RowModel(
            id = item.id,
            icon = item.categoryIcon,
            title = item.title,
            subtitle = subtitleParts.joinToString(" · "),
            amount = amount,
            income = item.kind == OperationKind.INCOME,
            muted = item.kind == OperationKind.OWN_TRANSFER,
            note = item.note,
        )
    }

    /** Groups rows by local day, newest first: «25 сентября», «Сегодня», «Вчера». */
    fun groupByDay(items: List<OperationItem>, zone: ZoneId, today: LocalDate): List<Pair<String, List<OperationItem>>> =
        items.groupBy { it.occurredAt.atZoneSameInstant(zone).toLocalDate() }
            .toSortedMap(compareByDescending { it })
            .map { (day, ops) -> dayLabel(day, today) to ops.sortedByDescending { it.occurredAt } }

    fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> RussianDates.day(day, today.year)
    }

    /** Search groups results by month first («Сентябрь 2026»), then by day. */
    fun groupByMonth(items: List<OperationItem>, zone: ZoneId): List<Pair<YearMonth, List<OperationItem>>> =
        items.groupBy { YearMonth.from(it.occurredAt.atZoneSameInstant(zone)) }
            .toSortedMap(compareByDescending { it })
            .map { (m, ops) -> m to ops }
}
