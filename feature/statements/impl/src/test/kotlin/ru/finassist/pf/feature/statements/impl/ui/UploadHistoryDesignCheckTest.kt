package ru.finassist.pf.feature.statements.impl.ui

import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorSeverity
import ru.finassist.pf.core.common.error.UserMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.model.StatementsList
import ru.finassist.pf.core.api.model.StatementsSummary
import ru.finassist.pf.core.api.model.Upload
import ru.finassist.pf.core.api.model.UploadStatus
import ru.finassist.pf.core.screenshot.DesignCheck
import java.time.OffsetDateTime

/**
 * Artboards `UploadHistory*`: three uploads, delete confirmation, after deletion, no uploads, lines of an upload read
 * by a later one (FIN-79).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UploadHistoryDesignCheckTest {
    private fun t(s: String) = OffsetDateTime.parse(s)

    private fun upload(id: String, file: String, uploaded: String, from: String, to: String, count: Int) = Upload(
        uploadId = id, fileName = file, status = UploadStatus.DONE, uploadedAt = t("${uploaded}T10:00:00+03:00"),
        operationCount = count, unreadCount = 0,
        periodFrom = from, periodTo = to, sourceName = "Т-Банк",
    )

    private val all = upload("u1", "operations_2026.ofx", "2026-09-25", "2026-04-01", "2026-09-25", 1086)
    private val aug = upload("u2", "operations_aug.ofx", "2026-09-02", "2026-08-01", "2026-08-31", 184)
    private val q2 = upload("u3", "operations_q2.ofx", "2026-07-01", "2026-04-01", "2026-06-30", 540)

    private val summary = StatementsSummary(
        uploadCount = 3, operationCount = 1086,
        firstOperationAt = t("2026-04-01T09:00:00+03:00"), lastOperationAt = t("2026-09-25T20:00:00+03:00"),
        gaps = emptyList(),
    )

    private val state = HistoryUiState(loading = false, list = StatementsList(summary, listOf(all, aug, q2)))

    private fun capture(name: String, state: HistoryUiState, dark: Boolean = false, popups: Boolean = false) =
        DesignCheck.capture(name, dark = dark, popups = popups) {
            HistoryContent(
                state, onBack = {}, onUpload = {}, onOpenUnread = { _, _ -> }, onRetry = {}, onAskDelete = {}, onDelete = {},
                onSnackbarShown = {},
            )
        }

    @Test fun uploadHistory() = capture("UploadHistory", state)

    @Test fun uploadHistoryDark() = capture("UploadHistoryDark", state, dark = true)

    @Test fun uploadHistoryConfirm() = capture("UploadHistoryConfirm", state.copy(confirmDelete = q2), popups = true)

    @Test fun uploadHistoryDeleted() = capture(
        "UploadHistoryDeleted",
        state.copy(list = StatementsList(summary.copy(uploadCount = 2), listOf(all, aug)), snackbar = "Загрузка удалена, обновляем аналитику"),
    )

    @Test fun uploadHistoryResolved() {
        val fixed = upload("u4", "operations_sep_fixed.ofx", "2026-10-12", "2026-09-01", "2026-09-30", 212)
        val sep = upload("u5", "operations_sep.ofx", "2026-10-10", "2026-09-01", "2026-09-30", 210)
            .copy(resolvedUnreadCount = 2, resolvedByUploadedAt = t("2026-10-12T10:00:00+03:00"))
        capture("UploadHistoryResolved", state.copy(list = StatementsList(summary, listOf(fixed, sep, q2))))
    }

    // No Figma artboard yet for a file without a period or for a processing upload (FIN-198).
    @Test fun uploadHistoryWithoutPeriod() = capture(
        "UploadHistoryWithoutPeriod",
        state.copy(list = StatementsList(null, listOf(all.copy(periodFrom = null, periodTo = null, operationCount = 0)))),
    )

    @Test fun uploadHistoryProcessing() = capture(
        "UploadHistoryProcessing",
        state.copy(list = StatementsList(null, listOf(all.copy(status = UploadStatus.PROCESSING, periodFrom = null, periodTo = null)))),
    )

    @Test fun uploadHistoryEmpty() = capture("UploadHistoryEmpty", HistoryUiState(loading = false, list = StatementsList(null, emptyList())))
    // FIN-223 fixture: proposed artboard; snapshots are not executed under the current instruction.
    @Test fun serverWarning() = capture("UploadHistoryServerWarning", state.copy(errorPresentation = ErrorPresentation(UserMessage.from("Сервис получил новый тип ошибки. Попробуйте позже"), ErrorSeverity.WARNING)))

    @Test fun refreshing() = capture("UploadHistoryRefreshing", state.copy(refreshing = true))
    @Test fun refreshingDark() = capture("UploadHistoryRefreshingDark", state.copy(refreshing = true), dark = true)

}
