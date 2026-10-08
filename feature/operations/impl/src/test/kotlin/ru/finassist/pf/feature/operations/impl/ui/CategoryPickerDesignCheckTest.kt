package ru.finassist.pf.feature.operations.impl.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.model.OperationDetails
import ru.finassist.pf.core.screenshot.DesignCheck
import ru.finassist.pf.feature.operations.impl.ui.OperationsDesignFixtures.detailState
import ru.finassist.pf.feature.operations.impl.ui.OperationsDesignFixtures.purchase
import ru.finassist.pf.feature.operations.impl.ui.OperationsDesignFixtures.refund
import ru.finassist.pf.feature.operations.impl.ui.OperationsDesignFixtures.today
import ru.finassist.pf.feature.operations.impl.ui.OperationsDesignFixtures.zone

/**
 * Artboards `CategoryPicker` / `CategoryPickerDark` / `CategoryPickerChanged` / `CategoryPickerIncome` /
 * `CategoryPickerSearch`: the category sheet over the operation details.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CategoryPickerDesignCheckTest {

    private fun capture(
        name: String,
        op: OperationDetails = purchase,
        draft: String = op.categoryId,
        query: String = "",
        dark: Boolean = false,
    ) = DesignCheck.capture(name, dark = dark, popups = true) {
        DetailContent(
            detailState(op).copy(picker = true, draftCategoryId = draft), zone, today,
            onBack = {}, onRetry = {}, onOpenPicker = {}, onClosePicker = {}, onChoose = {}, onSave = {},
            onSnackbarShown = {}, pickerQuery = query,
        )
    }

    @Test fun categoryPicker() = capture("CategoryPicker")

    @Test fun categoryPickerDark() = capture("CategoryPickerDark", dark = true)

    /** «Рестораны» chosen instead of «Супермаркеты»: «Сохранить» becomes active. */
    @Test fun categoryPickerChanged() = capture("CategoryPickerChanged", draft = "cat_restaurants")

    @Test fun categoryPickerIncome() = capture("CategoryPickerIncome", op = refund)

    /** «такси» matches none of the mockup's categories: «Ничего не нашли». */
    @Test fun categoryPickerSearch() = capture("CategoryPickerSearch", query = "такси")
}
