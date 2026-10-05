package ru.finassist.pf.core.mockbackend.engine

import ru.finassist.pf.core.network.dto.AccountDto
import ru.finassist.pf.core.network.dto.OperationDto
import ru.finassist.pf.core.network.dto.RangeDto
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Wire formatting helpers shared by the engine and the HTTP layer. */
class Wire(val zone: ZoneId) {
    private val fmt: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun time(t: OffsetDateTime): String = t.atZoneSameInstant(zone).toOffsetDateTime().format(fmt)
    fun dayStart(d: LocalDate): OffsetDateTime = d.atStartOfDay(zone).toOffsetDateTime()
    fun dayStartStr(d: LocalDate): String = time(dayStart(d))
    fun monthRange(ym: YearMonth): RangeDto = RangeDto(dayStartStr(ym.atDay(1)), dayStartStr(ym.plusMonths(1).atDay(1)))
    fun dayRange(fromInclusive: LocalDate, toInclusive: LocalDate): RangeDto = RangeDto(dayStartStr(fromInclusive), dayStartStr(toInclusive.plusDays(1)))
    fun parse(s: String): OffsetDateTime = OffsetDateTime.parse(s)
    fun month(ym: YearMonth): String = "%04d-%02d".format(ym.year, ym.monthValue)
    fun localDate(t: OffsetDateTime): LocalDate = t.atZoneSameInstant(zone).toLocalDate()

    fun account(a: Account?, spoken: Boolean): AccountDto? = a?.let { AccountDto(it.typeName, it.mask, if (spoken) it.spoken else null) }

    /** Row shape shared by feed, search, details and change-category responses. */
    fun operation(ledger: Ledger, op: Operation, details: Boolean): OperationDto {
        val cat = Categories.byId(op.categoryId)
        val note = when (val a = op.accounting) {
            is Accounting.OwnTransfer -> "не в тратах"
            is Accounting.Refund -> "возврат · " + (a.reducesCategoryId?.let { Categories.byId(it)?.name } ?: "без категории")
            Accounting.Regular -> if (op.categoryId == Categories.OWN_TRANSFER) "не в тратах" else null
        }
        val noteDetails = when (val a = op.accounting) {
            is Accounting.OwnTransfer -> "Не считаем тратой и доходом"
            is Accounting.Refund -> {
                val month = RuMonths.genitive[localDate(op.tx.postedAt).monthValue - 1]
                "Уменьшает расходы «${a.reducesCategoryId?.let { Categories.byId(it)?.name } ?: "Без категории"}» в $month"
            }
            Accounting.Regular -> if (op.categoryId == Categories.OWN_TRANSFER) "Не считаем тратой и доходом" else null
        }
        return OperationDto(
            id = op.id,
            kind = if (op.direction == Direction.Debit) "expense" else "income",
            occurredAt = time(op.tx.postedAt),
            amount = op.tx.amount,
            currency = op.tx.currency,
            title = op.tx.name,
            categoryId = op.categoryId,
            categoryName = cat?.name ?: "Без категории",
            categoryIcon = cat?.icon ?: "circle",
            isCategoryManual = op.isCategoryManual,
            note = note,
            status = "posted",
            account = account(ledger.accounts[op.tx.accountId], details),
            description = if (details) op.tx.name else null,
            noteDetails = if (details) noteDetails else null,
            bankCategory = if (details) op.tx.bankCategory else null,
            sourceName = if (details) "Выписка Т-Банка" else null,
        )
    }

    fun pair(ledger: Ledger, pair: OwnTransferPair, details: Boolean): OperationDto = OperationDto(
        id = pair.id,
        kind = "own_transfer",
        occurredAt = time(pair.debit.tx.postedAt),
        amount = pair.debit.tx.amount,
        currency = pair.debit.tx.currency,
        title = "Перевод между счетами",
        categoryId = Categories.OWN_TRANSFER,
        categoryName = "Между своими счетами",
        categoryIcon = "transfer",
        isCategoryManual = pair.debit.isCategoryManual || pair.credit.isCategoryManual,
        note = "не в тратах",
        status = "posted",
        fromAccount = account(ledger.accounts[pair.debit.tx.accountId], details),
        toAccount = account(ledger.accounts[pair.credit.tx.accountId], details),
        noteDetails = if (details) "Не считаем тратой и доходом" else null,
        sourceName = if (details) "Выписка Т-Банка" else null,
    )
}

object RuMonths {
    val genitive = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    val nominative = listOf("январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь")
}
