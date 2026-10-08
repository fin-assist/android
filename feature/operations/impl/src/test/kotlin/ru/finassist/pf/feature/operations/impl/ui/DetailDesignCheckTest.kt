package ru.finassist.pf.feature.operations.impl.ui

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

/** Artboards `Detail` / `DetailDark` / `DetailRefund` / `DetailTransfer`: purchase, refund and own transfer. */
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
}
