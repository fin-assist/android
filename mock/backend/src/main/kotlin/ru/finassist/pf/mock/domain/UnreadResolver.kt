package ru.finassist.pf.mock.domain

import ru.finassist.pf.mock.MockBackend.Closure
import ru.finassist.pf.mock.MockBackend.Upload
import ru.finassist.pf.mock.ofx.UnreadLine
import java.time.ZoneId

/**
 * Closes unread lines of earlier uploads that a later upload covers (api.md 3.6, «Закрытие строк»; FIN-79): the line is
 * read now, or it is unread again and the later upload's record replaces it. A closed line stays with its upload and
 * remembers the upload that closed it; the API shows only open lines.
 */
class UnreadResolver(private val ledger: Ledger, private val zone: () -> ZoneId) {

    /**
     * Recomputes every closure from scratch: a line of an upload is closed by the earliest later finished upload that
     * covers it. Run when an upload commits (pass it as [committing]: not marked done yet) and after one is deleted or
     * discarded. The result depends neither on the order uploads finish in nor on what was deleted before.
     * [uploads] — all of them, newest first (the order they were started in).
     */
    fun recompute(uploads: List<Upload>, committing: Upload? = null) {
        val done = uploads.filter { it.isDone || it === committing }
        done.forEachIndexed { i, upload ->
            upload.closedBy.clear()
            // Later uploads, the earliest first.
            val later = done.subList(0, i).asReversed()
            upload.unreadLines.forEachIndexed { index, line ->
                for (newer in later) {
                    val read = outcome(newer, upload, line) ?: continue
                    upload.closedBy[index] = Closure(newer.id, read)
                    break
                }
            }
        }
    }

    /** null — [newer] does not cover the line; true — read now; false — unread again, [newer]'s record replaces it. */
    private fun outcome(newer: Upload, old: Upload, line: UnreadLine): Boolean? {
        val account = line.accountId ?: return null
        val newerUnread = newer.unreadLines.filter { it.accountId == account }
        // 1. By FITID: [newer] has the operation with this pair (saved now or already there), or fails to read it again.
        // An operation another upload brought does not count: the closing upload is the one that read the line.
        if (line.fitId != null) {
            return when {
                ledger.byKey(account, line.fitId)?.uploadIds?.contains(newer.id) == true -> true
                newerUnread.any { it.fitId == line.fitId } -> false
                else -> null
            }
        }
        val period = newer.accountPeriods[account] ?: return null
        val date = line.date
        if (date != null) {
            // 2. By date: the newer statement of the account covers the day; read unless it has an unread line that day.
            // Days, not moments: a `DTEND` without time parses to midnight and still covers its whole day.
            val day = day(date)
            if (day !in days(period)) return null
            return newerUnread.none { it.date?.let(::day) == day }
        }
        // 3. No date, no FITID: the newer statement covers the whole period of the old one; read unless unread lines there.
        // By days, like rule 2.
        val oldPeriod = old.accountPeriods[account] ?: return null
        val oldDays = days(oldPeriod)
        val newDays = days(period)
        if (newDays.start > oldDays.start || newDays.endInclusive < oldDays.endInclusive) return null
        return newerUnread.none { it.date == null || day(it.date) in oldDays }
    }

    private fun day(at: java.time.OffsetDateTime) = at.atZoneSameInstant(zone()).toLocalDate()

    private fun days(p: ru.finassist.pf.mock.ofx.StatementPeriod) = day(p.from)..day(p.to)
}
