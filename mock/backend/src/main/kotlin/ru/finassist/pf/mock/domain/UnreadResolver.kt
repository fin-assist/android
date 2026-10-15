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

    /** Runs inside the import of [newer], after its operations are in the ledger; [older] — the uploads before it. */
    fun close(newer: Upload, older: List<Upload>) {
        for (upload in older) {
            for (line in upload.openUnreadLines) {
                outcome(newer, upload, line)?.let { read -> upload.closedBy[line.lineNumber] = Closure(newer.id, read) }
            }
        }
    }

    /**
     * [deleted] is gone (its operations already removed from the ledger): the lines it closed are checked again against
     * the later uploads that remain. [uploads] — the remaining ones, newest first.
     */
    fun reopen(deleted: String, uploads: List<Upload>) {
        uploads.forEachIndexed { i, upload ->
            val reopened = upload.closedBy.filterValues { it.uploadId == deleted }.keys
            if (reopened.isEmpty()) return@forEachIndexed
            reopened.forEach(upload.closedBy::remove)
            val later = uploads.subList(0, i).filter { it.isDone }
            upload.unreadLines.filter { it.lineNumber in reopened }.forEach { line ->
                for (newer in later) {
                    val read = outcome(newer, upload, line) ?: continue
                    upload.closedBy[line.lineNumber] = Closure(newer.id, read)
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
            val day = date.atZoneSameInstant(zone()).toLocalDate()
            if (day !in period.from.atZoneSameInstant(zone()).toLocalDate()..period.to.atZoneSameInstant(zone()).toLocalDate()) return null
            return newerUnread.none { it.date?.atZoneSameInstant(zone())?.toLocalDate() == day }
        }
        // 3. No date, no FITID: the newer statement covers the whole period of the old one; read unless unread lines there.
        val oldPeriod = old.accountPeriods[account] ?: return null
        if (!period.covers(oldPeriod)) return null
        return newerUnread.none { it.date == null || it.date in oldPeriod }
    }
}
