package ru.finassist.pf.feature.operations.impl.ui

import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorSeverity
import ru.finassist.pf.core.common.error.UserMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.screenshot.DesignCheck
import ru.finassist.pf.feature.operations.impl.ui.OperationsDesignFixtures.detailState
import ru.finassist.pf.feature.operations.impl.ui.OperationsDesignFixtures.purchase
import ru.finassist.pf.feature.operations.impl.ui.OperationsDesignFixtures.refund
import ru.finassist.pf.feature.operations.impl.ui.OperationsDesignFixtures.today
import ru.finassist.pf.feature.operations.impl.ui.OperationsDesignFixtures.transfer
import ru.finassist.pf.feature.operations.impl.ui.OperationsDesignFixtures.zone

/**
 * Artboards `Detail` / `DetailDark` / `DetailRefund` / `DetailTransfer`: purchase, refund and own transfer;
 * `DetailNoDescription` — no artboard yet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DetailDesignCheckTest {

    private fun capture(name: String, state: DetailUiState, dark: Boolean = false) = DesignCheck.capture(name, dark = dark) {
        DetailContent(
            state, zone, today, onBack = {}, onRetry = {}, onOpenPicker = {}, onClosePicker = {}, onChoose = {},
            onSave = {}, onSnackbarShown = {},
        )
    }

    @Test fun detail() = capture("Detail", detailState(purchase))

    @Test fun detailDark() = capture("DetailDark", detailState(purchase), dark = true)

    @Test fun detailRefund() = capture("DetailRefund", detailState(refund))

    @Test fun detailTransfer() = capture("DetailTransfer", detailState(transfer))

    /**
     * No artboard in Figma yet (FIN-67): an operation whose statement line had no description — «Без описания»
     * as the title, no «Описание в выписке» row. The purchase of `Detail` with an empty title.
     */
    @Test fun detailNoDescription() = capture("DetailNoDescription", detailState(purchase.copy(title = "", description = null)))
    // FIN-223 fixture: proposed artboard; snapshots are not executed under the current instruction.
    @Test fun serverWarning() = capture("DetailServerWarning", DetailUiState(loading = false, error = true, errorPresentation = ErrorPresentation(UserMessage.from("Сервис получил новый тип ошибки. Попробуйте позже"), ErrorSeverity.WARNING)))

    @Test fun refreshing() = capture("DetailRefreshing", detailState(purchase).copy(refreshing = true))
    @Test fun refreshingDark() = capture("DetailRefreshingDark", detailState(purchase).copy(refreshing = true), dark = true)

}
