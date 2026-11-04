package ru.finassist.pf.mock.domain

import ru.finassist.pf.core.api.model.ChartPageInfo
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.FieldErrorCodes
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.mock.MockBackend
import ru.finassist.pf.mock.apiError

/** In-memory opaque tokens belong to the authenticated mock user and an observable history revision. */
class MockChartPages(private val backend: MockBackend) {
    data class Cursor(val owner: String, val mode: TransferMode, val revision: String, val index: Int)
    private val revisions = mutableMapOf<TransferMode, Pair<String, String>>()
    private val cursors = mutableMapOf<String, Cursor>()

    private fun revision(mode: TransferMode): String {
        val fingerprint = listOf(backend.profile.userId, backend.profileZone().id,
            backend.now().atZoneSameInstant(backend.profileZone()).toLocalDate(), backend.dataChanges.version,
            backend.ledger.all.sortedBy { it.id }).toString()
        return revisions[mode]?.takeIf { it.first == fingerprint }?.second ?: backend.newUserId().also {
            revisions[mode] = fingerprint to it
        }
    }

    fun info(mode: TransferMode, page: HistoryChartPage): ChartPageInfo {
        val revision = revision(mode)
        fun token(index: Int?): String? = index?.let {
            val value = Cursor(backend.profile.userId, mode, revision, it)
            cursors.entries.firstOrNull { entry -> entry.value == value }?.key ?: backend.newUserId().also { key -> cursors[key] = value }
        }
        return ChartPageInfo(revision, token(page.previous), token(page.next))
    }

    fun resolve(token: String): Cursor {
        if (token.isEmpty()) invalid(FieldErrorCodes.REQUIRED)
        if (token.length > 2048) invalid(FieldErrorCodes.INVALID_FORMAT)
        val cursor = cursors[token]?.takeIf { it.owner == backend.profile.userId } ?: invalid(FieldErrorCodes.INVALID_FORMAT)
        if (cursor.revision != revision(cursor.mode)) invalid(FieldErrorCodes.OUT_OF_RANGE)
        return cursor
    }

    private fun invalid(code: String): Nothing = throw backend.apiError(ErrorCodes.VALIDATION_ERROR, 400,
        details = listOf(AppError.FieldError("cursor", code, "Invalid chart cursor")))
}
