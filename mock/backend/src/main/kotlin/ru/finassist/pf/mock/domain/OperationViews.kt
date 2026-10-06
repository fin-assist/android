package ru.finassist.pf.mock.domain

import ru.finassist.pf.core.api.model.Account
import ru.finassist.pf.core.api.model.OperationDetails
import ru.finassist.pf.core.api.model.OperationItem
import ru.finassist.pf.core.api.model.OperationKind
import ru.finassist.pf.core.api.model.OperationStatus
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.mock.ofx.OfxAccount
import java.time.OffsetDateTime
import java.time.ZoneId

/** A record as the API shows it: either a single operation or an own-transfer pair. */
sealed interface Record {
    val id: String
    val occurredAt: OffsetDateTime
    val amountMinor: Long

    data class Single(val op: Operation) : Record {
        override val id get() = op.id
        override val occurredAt get() = op.occurredAt
        override val amountMinor get() = op.amountMinor
    }

    data class Pair(val pair: OwnTransferPair) : Record {
        override val id get() = pair.id
        override val occurredAt get() = pair.debit.occurredAt
        override val amountMinor get() = pair.debit.amountMinor
    }
}

/** DTO mapping shared by the operations and statements APIs. */
class OperationViews(private val ledger: Ledger, private val zone: ZoneId) {

    /** Every record, newest first: pairs as one record, everything else as singles. */
    fun records(): List<Record> {
        val result = ArrayList<Record>()
        val seenPairs = HashSet<String>()
        for (op in ledger.all) {
            val pair = ledger.pairOf(op)
            if (pair == null) {
                result += Record.Single(op)
            } else if (seenPairs.add(pair.id)) {
                result += Record.Pair(pair)
            }
        }
        return result.sortedByDescending { it.occurredAt }
    }

    fun record(id: String): Record? = ledger.pair(id)?.let { Record.Pair(it) }
        ?: ledger.byId(id)?.let { op -> ledger.pairOf(op)?.let { Record.Pair(it) } ?: Record.Single(op) }

    fun item(record: Record): OperationItem = when (record) {
        is Record.Single -> record.op.let { op ->
            OperationItem(
                id = op.id, kind = if (op.isDebit) OperationKind.EXPENSE else OperationKind.INCOME,
                occurredAt = op.occurredAt, amount = Money(op.amountMinor), currency = op.currency, title = op.name,
                categoryId = op.category.id, categoryName = op.category.name, categoryIcon = op.category.icon,
                isCategoryManual = op.isManual, note = note(op), status = OperationStatus.POSTED,
                account = account(op.account, spoken = false),
            )
        }
        is Record.Pair -> record.pair.let { p ->
            OperationItem(
                id = p.id, kind = OperationKind.OWN_TRANSFER, occurredAt = p.debit.occurredAt,
                amount = Money(p.debit.amountMinor), currency = p.debit.currency, title = "Перевод между счетами",
                categoryId = p.debit.category.id, categoryName = p.debit.category.name, categoryIcon = p.debit.category.icon,
                isCategoryManual = false, note = "не в тратах", status = OperationStatus.POSTED,
                fromAccount = account(p.debit.account, spoken = false), toAccount = account(p.credit.account, spoken = false),
            )
        }
    }

    fun details(record: Record): OperationDetails = when (record) {
        is Record.Single -> record.op.let { op ->
            OperationDetails(
                id = op.id, kind = if (op.isDebit) OperationKind.EXPENSE else OperationKind.INCOME,
                occurredAt = op.occurredAt, amount = Money(op.amountMinor), currency = op.currency, title = op.name,
                categoryId = op.category.id, categoryName = op.category.name, categoryIcon = op.category.icon,
                isCategoryManual = op.isManual, note = note(op), status = OperationStatus.POSTED,
                account = account(op.account, spoken = true), description = op.name, noteDetails = noteDetails(op),
                bankCategory = op.bankCategory, sourceName = ru.finassist.pf.mock.MockBackend.SOURCE_NAME,
            )
        }
        is Record.Pair -> record.pair.let { p ->
            OperationDetails(
                id = p.id, kind = OperationKind.OWN_TRANSFER, occurredAt = p.debit.occurredAt,
                amount = Money(p.debit.amountMinor), currency = p.debit.currency, title = "Перевод между счетами",
                categoryId = p.debit.category.id, categoryName = p.debit.category.name, categoryIcon = p.debit.category.icon,
                isCategoryManual = false, note = "не в тратах", status = OperationStatus.POSTED,
                fromAccount = account(p.debit.account, spoken = true), toAccount = account(p.credit.account, spoken = true),
                noteDetails = "Не считаем тратой и доходом", sourceName = ru.finassist.pf.mock.MockBackend.SOURCE_NAME,
            )
        }
    }

    private fun note(op: Operation): String? = when {
        op.isOwnTransferCategory -> "не в тратах"
        op.isRefund -> op.refundTarget?.let { "возврат · ${it.name}" } ?: "возврат"
        else -> null
    }

    private fun noteDetails(op: Operation): String? = when {
        op.isOwnTransferCategory -> "Не считаем тратой и доходом"
        op.isRefund -> {
            val month = RussianDates.monthPrepositional(op.occurredAt.atZoneSameInstant(zone).month)
            op.refundTarget?.let { "Уменьшает расходы «${it.name}» в $month" } ?: "Уменьшает общие расходы в $month"
        }
        else -> null
    }

    private fun account(a: OfxAccount, spoken: Boolean) = Account(
        typeName = a.typeName,
        mask = a.mask,
        spoken = if (!spoken) null else if (a.isOtherBank) "счёт другого банка" else "${a.typeName.lowercase()} счёт, последние цифры ${a.id.takeLast(4)}",
    )
}
