package ru.finassist.pf.feature.operations.impl.domain

import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.money.MoneyFormat
import ru.finassist.pf.core.common.money.SignStyle
import ru.finassist.pf.core.network.codes.OperationKind
import ru.finassist.pf.core.network.codes.OperationStatus
import ru.finassist.pf.core.network.dto.AccountDto
import ru.finassist.pf.core.network.dto.OperationDto
import java.time.OffsetDateTime

data class AccountRef(val typeName: String, val mask: String?, val spoken: String?) {
    val label: String get() = if (mask != null) "$typeName $mask" else typeName
}

/** Operation as screens use it: parsed times, enum kinds, display strings ready. */
data class Operation(
    val id: String,
    val kind: OperationKind,
    val occurredAt: OffsetDateTime,
    val amount: Money,
    val title: String,
    val categoryId: String,
    val categoryName: String,
    val categoryIcon: String,
    val isCategoryManual: Boolean,
    val note: String?,
    val status: OperationStatus,
    val account: AccountRef?,
    val fromAccount: AccountRef?,
    val toAccount: AccountRef?,
    // details
    val description: String?,
    val postedAt: OffsetDateTime?,
    val noteDetails: String?,
    val bankCategory: String?,
    val mcc: String?,
    val sourceName: String?,
) {
    val isIncome: Boolean get() = kind == OperationKind.Income
    val isPair: Boolean get() = kind == OperationKind.OwnTransfer

    /** «−2 340 ₽», «+1 250 ₽», «20 000 ₽» (pair / unknown kind: no sign, no colour). */
    val amountText: String get() = when (kind) {
        OperationKind.Expense -> MoneyFormat.rub(amount, SignStyle.Expense)
        OperationKind.Income -> MoneyFormat.rub(amount, SignStyle.Income)
        else -> MoneyFormat.rub(amount)
    }

    val kindName: String get() = when (kind) {
        OperationKind.Expense -> "Списание"
        OperationKind.Income -> "Зачисление"
        OperationKind.OwnTransfer -> "Перевод"
        OperationKind.Unknown -> "Операция"
    }

    companion object {
        fun from(d: OperationDto) = Operation(
            id = d.id,
            kind = OperationKind.fromWire(d.kind),
            occurredAt = OffsetDateTime.parse(d.occurredAt),
            amount = Money(d.amount),
            title = d.title,
            categoryId = d.categoryId,
            categoryName = d.categoryName,
            categoryIcon = d.categoryIcon,
            isCategoryManual = d.isCategoryManual,
            note = d.note,
            status = OperationStatus.fromWire(d.status),
            account = d.account?.toRef(),
            fromAccount = d.fromAccount?.toRef(),
            toAccount = d.toAccount?.toRef(),
            description = d.description,
            postedAt = d.postedAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
            noteDetails = d.noteDetails,
            bankCategory = d.bankCategory,
            mcc = d.mcc,
            sourceName = d.sourceName,
        )

        private fun AccountDto.toRef() = AccountRef(typeName, mask, spoken)
    }
}

data class MonthSummary(val month: String, val expense: Money, val income: Money, val dataTo: OffsetDateTime?)

data class FeedPage(
    val items: List<Operation>,
    val summary: List<MonthSummary>,
    val stale: Boolean,
    val lastOperationAt: OffsetDateTime?,
    val lastUploadedAt: OffsetDateTime?,
    val nextBefore: String?,
)

data class SearchResult(
    val items: List<Operation>,
    val totalCount: Int,
    val rangeFrom: OffsetDateTime?,
    val rangeTo: OffsetDateTime?,
    val hasOlderData: Boolean,
)
