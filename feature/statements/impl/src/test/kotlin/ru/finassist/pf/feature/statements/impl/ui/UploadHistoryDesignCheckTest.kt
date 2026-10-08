package ru.finassist.pf.feature.statements.impl.ui

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

/** Artboards `UploadHistory*`: three uploads, delete confirmation, after deletion, no uploads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UploadHistoryDesignCheckTest {
    private fun t(s: String) = OffsetDateTime.parse(s)

    private fun upload(id: String, file: String, uploaded: String, from: String, to: String, count: Int) = Upload(
        uploadId = id, fileName = file, status = UploadStatus.DONE, uploadedAt = t("${uploaded}T10:00:00+03:00"),
        operationCount = count, unreadCount = 0,
        firstOperationAt = t("${from}T09:00:00+03:00"), lastOperationAt = t("${to}T20:00:00+03:00"), sourceName = "Т-Банк",
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
                state, onBack = {}, onUpload = {}, onOpenUnread = {}, onRetry = {}, onAskDelete = {}, onDelete = {},
                onSnackbarShown = {},
            )
        }

    @Test fun uploadHistory() = capture("UploadHistory", state)

    @Test fun uploadHistoryDark() = capture("UploadHistoryDark", state, dark = true)

    @Test fun uploadHistoryConfirm() = capture("UploadHistoryConfirm", state.copy(confirmDelete = q2), popups = true)

    @Test fun uploadHistoryDeleted() = capture(
        "UploadHistoryDeleted",
        state.copy(list = StatementsList(summary.copy(uploadCount = 2), listOf(all, aug)), snackbar = "Загрузка удалена"),
    )

    @Test fun uploadHistoryEmpty() = capture("UploadHistoryEmpty", HistoryUiState(loading = false, list = StatementsList(null, emptyList())))
}
