package ru.finassist.pf.feature.operations.impl.domain

import ru.finassist.pf.core.api.model.Account
import ru.finassist.pf.core.api.model.OperationItem
import ru.finassist.pf.core.api.model.OperationKind
import ru.finassist.pf.core.api.model.OperationStatus
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.RussianDates
import java.time.LocalDate
import java.time.OffsetDateTime
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
     * Money in the operation's currency (api.md: `currency`, ISO 4217). Rubles keep the design-system «₽»;
     * other currencies get their symbol or code.
     */
    fun money(amount: Money, currency: String, sign: Money.Sign): String {
        val text = amount.format(sign)
        if (currency == "RUB") return text
        val symbol = runCatching { java.util.Currency.getInstance(currency).getSymbol(java.util.Locale("ru")) }.getOrDefault(currency)
        return text.removeSuffix("₽").trimEnd() + Money.NBSP + symbol
    }

    /**
     * Row: expense `−2 340 ₽`, income `+15 000 ₽` (positive colour), own transfer without sign and muted, unknown
     * kind — no sign, no colour (api.md 4.1). Pending holds get «холд» in the subtitle.
     */
    fun row(item: OperationItem): RowModel {
        val amount = when (item.kind) {
            OperationKind.EXPENSE -> money(-item.amount, item.currency, Money.Sign.AUTO)
            OperationKind.INCOME -> money(item.amount, item.currency, Money.Sign.ALWAYS)
            OperationKind.OWN_TRANSFER, OperationKind.UNKNOWN -> money(item.amount, item.currency, Money.Sign.NONE)
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

    /**
     * Search results (mockup `Search`): one group per month, newest first, labelled «Сентябрь» («Сентябрь 2025» in
     * another year); each row says its day instead of the category — «24 сентября» (with the year in another
     * year), plus «холд» for a pending hold.
     */
    fun searchGroups(items: List<OperationItem>, zone: ZoneId, today: LocalDate): List<Pair<String, List<RowModel>>> =
        items.groupBy { YearMonth.from(it.occurredAt.atZoneSameInstant(zone)) }
            .toSortedMap(compareByDescending { it })
            .map { (month, ops) ->
                val name = RussianDates.monthNominative(month.month).replaceFirstChar { it.uppercase() }
                val label = if (month.year == today.year) name else "$name ${month.year}"
                label to ops.sortedByDescending { it.occurredAt }.map { op ->
                    val day = RussianDates.day(op.occurredAt.atZoneSameInstant(zone).toLocalDate(), today.year)
                    row(op).copy(subtitle = if (op.status == OperationStatus.PENDING) "$day · холд" else day)
                }
            }

    /** The kind above the amount on the operation card: «Списание», «Зачисление», «Перевод». */
    fun kindLabel(kind: OperationKind): String = when (kind) {
        OperationKind.EXPENSE -> "Списание"
        OperationKind.INCOME -> "Зачисление"
        OperationKind.OWN_TRANSFER -> "Перевод"
        OperationKind.UNKNOWN -> "Операция"
    }

    /** «25.09.2026, 19:11» in [zone]: the date under the amount on the operation card. */
    fun numericDateTime(at: OffsetDateTime, zone: ZoneId): String {
        val t = at.atZoneSameInstant(zone)
        return "%02d.%02d.%04d, %02d:%02d".format(t.dayOfMonth, t.monthValue, t.year, t.hour, t.minute)
    }

    /**
     * First day of the server's default search window: known only from a search made without a period and without
     * «за всё время» (then the response range is that window); `null` otherwise.
     */
    fun defaultWindowStart(range: DateRange?, filter: OperationsFilter, allTime: Boolean): LocalDate? =
        range?.takeIf { !allTime && filter.from == null && filter.to == null }?.from?.toLocalDate()

    /** «с октября 2025»: start of the default search window, as the search footer and the period sheet name it. */
    fun windowStart(from: LocalDate): String = "с ${RussianDates.monthGenitive(from.month)} ${from.year}"
}
