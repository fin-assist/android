package ru.finassist.pf.core.mockbackend.engine

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parser for T-Bank OFX 2.0.2 (XML) exports — see `tbank-ofx-format.md`.
 * Tolerant: a `STMTTRN` with a broken field becomes an [UnreadLine] instead of failing the file,
 * which is what the contract's «непрочитанные строки» are about.
 */
object OfxParser {
    sealed interface Result {
        data class Ok(val statement: ParsedStatement) : Result
        /** The file is not OFX at all (HTML from a login page, PDF, …). */
        data object WrongFormat : Result
        data object CsvNotAccepted : Result
        data class WrongBank(val bankId: String?) : Result
    }

    private val TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun parse(bytes: ByteArray): Result {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8)
        if (looksLikeCsv(head)) return Result.CsvNotAccepted
        val start = head.indexOf("<OFX>")
        if (start < 0 || !head.contains("OFXHEADER")) return Result.WrongFormat
        val text = String(bytes, Charsets.UTF_8)
        val xml = text.substring(text.indexOf("<OFX>"))
        // Line numbers for unread lines refer to the original file: offset of the <OFX> root.
        val baseLine = text.substring(0, text.indexOf("<OFX>")).count { it == '\n' }

        val doc = try {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
                .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            return Result.WrongFormat
        }
        val root = doc.documentElement
        val bankIds = root.elements("BANKID").map { it.textContent.trim() }.toSet()
        if (bankIds.isNotEmpty() && bankIds.any { it != "T-BANK" }) return Result.WrongBank(bankIds.first { it != "T-BANK" })

        val accounts = mutableListOf<Account>()
        val transactions = mutableListOf<Transaction>()
        val unread = mutableListOf<UnreadLine>()
        // Approximate line numbers: count STMTTRN occurrences in order and map to source lines.
        val trnLines = Regex("<STMTTRN>").findAll(xml).map { baseLine + 1 + xml.substring(0, it.range.first).count { c -> c == '\n' } }.toList()
        var trnIndex = 0
        var coveredFrom: OffsetDateTime? = null
        var coveredTo: OffsetDateTime? = null

        for (stmt in root.elements("STMTTRNRS")) {
            val acctId = stmt.first("ACCTID")?.textContent?.trim() ?: continue
            val acctType = stmt.first("ACCTTYPE")?.textContent?.trim()
            val type = when {
                acctId.startsWith("OB$") -> AccountType.OtherBank
                acctType == "SAVINGS" -> AccountType.Savings
                else -> AccountType.Current
            }
            accounts += Account(acctId, type)
            stmt.first("DTSTART")?.textContent?.trim()?.let(::parseDate)?.let { d -> coveredFrom = coveredFrom?.let { if (d.isBefore(it)) d else it } ?: d }
            stmt.first("DTEND")?.textContent?.trim()?.let(::parseDate)?.let { d -> coveredTo = coveredTo?.let { if (d.isAfter(it)) d else it } ?: d }
            for (trn in stmt.elements("STMTTRN")) {
                val line = trnLines.getOrElse(trnIndex) { baseLine }
                trnIndex++
                val dateText = trn.first("DTPOSTED")?.textContent?.trim()
                val date = dateText?.let(::parseDate)
                val amountText = trn.first("TRNAMT")?.textContent?.trim()
                val amount = amountText?.let(::parseAmount)
                val name = trn.first("NAME")?.textContent?.trim()
                val typeText = trn.first("TRNTYPE")?.textContent?.trim()
                val fit = trn.first("FITID")?.textContent?.trim()
                val memo = trn.first("MEMO")?.textContent?.trim().orEmpty().replace(' ', ' ')
                val currency = trn.first("CURSYM")?.textContent?.trim() ?: "RUB"
                when {
                    dateText == null -> unread += UnreadLine(line, null, "no_date")
                    date == null -> unread += UnreadLine(line, null, "bad_date")
                    amount == null -> unread += UnreadLine(line, date, "bad_amount")
                    name.isNullOrEmpty() -> unread += UnreadLine(line, date, "no_description")
                    fit.isNullOrEmpty() || typeText == null -> unread += UnreadLine(line, date, "truncated")
                    else -> {
                        val direction = if (typeText == "CREDIT" || (typeText != "DEBIT" && amount > 0)) Direction.Credit else Direction.Debit
                        transactions += Transaction(acctId, fit, direction, date, kotlin.math.abs(amount), name, memo, currency)
                    }
                }
            }
        }
        return Result.Ok(ParsedStatement(accounts, transactions, unread, bankIds.firstOrNull(), coveredFrom, coveredTo))
    }

    private fun looksLikeCsv(head: String): Boolean {
        val firstLine = head.lineSequence().firstOrNull()?.trim() ?: return false
        return firstLine.count { it == ';' } >= 3 || firstLine.startsWith("Дата операции")
    }

    /** `20260930175338.000[+3:MSK]` → OffsetDateTime. Missing zone → Moscow. */
    fun parseDate(s: String): OffsetDateTime? = runCatching {
        val digits = s.take(14)
        val ldt = LocalDateTime.parse(digits, TS)
        val zone = Regex("""\[([+-]?\d+)(?::[A-Z]+)?]""").find(s)?.groupValues?.get(1)?.toInt() ?: 3
        ldt.atOffset(ZoneOffset.ofHours(zone))
    }.getOrNull()

    /** `-50818.1` → kopecks (signed). Decimal point only; no thousands separators in OFX. */
    fun parseAmount(s: String): Long? {
        val m = Regex("""^(-?)(\d+)(?:\.(\d{1,2}))?$""").matchEntire(s) ?: return null
        val sign = if (m.groupValues[1] == "-") -1 else 1
        val whole = m.groupValues[2].toLong()
        val frac = m.groupValues[3].padEnd(2, '0').ifEmpty { "00" }.toLong()
        return sign * (whole * 100 + frac)
    }

    private fun Element.elements(tag: String): List<Element> {
        val list = getElementsByTagName(tag)
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun Element.first(tag: String): Element? = elements(tag).firstOrNull()
}
