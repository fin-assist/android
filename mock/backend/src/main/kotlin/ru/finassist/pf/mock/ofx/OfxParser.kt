package ru.finassist.pf.mock.ofx

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Parsed T-Bank OFX 2.0.2 statement (see tbank-ofx-format.md). The format is flat XML, so a small scanner is
 * enough and it runs in plain JVM tests. Transactions that cannot be read are reported as [UnreadLine]s with
 * the line number of their `<STMTTRN>` tag — the same information the real backend keeps.
 */
data class OfxDocument(
    val bankId: String?,
    val accounts: List<OfxAccount>,
    val transactions: List<OfxTransaction>,
    val unreadLines: List<UnreadLine>,
    /** `DTSTART`/`DTEND` of the statement: the period the file covers even when it has no operations. */
    val coverageFrom: OffsetDateTime?,
    val coverageTo: OffsetDateTime?,
)

data class OfxAccount(
    /** Full `ACCTID`; the mock keeps it only to key transactions and derive the mask. */
    val id: String,
    val type: String,
) {
    val isOtherBank: Boolean get() = id.startsWith("OB$")
    val mask: String? get() = if (isOtherBank) null else "··" + id.takeLast(4)
    val typeName: String
        get() = when {
            isOtherBank -> "Счёт другого банка"
            type.equals("SAVINGS", ignoreCase = true) || id.startsWith("42301") -> "Накопительный"
            else -> "Текущий"
        }
}

data class OfxTransaction(
    val accountId: String,
    val fitId: String,
    val isDebit: Boolean,
    val postedAt: OffsetDateTime,
    /** Absolute amount in kopecks. */
    val amountMinor: Long,
    val name: String,
    val memo: String,
    val currency: String,
    val lineNumber: Int,
)

data class UnreadLine(val lineNumber: Int, val date: OffsetDateTime?, val reason: String)

sealed interface OfxCheck {
    data object Ok : OfxCheck
    data object Csv : OfxCheck
    data object NotOfx : OfxCheck
    data class WrongBank(val bankId: String?) : OfxCheck
}

object OfxParser {
    private val TAG = Regex("<([A-Z0-9.]+)>([^<]*)")
    private val ACCOUNT_BLOCK = Regex("<BANKACCTFROM>(.*?)</BANKACCTFROM>", RegexOption.DOT_MATCHES_ALL)
    private val STMTTRNRS_BLOCK = Regex("<STMTTRNRS>(.*?)</STMTTRNRS>", RegexOption.DOT_MATCHES_ALL)
    private val TRN_BLOCK = Regex("<STMTTRN>(.*?)</STMTTRN>", RegexOption.DOT_MATCHES_ALL)
    private val BANK_ID = Regex("<BANKID>([^<]*)")
    private val OFX_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    /** Cheap checks done before parsing, in the order the backend does them (api.md 3.2). */
    fun check(text: String): OfxCheck {
        val head = text.take(4096)
        return when {
            head.contains("<OFX>") || head.contains("OFXHEADER") -> {
                val bank = BANK_ID.find(text)?.groupValues?.get(1)?.trim()
                when {
                    !text.contains("<BANKMSGSRSV1>") -> OfxCheck.NotOfx
                    bank != null && !bank.equals("T-BANK", ignoreCase = true) -> OfxCheck.WrongBank(bank)
                    else -> OfxCheck.Ok
                }
            }
            head.contains("Дата операции") && head.contains(";") -> OfxCheck.Csv
            head.trimStart().startsWith("%PDF") -> OfxCheck.NotOfx
            else -> OfxCheck.NotOfx
        }
    }

    fun parse(text: String): OfxDocument {
        val lineStarts = lineStartOffsets(text)
        val accounts = mutableListOf<OfxAccount>()
        val transactions = mutableListOf<OfxTransaction>()
        val unread = mutableListOf<UnreadLine>()
        var coverageFrom: OffsetDateTime? = null
        var coverageTo: OffsetDateTime? = null
        val bankId = ACCOUNT_BLOCK.find(text)?.let { fields(it.groupValues[1])["BANKID"] }

        for (stmt in STMTTRNRS_BLOCK.findAll(text)) {
            val body = stmt.groupValues[1]
            val bodyOffset = stmt.groups[1]!!.range.first
            val accountFields = ACCOUNT_BLOCK.find(body)?.let { fields(it.groupValues[1]) } ?: continue
            val account = OfxAccount(id = accountFields["ACCTID"] ?: continue, type = accountFields["ACCTTYPE"] ?: "CHECKING")
            accounts += account
            val listFields = fields(body.substringAfter("<BANKTRANLIST>", "").substringBefore("<STMTTRN>"))
            listFields["DTSTART"]?.let(::parseDate)?.let { from -> coverageFrom = minOf(coverageFrom ?: from, from) }
            listFields["DTEND"]?.let(::parseDate)?.let { to -> coverageTo = maxOf(coverageTo ?: to, to) }

            for (trn in TRN_BLOCK.findAll(body)) {
                val lineNumber = lineNumberAt(lineStarts, bodyOffset + trn.range.first)
                val f = fields(trn.groupValues[1])
                val date = f["DTPOSTED"]?.let(::parseDate)
                val amount = f["TRNAMT"]?.let { runCatching { BigDecimal(it.trim()) }.getOrNull() }
                val name = f["NAME"]?.trim()
                when {
                    date == null -> unread += UnreadLine(lineNumber, null, if (f["DTPOSTED"] == null) "no_date" else "bad_date")
                    amount == null -> unread += UnreadLine(lineNumber, date, "bad_amount")
                    name.isNullOrEmpty() -> unread += UnreadLine(lineNumber, date, "no_description")
                    f["FITID"].isNullOrBlank() -> unread += UnreadLine(lineNumber, date, "truncated")
                    else -> transactions += OfxTransaction(
                        accountId = account.id,
                        fitId = f.getValue("FITID").trim(),
                        isDebit = amount.signum() < 0 || f["TRNTYPE"]?.trim().equals("DEBIT", ignoreCase = true),
                        postedAt = date,
                        amountMinor = amount.abs().movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact(),
                        name = unescape(name),
                        memo = unescape(f["MEMO"]?.trim().orEmpty()),
                        currency = f["CURSYM"]?.trim()?.ifEmpty { null } ?: "RUB",
                        lineNumber = lineNumber,
                    )
                }
            }
        }
        return OfxDocument(bankId, accounts, transactions, unread, coverageFrom, coverageTo)
    }

    private fun fields(block: String): Map<String, String> =
        TAG.findAll(block).associate { it.groupValues[1] to it.groupValues[2].trim() }

    /** `20260930175338.000[+3:MSK]` → OffsetDateTime; missing zone → Moscow. */
    fun parseDate(raw: String): OffsetDateTime? = runCatching {
        val digits = raw.trim().takeWhile { it.isDigit() }
        if (digits.length < 8) return null
        val padded = (digits + "000000").take(14)
        val local = java.time.LocalDateTime.parse(padded, OFX_DATE)
        val tz = Regex("""\[([+-]?\d+)(?::[A-Z]+)?]""").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 3
        local.atOffset(ZoneOffset.ofHours(tz))
    }.getOrNull()

    private fun unescape(s: String) = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")

    private fun lineStartOffsets(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        text.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
        return starts.toIntArray()
    }

    private fun lineNumberAt(lineStarts: IntArray, offset: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo + 1
    }
}
