package ru.finassist.pf.core.mockbackend.engine

import java.time.OffsetDateTime

/*
 * In-memory model of the mock backend. It mirrors what `bank-statement-data` would hold, reduced to what the
 * client contract needs. Amounts are kopecks (Long).
 */

enum class AccountType { Current, Savings, OtherBank }

data class Account(val id: String, val type: AccountType) {
    /** «··4821»; other-bank accounts have an opaque `OB$…` id and no mask. */
    val mask: String? get() = if (type == AccountType.OtherBank) null else "··" + id.takeLast(4)
    val typeName: String get() = when (type) {
        AccountType.Current -> "Текущий"
        AccountType.Savings -> "Накопительный"
        AccountType.OtherBank -> "Счёт другого банка"
    }
    val spoken: String get() = when (type) {
        AccountType.Current -> "текущий счёт, последние цифры ${id.takeLast(4)}"
        AccountType.Savings -> "накопительный счёт, последние цифры ${id.takeLast(4)}"
        AccountType.OtherBank -> "счёт другого банка"
    }
}

enum class Direction { Debit, Credit }

/** One `STMTTRN` as read from the file. */
data class Transaction(
    val accountId: String,
    val fitId: String,
    val direction: Direction,
    val postedAt: OffsetDateTime,
    /** Absolute amount, kopecks. */
    val amount: Long,
    val name: String,
    /** T-Bank category (`MEMO`). */
    val bankCategory: String,
    val currency: String,
)

/** Result of parsing one file. */
data class ParsedStatement(
    val accounts: List<Account>,
    val transactions: List<Transaction>,
    /** Lines the parser could not read: line number, parsed date if any, reason code. */
    val unreadLines: List<UnreadLine>,
    val bankId: String?,
    /** Union of `DTSTART`..`DTEND` over accounts: the period the file claims to cover (not just first..last operation). */
    val coveredFrom: OffsetDateTime?,
    val coveredTo: OffsetDateTime?,
)

data class UnreadLine(val lineNumber: Int, val date: OffsetDateTime?, val reason: String) {
    val reasonName: String get() = when (reason) {
        "bad_amount" -> "не распознали сумму"
        "bad_date" -> "не распознали дату"
        "no_date" -> "нет даты"
        "no_description" -> "нет описания"
        "truncated" -> "строка оборвана"
        else -> "не удалось прочитать"
    }
}

/** How an operation counts in analytics after rules were applied. */
sealed interface Accounting {
    /** Counted by its final category. */
    data object Regular : Accounting
    /** Half of a matched own-transfer pair; excluded from analytics. */
    data class OwnTransfer(val pairId: String) : Accounting
    /** Refund of an earlier purchase: reduces [reducesCategoryId] (null → "без категории") in its own month. */
    data class Refund(val reducesCategoryId: String?) : Accounting
}

/** Stored operation: a transaction plus everything the rules derived. */
data class Operation(
    val id: String,
    val uploadIds: MutableSet<String>,
    val tx: Transaction,
    /** Final category id; manual change overrides the rule result. */
    var categoryId: String,
    var isCategoryManual: Boolean,
    var accounting: Accounting,
) {
    val direction: Direction get() = tx.direction
}

/** Two operations shown as one row «Перевод между счетами». */
data class OwnTransferPair(val id: String, val debit: Operation, val credit: Operation)

data class Upload(
    val id: String,
    val fileName: String,
    val uploadedAt: OffsetDateTime,
    var status: String,            // processing | done
    var operationCount: Int,
    var newCount: Int,
    var duplicateCount: Int,
    var unreadLines: List<UnreadLine>,
    var firstOperationAt: OffsetDateTime?,
    var lastOperationAt: OffsetDateTime?,
    val coveredFrom: OffsetDateTime?,
    val coveredTo: OffsetDateTime?,
)
