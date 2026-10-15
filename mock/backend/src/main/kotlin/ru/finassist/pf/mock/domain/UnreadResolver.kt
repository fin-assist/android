package ru.finassist.pf.mock.domain

import ru.finassist.pf.mock.MockBackend.Upload
import ru.finassist.pf.mock.ofx.UnreadLine
import java.time.ZoneId

/**
 * Closes unread lines of earlier uploads that a later upload has read (api.md 3.6, «Закрытие строк»; FIN-79). A closed
 * line stays with its upload and remembers the upload that closed it; the API shows only open lines.
 */
class UnreadResolver(private val ledger: Ledger, private val zone: () -> ZoneId) {

    /** Runs inside the import of [newer], after its operations are in the ledger; [older] — the uploads before it. */
    fun close(newer: Upload, older: List<Upload>) {
        for (upload in older) {
            for (line in upload.openUnreadLines) {
                if (closes(newer, upload, line)) upload.resolvedBy[line.lineNumber] = newer.id
            }
        }
    }

    /**
     * [deleted] is gone (its operations already removed from the ledger): the lines it closed are checked again against
     * the later uploads that remain. [uploads] — the remaining ones, newest first.
     */
    fun reopen(deleted: String, uploads: List<Upload>) {
        uploads.forEachIndexed { i, upload ->
            val reopened = upload.resolvedBy.filterValues { it == deleted }.keys
            if (reopened.isEmpty()) return@forEachIndexed
            reopened.forEach(upload.resolvedBy::remove)
            val later = uploads.subList(0, i).filter { it.isDone }
            upload.unreadLines.filter { it.lineNumber in reopened }.forEach { line ->
                later.firstOrNull { closes(it, upload, line) }?.let { upload.resolvedBy[line.lineNumber] = it.id }
            }
        }
    }

    private fun closes(newer: Upload, old: Upload, line: UnreadLine): Boolean {
        val account = line.accountId ?: return false
        // 1. By FITID: the operation with this pair is in the ledger — saved by [newer] or there already.
        if (line.fitId != null) return ledger.byKey(account, line.fitId) != null
        val period = newer.accountPeriods[account] ?: return false
        val newerUnread = newer.unreadLines.filter { it.accountId == account }
        val date = line.date
        if (date != null) {
            // 2. By date: the newer statement of the account covers the day and has no unread line of its own that day.
            val day = date.atZoneSameInstant(zone()).toLocalDate()
            return date in period && newerUnread.none { it.date?.atZoneSameInstant(zone())?.toLocalDate() == day }
        }
        // 3. No date, no FITID: the newer statement covers the whole period of the old one and reads all of it.
        val oldPeriod = old.accountPeriods[account] ?: return false
        return period.covers(oldPeriod) && newerUnread.none { it.date == null || it.date in oldPeriod }
    }
}
